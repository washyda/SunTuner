#!/system/bin/sh
set -eu
PATH=/system/bin
export PATH
umask 077

bundle=$1
action=$2
shift 2
root=/data/adb/sm8750-tuner
persistent=$root/persistent
cpu=/sys/devices/system/cpu
modules=/sys/module
boot_file=/proc/sys/kernel/random/boot_id
work=
pvc_pid=
owned_plan=
owned_observer=0

die() { printf 'ERROR %s\n' "$*" >&2; exit 1; }

find_cdt_dev() {
    for p in /dev/block/by-name/cdt /dev/block/bootdevice/by-name/cdt; do
        if [ -e "$p" ]; then
            printf '%s\n' "$p"
            return 0
        fi
    done
    return 1
}

restore_governors() {
    local journal="$session/stress-governors" policy previous file current failed=0
    [ -f "$journal" ] || return 0
    while read -r policy previous; do
        file="$cpu/cpufreq/policy$policy/scaling_governor"
        current=$(cat "$file") || { failed=1; continue; }
        if [ "$current" = performance ] && [ "$previous" != performance ]; then
            (printf '%s\n' "$previous" > "$file") || { failed=1; continue; }
            [ "$(cat "$file")" = "$previous" ] || failed=1
        fi
    done < "$journal"
    [ "$failed" = 0 ] || return 1
    rm -- "$journal"
}

set_performance() {
    local mask=$1 policy dir governor bits
    : > "$work/governors"
    for policy in 0 6; do
        if [ "$policy" = 0 ]; then bits=63; else bits=192; fi
        [ "$((mask & bits))" -ne 0 ] || continue
        dir="$cpu/cpufreq/policy$policy"
        grep -qw performance "$dir/scaling_available_governors" || die "policy$policy 缺少 performance 调速器"
        governor=$(cat "$dir/scaling_governor")
        [ -n "$governor" ] || die "无法读取 policy$policy 的调速器"
        printf '%s %s\n' "$policy" "$governor" >> "$work/governors"
    done
    mv "$work/governors" "$session/stress-governors"
    while read -r policy governor; do
        dir="$cpu/cpufreq/policy$policy"
        [ "$(cat "$dir/scaling_governor")" = "$governor" ] || die "policy$policy 正被其他程序调频"
        if [ "$governor" != performance ]; then
            printf 'performance\n' > "$dir/scaling_governor" || die "无法切换 policy$policy 的调速器"
        fi
        [ "$(cat "$dir/scaling_governor")" = performance ] || die "policy$policy 未进入 performance 模式"
    done < "$session/stress-governors"
}

restore_frequency_lock() {
    local journal="$session/stress-qos"
    [ -f "$journal" ] || return 0
    if [ -d "$modules/sm8750_stress_qos" ]; then
        rmmod sm8750_stress_qos || return 1
    fi
    rm -- "$journal"
}

lock_frequencies() {
    local mask=$1 freq0=$2 freq1=$3 policy bits khz
    for policy in 0 6; do
        if [ "$policy" = 0 ]; then bits=63; khz=$freq0; else bits=192; khz=$freq1; fi
        if [ "$((mask & bits))" = 0 ]; then
            [ "$khz" = 0 ] || die "未选中 policy$policy 的核心"
        else
            case "$khz" in [1-9][0-9][0-9][0-9][0-9][0-9]|[1-9][0-9][0-9][0-9][0-9][0-9][0-9]) ;; *) die "锁频值无效" ;; esac
            if ! grep -qw "$khz" "$cpu/cpufreq/policy$policy/scaling_available_frequencies"; then
                is_sm8850 && sm8850_boost_frequencies "$policy" | grep -qx "$khz" ||
                    die "policy$policy 不支持 $khz kHz"
            fi
        fi
    done
    [ ! -d "$modules/sm8750_stress_qos" ] || die "已有其他任务正在使用锁频模块"
    : > "$session/stress-qos"
    local asset
    asset=$(kernel_module_asset stress-qos) || die "当前内核缺少匹配的锁频模块"
    cp "$bundle/$asset" "$work/sm8750_stress_qos.ko"
    load_owned sm8750_stress_qos "pdp0_khz=$freq0" "pdp1_khz=$freq1"
}

cleanup() {
    rc=$?
    trap - EXIT
    if [ -n "$pvc_pid" ]; then
        [ -z "$work" ] || : > "$work/load.done" 2>/dev/null || true
        wait "$pvc_pid" 2>/dev/null || true
        pvc_pid=
    fi
    if [ -d "$modules/sm8750_pvc_corr_ro" ]; then
        rmmod sm8750_pvc_corr_ro || { printf 'ERROR PVC 采样模块卸载失败\n' >&2; rc=1; }
    fi
    restore_governors || { printf 'ERROR 调速器恢复失败，刷新时会重新尝试\n' >&2; rc=1; }
    restore_frequency_lock || { printf 'ERROR 临时频率上限清理失败，刷新时会重新尝试\n' >&2; rc=1; }
    unload_scmi_module || { printf 'ERROR SCMI 读表模块卸载失败\n' >&2; rc=1; }
    if [ "$owned_observer" = 1 ] && [ -d "$modules/sm8750_acd_observer" ]; then
        rmmod sm8750_acd_observer || { printf 'ERROR ACD 状态探针卸载失败\n' >&2; rc=1; }
    fi
    [ -z "$work" ] || rm -rf -- "$work" || rc=1
    exit "$rc"
}

is_sm8850() {
    case "$(getprop ro.soc.model)" in
        [sS][mM]8850) return 0 ;;
        *) return 1 ;;
    esac
}

check_sm8850_policies() {
    [ "$(cat "$cpu/cpufreq/policy0/related_cpus")" = '0 1 2 3 4 5' ] &&
        [ "$(cat "$cpu/cpufreq/policy6/related_cpus")" = '6 7' ] ||
        die "当前 SM8850 的 CPU 簇布局与 6+2 适配不匹配"
}

kernel_page_size() {
    local bytes
    bytes=$(getconf PAGESIZE 2>/dev/null) || bytes=
    case "$bytes" in
        ''|*[!0-9]*)
            awk '
                /^KernelPageSize:/ && $2 > 0 { print $2 * 1024; found = 1; exit }
                END { if (!found) exit 1 }
            ' /proc/self/smaps
            ;;
        *) printf '%s\n' "$bytes" ;;
    esac
}

kernel_module_asset() {
    local name=$1 directory=sm8750 pages
    if is_sm8850; then
        directory=sm8850/android16-6-4k
        case "$(uname -r)" in
            6.12.*-android16-5-*)
                pages=$(kernel_page_size) || return 1
                [ "$pages" = 4096 ] || return 1
                directory=sm8850/android16-5-4k
                ;;
        esac
    fi
    local asset="modules/$directory/$name.ko"
    [ -r "$bundle/$asset" ] || return 1
    printf '%s\n' "$asset"
}

sm8850_cdt_ready() {
    [ "$state" = STOCK_UNMODIFIED ] && stock_baseline_candidate || return 1
    case "$cdt_disk:$boot_platform_info" in
        empty:6,*) return 0 ;;
        stock:*) [ "$cdt_v6_can_enable" = 1 ] ;;
        *) return 1 ;;
    esac
}

sm8850_boost_frequencies() {
    local dir="$cpu/cpufreq/policy$1" max points freq
    [ -r "$dir/scaling_boost_frequencies" ] || return 0
    max=$(cat "$dir/cpuinfo_max_freq") || return 0
    points=$(cat "$dir/scaling_boost_frequencies") || return 0
    for freq in $points; do
        if [ "$freq" -le "$max" ]; then printf '%s\n' "$freq"; fi
    done
    return 0
}

sm8850_cpu_frequencies() {
    check_sm8850_policies
    local policy frequencies boost
    for policy in 0 6; do
        frequencies=$(cat "$cpu/cpufreq/policy$policy/scaling_available_frequencies") ||
            die "无法读取 policy$policy 频点表"
        boost=$(sm8850_boost_frequencies "$policy" | tr '\n' ' ')
        printf 'POLICY %s %s %s\n' "$policy" "$frequencies" "$boost"
    done
}

read_sm8850_frequencies() {
    is_sm8850 || die "需要 SM8850 平台"
    printf 'BOOT %s\n' "$(cat "$boot_file")"
    printf 'ONLINE %s\n' "$(cat "$cpu/online")"
    if kernel_module_asset stress-qos >/dev/null; then printf 'FIXED 1\n'; else printf 'FIXED 0\n'; fi
    if kernel_module_asset qcom-scmi-ro >/dev/null; then printf 'VOLTAGE 1\n'; else printf 'VOLTAGE 0\n'; fi
    sm8850_cpu_frequencies
}

check_firmware() {
    [ "$(id -u)" = 0 ] || die "需要 Root 权限"
    case "$action" in cdt_backup|cdt_status|cdt_recover|cdt_restore) return 0 ;; esac
    [ -d "$cpu/cpufreq/policy0" ] &&
        [ -d "$cpu/cpufreq/policy6" ] ||
        die "缺少 CPU policy0/policy6 调频接口"
    case "$action" in
        read|cdt_enable)
            [ -d /sys/bus/scmi_protocol ] || die "缺少 /sys/bus/scmi_protocol 接口" ;;
    esac
}

load_owned() {
    name=$1
    shift
    [ ! -d "$modules/$name" ] || die "模块 $name 正在被其他程序使用"
    insmod "$work/$name.ko" "$@" || die "加载 $name 失败（当前内核 $(uname -r)）"
}

plan() {
    local driver=qcom-scmi-ro
    if [ "$owned_plan" = sm8750_scmi_plan_ro ]; then driver=sm8750-scmi-plan-ro; fi
    set -- /sys/bus/scmi_protocol/drivers/"$driver"/*/snapshot
    [ "$#" = 1 ] && [ -r "$1" ] || die "SCMI PERF 读表模块未绑定"
    cat "$1"
}

stock_slot() {
    case "$1" in
        *" stable=1 state=0x1 "*" len=8 "*" cookie_state=2 cookie_magic=0xabcd9876 cookie_version=1") return 0 ;;
        *) return 1 ;;
    esac
}

prepare_cdt_tool() {
    [ -r "$bundle/cdt_tool" ] || die "缺少 CDT 工具二进制"
    cp "$bundle/cdt_tool" "$work/cdt_tool"
    chmod 700 "$work/cdt_tool"
}

probe_apns_slot() {
    apns_slot=unknown
    [ -r "$bundle/modules/sm8750/acd-observer.ko" ] || return 0
    [ ! -d "$modules/sm8750_acd_observer" ] || return 0
    cp "$bundle/modules/sm8750/acd-observer.ko" "$work/sm8750_acd_observer.ko"
    apns_slot=stock
    for domain in 0 1; do
        if insmod "$work/sm8750_acd_observer.ko" "pdp=$domain" 2>/dev/null; then
            owned_observer=1
            observed=$(cat "$modules/sm8750_acd_observer/parameters/snapshot" 2>/dev/null || true)
            rmmod sm8750_acd_observer 2>/dev/null || true
            owned_observer=0
            if ! stock_slot "$observed"; then
                apns_slot=external
                return 0
            fi
        else
            apns_slot=unknown
            return 0
        fi
    done
}

inspect_state() {
    cdt_dev=$(find_cdt_dev) || die "未找到 /dev/block/by-name/cdt 分区"
    "$work/cdt_tool" status "$cdt_dev" "$persistent/cdt-orig.bin" > "$work/cdt.status" ||
        die "读取 CDT 分区状态失败"
    boot_cdt=unknown
    boot_platform_info=unknown
    read_cdt_status "$work/cdt.status"
    if is_sm8850; then apns_slot=unknown; else probe_apns_slot; fi
    classify_state
}

read_cdt_status() {
    cdt_disk=unknown
    cdt_key7=absent
    cdt_platform=none
    cdt_v6_can_enable=0
    cdt_backup_status=missing
    while IFS='=' read -r k v; do
        case "$k" in
            CDT_DISK) cdt_disk=$v ;;
            CDT_KEY7) cdt_key7=$v ;;
            CDT_PLATFORM) cdt_platform=$v ;;
            CDT_V6_CAN_ENABLE) cdt_v6_can_enable=$v ;;
            CDT_BACKUP_STATUS) cdt_backup_status=$v ;;
            BOOT_CDT) boot_cdt=$v ;;
            BOOT_PLATFORM_INFO) boot_platform_info=$v ;;
        esac
    done < "$1"
}

classify_state() {
    if [ "$cdt_backup_status" = mismatch ]; then
        state=CDT_RECOVERY_REQUIRED
    elif [ "$cdt_disk" = corrupt ] || [ "$cdt_backup_status" = invalid ]; then
        state=CORRUPT_CDT
    elif [ "$cdt_disk" = slt_enabled ]; then
        state=CDT_WRITTEN
    elif [ "$apns_slot" = external ]; then
        state=EXTERNAL_RUNTIME
    else
        state=STOCK_UNMODIFIED
    fi

    has_backup=0
    case "$cdt_backup_status" in match|enabled_match|mismatch) has_backup=1 ;; esac
}

stock_baseline_candidate() {
    # An older app build may have recalibrated this running boot.
    case "$cdt_disk" in empty|stock) ;; *) return 1 ;; esac
    [ "$apns_slot" != external ] &&
        [ ! -e "$session/manual-slt-0" ] && [ ! -e "$session/manual-slt-1" ] &&
        [ "$(cat "$persistent/cdt-restore.boot" 2>/dev/null || true)" != "$boot" ]
}

maybe_persist_stock_baseline() {
    local plan_file="$1" candidate="$persistent/stock-baseline.csv.tmp"
    stock_baseline_candidate || return 0
    case "$boot_cdt:$action" in
        not_found:*|unknown:cdt_enable) ;;
        applied:cdt_enable) is_sm8850 || return 0 ;;
        *) return 0 ;;
    esac
    plan_frequencies "$plan_file" >/dev/null || return 0
    if { printf '# boot_id=%s\n' "$boot" && grep '^[01],' "$plan_file"; } > "$candidate" &&
        mv "$candidate" "$persistent/stock-baseline.csv"; then
        return 0
    fi
    rm -f -- "$candidate"
    if is_sm8850 && [ "$action" = cdt_enable ]; then die "无法保存刷入前的电压计划"; fi
    printf 'WARN 无法保存刷入前的电压计划\n' >&2
}

scmi_module_busy() {
    [ -d "$modules/qcom_scmi_ro" ] || [ -d "$modules/sm8750_scmi_plan_ro" ]
}

insmod_scmi_asset() {
    cp "$bundle/$1" "$work/qcom-scmi-ro.ko" && insmod "$work/qcom-scmi-ro.ko" || return 1
    if [ -d "$modules/qcom_scmi_ro" ]; then
        owned_plan=qcom_scmi_ro
    elif [ -d "$modules/sm8750_scmi_plan_ro" ]; then
        owned_plan=sm8750_scmi_plan_ro
    else
        return 1
    fi
}

unload_scmi_module() {
    [ -n "$owned_plan" ] || return 0
    if [ -d "$modules/$owned_plan" ]; then rmmod "$owned_plan" || return 1; fi
    owned_plan=
}

load_scmi_module() {
    local asset
    asset=$(kernel_module_asset qcom-scmi-ro) || die "缺少对应内核的 SCMI 电压读表模块"
    scmi_module_busy && die "SCMI 读表模块正在被其他程序使用"
    insmod_scmi_asset "$asset" || die "加载 qcom-scmi-ro 失败（当前内核 $(uname -r)）"
}

report() {
    inspect_state
    plan > "$work/current"
    if is_sm8850; then
        validate_current_plan
    fi
    maybe_persist_stock_baseline "$work/current"
    prepare_report
    emit_report
}

prepare_report() {
    online=$(cat "$cpu/online") || die "无法读取在线 CPU"
    printf '%s\n' "$online" | grep -Eq '^[0-7](-[0-7])?(,[0-7](-[0-7])?)*$' ||
        die "在线 CPU 状态无效"
    local baseline_file="$persistent/stock-baseline.csv" baseline_boot
    if [ -f "$baseline_file" ]; then
        baseline_boot=$(sed -n '1s/^# boot_id=//p' "$baseline_file" 2>/dev/null) || baseline_boot=
        if [ -z "$baseline_boot" ]; then
            baseline_boot=$(cat "$persistent/stock-baseline.boot" 2>/dev/null || true)
        fi
        if [ "$state" != CDT_WRITTEN ] || [ "$baseline_boot" != "$boot" ]; then
            cp "$baseline_file" "$work/baseline" 2>/dev/null || rm -f "$work/baseline"
        fi
    fi
}

plan_frequencies() {
    local sm8850=0
    if is_sm8850; then sm8850=1; fi
    awk -F, -v sm8850="$sm8850" '
        /^[01],/ {
            if (NF != 7) exit 1
            for (i = 1; i <= NF; i++) if ($i !~ /^[0-9]+$/ || $i > 2147483647) exit 1
            if ($2 != count[$1] || $2 != $7 || $4 <= 0 || $6 <= previous[$1]) exit 1
            if (sm8850) {
                coeff = $1 == 0 ? 100 : 150
                mv = int(sqrt($4 * 1e9 / (coeff * $6)) / 4 + 0.5) * 4
                if (int(coeff * $6 * mv * mv / 1e9) != $4) exit 1
            }
            count[$1]++
            previous[$1] = $6
            print $1, $2, $6
        }
        END {
            if (sm8850 ? (count[0] < 1 || count[1] < 1) : (count[0] != 16 || count[1] != 16)) exit 1
        }
    ' "$1"
}

validate_current_plan() {
    plan_frequencies "$work/current" > "$work/current.freq" || die "SCMI 电压计划校验失败"
    if is_sm8850; then
        sm8850_cpu_frequencies > "$work/cpu.freq"
        awk '
            FNR == NR { plan[$1, $3] = 1; next }
            {
                domain = $2 == 0 ? 0 : 1
                for (i = 3; i <= NF; i++) if (!plan[domain, $i]) exit 1
            }
        ' "$work/current.freq" "$work/cpu.freq" || die "SM8850 系统频点与 SCMI 电压计划不匹配"
    fi
}

emit_report() {
    if stock_baseline_candidate && [ "$boot_cdt" = not_found ]; then
        baseline_source=current_stock
    elif [ -f "$work/baseline" ]; then
        baseline_source=persisted
    else
        baseline_source=none
    fi

    printf 'BOOT %s\nSTATE %s\nCDT_DISK %s\nCDT_KEY7 %s\nCDT_HAS_BACKUP %s\nCDT_PLATFORM %s\nBOOT_CDT %s\nBOOT_PLATFORM_INFO %s\nAPNS_SLOT %s\nONLINE %s\nBASELINE_SOURCE %s\n' \
        "$boot" "$state" "$cdt_disk" "$cdt_key7" "$has_backup" "$cdt_platform" \
        "$boot_cdt" "$boot_platform_info" "$apns_slot" "$online" "$baseline_source"
    if is_sm8850; then
        flash_supported=0
        if sm8850_cdt_ready; then flash_supported=1; fi
        printf 'PLAN_MODEL SM8850\nCDT_FLASH_SUPPORTED %s\n' "$flash_supported"
        if [ "$(sed -n '1s/^# boot_id=//p' "$persistent/stock-baseline.csv" 2>/dev/null || true)" = "$boot" ] &&
            [ "$state" = CDT_WRITTEN ]; then
            printf 'CDT_PENDING_REBOOT 1\n'
        fi
    fi

    if [ "$baseline_source" = persisted ]; then
        printf 'BEGIN baseline\n'
        cat "$work/baseline"
    fi
    printf 'BEGIN current\n'
    cat "$work/current"
    printf 'END\n'
}

cdt_stage() {
    case "$action" in
        cdt_enable|cdt_restore) printf 'CDT_STAGE=%s\n' "$1" >&2 ;;
    esac
}

cdt_error() {
    while IFS= read -r detail; do
        case "$detail" in CDT_ERR=*) printf '%s\n' "$detail" >&2 ;; esac
        case "$detail" in
            CDT_STEP=*|CDT_INFO=*) printf '%s\n' "$detail" >&2 ;;
            CDT_ERR=rolled_back) printf 'ERROR 已恢复并校验操作前的完整 CDT\n' >&2 ;;
            CDT_ERR=unchanged) printf 'ERROR 写入未开始，CDT 未更改\n' >&2 ;;
            CDT_ERR=backup_mismatch) printf 'ERROR 当前 CDT 与原厂备份不一致，请先还原分区。\n' >&2 ;;
            CDT_ERR=backup_invalid) printf 'ERROR 原厂 CDT 备份无效，已停止写入。\n' >&2 ;;
            CDT_ERR=restore_image_size) printf 'ERROR CDT 镜像无法读取，或与分区大小不一致。\n' >&2 ;;
            CDT_ERR=restore_image_invalid) printf 'ERROR CDT 镜像损坏或不是原厂格式。\n' >&2 ;;
            CDT_ERR=restore_image_mismatch) printf 'ERROR 所选原厂镜像与当前 CDT 不匹配。\n' >&2 ;;
            CDT_ERR=restore_backup_conflict) printf 'ERROR 无法保存所选备份，或它与已有原厂备份不一致。\n' >&2 ;;
            CDT_ERR=rollback_failed:*|CDT_ERR=recovery_failed:*)
                printf 'ERROR 无法确认 CDT 已恢复，恢复记录已保留。请勿重启，先刷新重试恢复。\n' >&2
                printf 'ERROR %s\n' "$detail" >&2 ;;
            *) [ -z "$detail" ] || printf 'ERROR %s\n' "$detail" >&2 ;;
        esac
    done < "$1"
    die "$2"
}

recover_cdt_if_pending() {
    [ -e "$persistent/cdt-orig.bin.transaction" ] || return 0
    printf 'CDT_RECOVERY_BEGIN\n'
    prepare_cdt_tool
    cdt_dev=$(find_cdt_dev) || die "发现待恢复 CDT，未找到 CDT 分区"
    "$work/cdt_tool" recover "$cdt_dev" "$persistent/cdt-orig.bin" 2>"$work/recover.err" ||
        cdt_error "$work/recover.err" "CDT 恢复失败"
    cat "$work/recover.err" >&2
}

run_cdt_operation() {
    verb=$1
    device=$2
    shift 2
    printf 'CDT_OPERATION_BEGIN\n'
    native_rc=0
    "$work/cdt_tool" "$verb" "$device" "$persistent/cdt-orig.bin" "$@" >"$work/cdt.out" 2>"$work/cdt.err" || native_rc=$?
    cat "$work/cdt.out"
    if [ "$native_rc" -ne 0 ]; then
        if [ "${restore_marker_added:-0}" = 1 ] &&
            grep -Eq '^CDT_RESULT=(unchanged|rolled_back)$' "$work/cdt.out"; then
            rm -f -- "$persistent/cdt-restore.boot" || true
        fi
        if grep -q '^CDT_ERR=no_backup$' "$work/cdt.err"; then
            die "CDT_NO_BACKUP 未找到原始 CDT 备份，若需移除 Key 7 恢复出厂请二次确认"
        fi
        cdt_error "$work/cdt.err" "CDT 操作失败"
    fi
    cat "$work/cdt.err" >&2
}

prev_pvc_t0_ns() {
    dmesg | awk '
        /sm8750_pvc_corr: .*t0_ns=/ {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^t0_ns=/) {
                    sub(/^t0_ns=/, "", $i)
                    v = $i + 0
                    if (v > max) max = v
                }
            }
        }
        END { printf "%.0f\n", max + 0 }
    '
}

parse_pvc_dmesg() {
    prev_t0=$1
    dmesg | awk -v min_t0="$prev_t0" '
        function hex4(s,    i, c, v, res) {
            if (length(s) != 4) return -1
            res = 0
            for (i = 1; i <= 4; i++) {
                c = tolower(substr(s, i, 1))
                v = index("0123456789abcdef", c) - 1
                if (v < 0) return -1
                digits[i] = v
            }
            return (digits[1] * 16 + digits[2]) + (digits[3] * 16 + digits[4]) * 256
        }
        /sm8750_pvc_corr:/ && /t0_ns=/ {
            label = ""; rc = ""; st = ""; setv = ""; val = ""; t0 = 0; k0 = ""; k6 = ""
            for (i = 1; i <= NF; i++) {
                if ($i == "pvc0_default" || $i == "pvc1_shared") label = $i
                else if ($i ~ /^rc=/) rc = substr($i, 4)
                else if ($i ~ /^status=/) st = substr($i, 8)
                else if ($i ~ /^set=/) setv = substr($i, 5)
                else if ($i ~ /^valid=/) val = substr($i, 7)
                else if ($i ~ /^t0_ns=/) t0 = substr($i, 7) + 0
                else if ($i ~ /^kHz0=/) k0 = substr($i, 6)
                else if ($i ~ /^kHz6=/) k6 = substr($i, 6)
            }
            if (label == "" || t0 <= min_t0 + 0) next
            if (rc != "0,0,0") next
            if (st !~ /^8[0-9a-fA-F],8[0-9a-fA-F]$/) next
            if (setv == "" || setv != val) next
            mv = hex4(setv)
            if (mv <= 0) next
            if (label == "pvc0_default") {
                split(k0, ka, ",")
                if (ka[1] == "" || ka[1] != ka[2] || ka[1] + 0 <= 0) next
                key = "0 " ka[1]
            } else {
                split(k6, ka, ",")
                if (ka[1] == "" || ka[1] != ka[2] || ka[1] + 0 <= 0) next
                key = "1 " ka[1]
            }
            c = ++cnt[key]
            vals[key, c] = mv
            total++
        }
        END {
            if (total == 0) {
                print "PVC_STATUS empty"
                exit
            }
            print "PVC_STATUS ok"
            for (key in cnt) {
                n = cnt[key]
                for (i = 1; i <= n; i++) arr[i] = vals[key, i]
                for (i = 2; i <= n; i++) {
                    x = arr[i]
                    j = i - 1
                    while (j >= 1 && arr[j] > x) {
                        arr[j + 1] = arr[j]
                        j--
                    }
                    arr[j + 1] = x
                }
                med = (n % 2 == 1) ? arr[(n + 1) / 2] : int((arr[n / 2] + arr[n / 2 + 1]) / 2)
                printf "PVC %s %d %d %d %d\n", key, arr[1], med, arr[n], n
            }
        }
    '
}

run_pvc_sampler() {
    ready_file=$1
    stop_file=$2
    prev_t0=$3
    out_file=$4
    ko_src=$5
    ko_dst=$6
    exec 0</dev/null 9>&-
    tries=0
    while [ ! -e "$ready_file" ] && [ ! -e "$stop_file" ] && [ "$tries" -lt 160 ]; do
        sleep 0.2
        tries=$((tries + 1))
    done
    if [ ! -e "$ready_file" ] || [ -e "$stop_file" ]; then
        printf 'PVC_STATUS skipped\n' > "$out_file"
        return 0
    fi
    sleep 1.2
    if [ ! -e "$ready_file" ] || [ -e "$stop_file" ]; then
        printf 'PVC_STATUS skipped\n' > "$out_file"
        return 0
    fi
    if [ -d "$modules/sm8750_pvc_corr_ro" ]; then
        printf 'PVC_STATUS busy\n' > "$out_file"
        return 0
    fi
    cp "$ko_src" "$ko_dst"
    if ! insmod "$ko_dst" >/dev/null 2>&1; then
        printf 'PVC_STATUS unsupported\n' > "$out_file"
        return 0
    fi
    rmmod sm8750_pvc_corr_ro >/dev/null 2>&1 || true
    parse_pvc_dmesg "$prev_t0" > "$out_file"
}

print_diagnostic_log() {
    awk '
        {
            line = tolower($0)
            if (line ~ /serial|imei|meid|mac[ _-]*addr|keybox|androidboot[.]/) next
            if (line !~ /boot_cdt_init|(^|[^[:alnum:]_])cdt([^[:alnum:]_]|$)|platform id:|platform[[:space:]]*:|platforminfo|calibrationdxe|calibration (done|start)|calcomp|(^|[^[:alnum:]_])(slt|acd|apns)([^[:alnum:]_]|$)/) next
            gsub(/[[:cntrl:]]/, " ")
            print substr($0, 1, 420)
            if (++count == 100) {
                print "[log excerpt limited to 100 lines]"
                exit
            }
        }
        END { if (!count) print "[no matching entries]" }
    '
}

diagnostic_read_only_value() {
    local label="$1" value
    shift
    value=$("$@" 2>/dev/null) || value=unknown
    case "$value" in 0|1) ;; *) value=unknown ;; esac
    printf '%s=%s\n' "$label" "$value"
}

print_cdt_write_log() {
    awk -v block="$1" -v parent="$2" -v audit_only="$3" '
        function has_name(line, name,    rest, i, before, after) {
            if (name == "") return 0
            rest = line
            while ((i = index(rest, name)) != 0) {
                before = i == 1 ? "" : substr(rest, i - 1, 1)
                after = substr(rest, i + length(name), 1)
                if (before !~ /[[:alnum:]_]/ && after !~ /[[:alnum:]_]/) return 1
                rest = substr(rest, i + length(name))
            }
            return 0
        }
        {
            line = tolower($0)
            if (line ~ /serial|imei|meid|mac[ _-]*addr|keybox|androidboot[.]/) next
            cdt = has_name(line, "cdt") || has_name(line, block)
            if ((has_name(line, "baseband_guard") || has_name(line, "bbg")) &&
                line ~ /deny|denied|reject|blocked|write[ _-]*protect/) {
                if (!cdt && !has_name(line, "cdt_tool")) next
            } else if (line ~ /avc:[[:space:]]*denied/) {
                if (!cdt && !(has_name(line, "cdt_tool") && line ~ /tclass=blk_file/)) next
            } else {
                if (audit_only || !(cdt || has_name(line, parent))) next
                if (line !~ /write[ _-]*protect|write[ _-]*(denied|refused|reject|fail|error)|read[ _-]*only|operation not permitted|permission denied|op .*write|i\/o error.*write|write.*i\/o error/) next
            }
            gsub(/[[:cntrl:]]/, " ")
            entries[count++ % 60] = substr($0, 1, 420)
        }
        END {
            if (!count) print "[no matching retained entries]"
            if (count > 60) print "[showing the latest 60 matching entries]"
            for (i = count > 60 ? count - 60 : 0; i < count; ++i) print entries[i % 60]
        }
    '
}

scmi_module_kernel_log() {
    dmesg 2>/dev/null | awk '/qcom_scmi_ro:|qcom-scmi-ro:|sm8750_scmi_plan_ro:/'
}

read_sm8850_perf() {
    local asset
    if ! asset=$(kernel_module_asset qcom-scmi-ro); then
        printf 'SCMI_PERF=module_missing\n'
        return 1
    fi
    if scmi_module_busy; then
        printf 'SCMI_PERF=module_busy\n'
        return 1
    fi
    printf 'SCMI_PERF_MODULE=%s\n' "$asset"
    scmi_module_kernel_log > "$work/scmi_dmesg_before"
    if ! insmod_scmi_asset "$asset"; then
        printf 'SCMI_PERF=module_load_failed\n'
        scmi_module_kernel_log > "$work/scmi_dmesg_after"
        awk 'FILENAME == ARGV[1] { old[$0]++; next } old[$0] { old[$0]--; next } { print }' \
            "$work/scmi_dmesg_before" "$work/scmi_dmesg_after" | tail -n 12
        return 1
    fi
    local result=0
    if (plan) > "$work/current"; then
        if (validate_current_plan); then
            printf 'SCMI_PERF=ok\n'
        else
            printf 'SCMI_PERF=invalid_plan\n'
            result=1
        fi
        cat "$work/current"
    else
        printf 'SCMI_PERF=read_failed\n'
        result=1
    fi
    if ! unload_scmi_module; then
        printf 'SCMI_PERF_UNLOAD=failed\n'
        result=1
    fi
    return "$result"
}

collect_diagnostics() {
    local cdt_dev path prop value attribute policy
    local cdt_block= cdt_parent= cdt_sysfs= parent_sysfs=
    printf '[boot and kernel]\n'
    printf 'KERNEL_RELEASE=%s\n' "$(uname -r)"
    printf 'KERNEL_PAGE_SIZE=%s\n' "$(kernel_page_size 2>/dev/null || printf unknown)"
    for prop in ro.boot.slot_suffix ro.boot.slot ro.boot.hardware ro.product.vendor.device         ro.vendor.build.fingerprint ro.soc.manufacturer ro.soc.model; do
        value=$(getprop "$prop")
        [ -z "$value" ] || printf '%s=%s\n' "$prop" "$value"
    done

    printf '\n[cdt partition]\n'
    if [ -e "$persistent/cdt-orig.bin.transaction" ]; then
        printf 'CDT_TRANSACTION=pending_recovery\n'
    fi
    if cdt_dev=$(find_cdt_dev) && [ -b "$cdt_dev" ]; then
        path=$(readlink -f "$cdt_dev") || path=unknown
        printf 'CDT_DEV=%s\nCDT_BLOCK=%s\n' "$cdt_dev" "$path"
        value=$(blockdev --getsize64 "$cdt_dev" 2>/dev/null) || value=unknown
        printf 'CDT_PARTITION_BYTES=%s\n' "$value"
        diagnostic_read_only_value CDT_BLOCKDEV_RO blockdev --getro "$cdt_dev"
        if [ "$path" != unknown ]; then
            cdt_block=${path##*/}
            cdt_sysfs=$(readlink -f "/sys/class/block/$cdt_block" 2>/dev/null) || cdt_sysfs=
        fi
        if [ -n "$cdt_sysfs" ] && [ -f "$cdt_sysfs/partition" ]; then
            parent_sysfs=${cdt_sysfs%/*}
            cdt_parent=${parent_sysfs##*/}
        fi
        diagnostic_read_only_value CDT_SYSFS_RO cat "${cdt_sysfs:+$cdt_sysfs/ro}"
        if [ -n "$parent_sysfs" ]; then
            printf 'CDT_PARENT_BLOCK=%s\n' "$cdt_parent"
            diagnostic_read_only_value CDT_PARENT_SYSFS_RO cat "$parent_sysfs/ro"
        fi
        printf 'RO values: 1=read_only, 0=not_marked_read_only; 0 does not prove writes are permitted.\n'
        if cp "$bundle/cdt_tool" "$work/cdt_tool" 2>/dev/null && chmod 700 "$work/cdt_tool" 2>/dev/null &&
            "$work/cdt_tool" backup "$cdt_dev" "$work/cdt.bin" 2>/dev/null &&
            "$work/cdt_tool" status "$work/cdt.bin" "$persistent/cdt-orig.bin" > "$work/cdt.status" 2>/dev/null; then
            value=$(sha256sum "$work/cdt.bin") || value=unknown
            printf 'CDT_SHA256=%s\n' "${value%% *}"
            sed '/^CDT_DEV=/d' "$work/cdt.status"
            printf 'BOOT_CDT is a boot-log heuristic, not proof that SLT/ACD ran.\n'
        else
            printf 'CDT_READ=unavailable (read or parser failed)\n'
        fi
    else
        printf 'CDT_READ=partition_not_found\n'
    fi

    printf '\n[cpu and scmi]\n'
    value=$(cat "$cpu/online" 2>/dev/null) || value=unknown
    printf 'CPU_ONLINE=%s\n' "$value"
    for policy in "$cpu"/cpufreq/policy*; do
        [ -d "$policy" ] || continue
        for attribute in related_cpus scaling_driver scaling_available_frequencies cpuinfo_max_freq scaling_max_freq scaling_boost_frequencies; do
            value=$(cat "$policy/$attribute" 2>/dev/null) || continue
            printf '%s.%s=%s\n' "${policy##*/}" "$attribute" "$value"
        done
        if [ -r "$policy/stats/time_in_state" ]; then
            printf '%s.time_in_state=present\n' "${policy##*/}"
        fi
    done
    if [ -d /sys/bus/scmi_protocol ]; then
        printf 'SCMI_BUS=present\n'
        for path in /sys/bus/scmi_protocol/devices/*; do
            [ -e "$path" ] || continue
            value=$(cat "$path/modalias" 2>/dev/null) || continue
            printf 'SCMI_DEVICE=%s %s\n' "${path##*/}" "$value"
        done
    else
        printf 'SCMI_BUS=unavailable\n'
    fi

    printf '\n[bootloader log excerpt]\n'
    if head -c 262144 /proc/bootloader_log > "$work/boot.log" 2>/dev/null; then
        if grep -iq 'SLT Platform Key not found' "$work/boot.log"; then
            printf 'SLT_KEY_LOOKUP=not_found\n'
        else
            printf 'SLT_KEY_LOOKUP=not_confirmed_by_log\n'
        fi
        print_diagnostic_log < "$work/boot.log"
    else
        printf '[bootloader_log unavailable]\n'
    fi
    printf '\n[kernel log excerpt]\n'
    if dmesg > "$work/kernel.log" 2>/dev/null; then
        print_diagnostic_log < "$work/kernel.log"
    else
        rm -f -- "$work/kernel.log"
        printf '[kernel log unavailable]\n'
    fi

    printf '\n[CDT write protection and security modules]\n'
    value=$(getenforce 2>/dev/null) || value=unknown
    case "$value" in Enforcing|Permissive|Disabled) ;; *) value=unknown ;; esac
    printf 'SELINUX_MODE=%s\n' "$value"
    value=$(cat /sys/kernel/security/lsm 2>/dev/null) || value=unknown
    printf 'ACTIVE_LSM=%s\n' "${value:-unknown}"
    printf 'Only retained CDT or parent-device messages are shown; missing entries do not establish write access.\n'
    printf '\n[CDT write-related kernel messages]\n'
    if [ -f "$work/kernel.log" ]; then
        print_cdt_write_log "$cdt_block" "$cdt_parent" 0 < "$work/kernel.log"
    else
        printf '[kernel log unavailable]\n'
    fi
    printf '\n[CDT SELinux and BBG denials from logcat]\n'
    if logcat -b all -d -t 2000 > "$work/audit.log" 2>/dev/null; then
        print_cdt_write_log "$cdt_block" "$cdt_parent" 1 < "$work/audit.log"
    else
        printf '[logcat unavailable]\n'
    fi
}

if [ "$action" = crash_logs ]; then
    exec sh "$bundle/crash_logs.sh" "$@"
fi

if [ "$action" = read_frequencies ]; then
    [ "$#" = 0 ] || die "频点读取参数无效"
    [ "$(id -u)" = 0 ] || die "需要 Root 权限"
    read_sm8850_frequencies
    exit 0
fi

if [ "$action" = diagnostics ]; then
    [ "$#" = 0 ] || die "诊断参数无效"
    [ "$(id -u)" = 0 ] || die "需要 Root 权限"
    mkdir -p "$root"
    exec 9>"$root/lock"
    flock -n 9 9>&9 || die "有另一个降压或烤机任务正在运行"
    work=$(mktemp -d "$root/diagnostics.XXXXXXXX")
    trap 'unload_scmi_module 2>/dev/null || true; rm -rf -- "$work"' EXIT
    trap 'exit 143' TERM HUP INT
    collect_diagnostics
    if is_sm8850; then
        printf '\n[cpu scmi perf probe]\n'
        read_sm8850_perf || true
    fi
    exit 0
fi

cdt_stage check_firmware
check_firmware
cdt_stage acquire_lock
mkdir -p "$root" "$persistent"
exec 9>"$root/lock"
flock -n 9 9>&9 || die "有另一个降压或烤机任务正在运行"
if [ "$action" = cdt_backup ]; then
    [ "$#" = 0 ] || die "备份参数无效"
    cdt_dev=$(find_cdt_dev) || die "未找到 CDT 分区"
    [ -b "$cdt_dev" ] || die "CDT 路径不是块设备"
    work=$(mktemp -d "$root/work.XXXXXXXX")
    trap 'rm -rf -- "$work"' EXIT
    trap 'exit 143' TERM HUP INT
    prepare_cdt_tool
    "$work/cdt_tool" backup "$cdt_dev" "$work/cdt.bin" || die "读取完整 CDT 镜像失败"
    digest=$(sha256sum "$work/cdt.bin") || die "计算备份校验值失败"
    printf 'CDT_SHA256 %s\n' "${digest%% *}"
    printf 'CDT_IMAGE_BEGIN\n'
    base64 "$work/cdt.bin" || die "读取备份数据失败"
    printf '\nCDT_IMAGE_END\n'
    exit 0
fi
boot=$(cat "$boot_file")
case "$boot" in ????????-????-????-????-????????????) ;; *) die "无法确认启动 ID" ;; esac
case "$action" in
    cdt_status|cdt_recover|cdt_restore)
        work=$(mktemp -d "$root/work.XXXXXXXX")
        trap 'rm -rf -- "$work"' EXIT
        trap 'exit 143' TERM HUP INT
        trap '' PIPE
        if [ "$action" = cdt_restore ] || [ "$#" -gt 0 ]; then
            [ "$#" -ge 1 ] && [ "$1" = "$boot" ] || die "启动 ID 已改变，请刷新"
        fi
        prepare_cdt_tool
        cdt_dev=$(find_cdt_dev) || die "未找到 CDT 分区"
        if [ "$action" = cdt_restore ]; then
            shift
            case "$#:${1:-}" in
                0:) ;;
                1:--fallback-clean) ! is_sm8850 || die "SM8850 需要原厂 CDT 完整备份才能还原" ;;
                2:--from-file) [ -f "$2" ] || die "无法打开所选 CDT 镜像" ;;
                *) die "还原参数无效" ;;
            esac
            recover_cdt_if_pending
            cdt_stage restore_and_verify
            # The running plan can still be from the pre-restore CDT until the next boot.
            if [ "$(cat "$persistent/cdt-restore.boot" 2>/dev/null || true)" != "$boot" ]; then
                printf '%s\n' "$boot" > "$persistent/cdt-restore.boot" ||
                    die "无法记录本次还原的启动 ID"
                restore_marker_added=1
            fi
            run_cdt_operation restore "$cdt_dev" "$@"
        elif [ "$action" = cdt_recover ]; then
            [ "$#" -le 1 ] || die "恢复参数无效"
            recover_cdt_if_pending
        else
            [ ! -e "$persistent/cdt-orig.bin.transaction" ] || die "CDT 恢复记录尚未处理，请刷新"
            printf 'BOOT %s\n' "$boot"
            "$work/cdt_tool" status "$cdt_dev" "$persistent/cdt-orig.bin"
        fi
        exit 0 ;;
esac
session="$root/session"
if [ "$(cat "$session/boot" 2>/dev/null || true)" != "$boot" ]; then
    rm -rf -- "$session"
    mkdir "$session"
    printf '%s\n' "$boot" > "$session/boot"
fi
work=$(mktemp -d "$root/work.XXXXXXXX")
trap cleanup EXIT
trap 'exit 143' TERM HUP INT
trap '' PIPE
restore_governors || die "无法恢复上次烤机前的调速器"
restore_frequency_lock || die "无法清理上次烤机的临时频率上限"
case "$action" in
    read|cdt_enable|reboot) recover_cdt_if_pending ;;
esac

case "$action" in
    reboot)
        [ "$#" = 1 ] && [ "$1" = "$boot" ] || die "启动 ID 已改变，请刷新"
        /system/bin/svc power reboot --no-wait ;;
    load)
        [ "$#" = 7 ] || [ "$#" = 8 ] || die "烤机参数不完整"
        [ "$1" = "$boot" ] || die "启动 ID 已改变，请刷新"
        shift
        workload=${7:-mixed}
        case "$workload" in mixed|int|fp) ;; *) die "算力测试类型无效" ;; esac
        case "$1" in [1-9]|[1-9][0-9]|[1-9][0-9][0-9]) ;; *) die "核心掩码无效" ;; esac
        [ "$1" -le 255 ] || die "核心掩码无效"
        if is_sm8850; then check_sm8850_policies; fi
        case "$4" in
            max) set_performance "$1" ;;
            fixed) lock_frequencies "$1" "$5" "$6"; set_performance "$1" ;;
            system) ;;
            *) die "烤机调频模式无效" ;;
        esac

        if ! is_sm8850; then
            prepare_cdt_tool
            inspect_state
        fi
        cp "$bundle/load" "$work/load"
        chmod 700 "$work/load"
        : > "$work/pvc.out"
        if ! is_sm8850 && [ "$3" -ge 6 ] && [ -r "$bundle/modules/sm8750/pvc-corr-ro.ko" ]; then
            prev_t0=$(prev_pvc_t0_ns)
            run_pvc_sampler "$work/pvc.ready" "$work/load.done" "$prev_t0" "$work/pvc.out" \
                "$bundle/modules/sm8750/pvc-corr-ro.ko" "$work/sm8750_pvc_corr_ro.ko" &
            pvc_pid=$!
        else
            printf 'PVC_STATUS skipped\n' > "$work/pvc.out"
        fi

        SM8750_PVC_READY_FILE="$work/pvc.ready" SM8750_SUMMARY_FILE="$work/load.summary" \
            "$work/load" "$1" "$2" "$3" "$workload" 9>&9
        : > "$work/load.done"
        if [ -n "$pvc_pid" ]; then
            wait "$pvc_pid" 2>/dev/null || true
            pvc_pid=
        fi
        if [ -s "$work/load.summary" ]; then
            cat "$work/pvc.out"
            printf 'FINISH\n'
        fi ;;
    read)
        prepare_cdt_tool
        load_scmi_module
        report ;;
    cdt_enable)
        [ "$#" = 1 ] && [ "$1" = "$boot" ] || die "启动 ID 已改变，请刷新"
        cdt_stage prepare_backend
        prepare_cdt_tool
        load_scmi_module
        cdt_stage inspect_partition
        inspect_state
        cat "$work/cdt.status" >&2
        printf 'CDT_PRE_STATE=%s\n' "$state" >&2
        [ "$state" != CDT_RECOVERY_REQUIRED ] || die "当前 CDT 与原厂备份不一致，请先还原分区"
        if is_sm8850; then
            sm8850_cdt_ready || die "当前 CDT 布局或启动状态不满足安全写入条件"
        fi
        cdt_stage capture_plan
        plan > "$work/current" || die "读取 SCMI 计划失败，未开始写入 CDT"
        validate_current_plan
        maybe_persist_stock_baseline "$work/current"
        cdt_stage write_and_verify
        run_cdt_operation enable "$cdt_dev" ;;
    *) die "未知操作" ;;
esac
