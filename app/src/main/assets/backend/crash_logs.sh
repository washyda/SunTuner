#!/system/bin/sh
set -eu
PATH=/system/bin
export PATH
umask 077

fail() { printf 'ERROR %s\n' "$*" >&2; exit 1; }

[ "$(id -u)" = 0 ] || fail '管理设备崩溃日志需要 Root 授权'
critical=/mnt/vendor/oplusreserve/media/log/criticalLog/persist/critical.log
vendor=/data/debugging/last_kmsg.txt
tool_root=/data/adb/sm8750-tuner

collect() {
    boot=$(cat /proc/sys/kernel/random/boot_id)
    printf 'CRASH_BOOT %s\n' "$boot"

    printf 'BEGIN reason\n'
    [ ! -r /proc/last_boot_reason ] || head -c 512 /proc/last_boot_reason
    printf '\n%s\n%s\n' "$(getprop sys.boot.reason)" "$(getprop ro.boot.bootreason)"
    printf 'END reason\n'

    if [ -r "$critical" ]; then
        printf 'BEGIN critical\n'
        tail -c 524288 "$critical" | awk '
            /^<kernel-panic-begin>/ { capture=1 }
            capture { print }
            /^<kernel-panic-end>/ { capture=0 }
        '
        printf 'END critical\n'
    fi

    uptime=$(cat /proc/uptime)
    cutoff=$(($(date +%s) - ${uptime%%.*} - 10))
    collected=0
    for p in /sys/fs/pstore/dmesg-ramoops* /sys/fs/pstore/console-ramoops* /proc/last_kmsg "$vendor"; do
        if [ "$collected" -ge 8 ]; then break; fi
        [ -r "$p" ] || continue
        digest=-
        if [ "$p" = "$vendor" ]; then
            modified=$(stat -c %Y "$p" 2>/dev/null) || continue
            [ "$modified" -ge "$cutoff" ] || continue
        fi
        if [ "$p" != /proc/last_kmsg ]; then
            sum=$(sha256sum "$p" 2>/dev/null) || continue
            digest=${sum%% *}
        fi
        printf 'BEGIN kernel:%s\nCRASH_HASH %s\n' "${p##*/}" "$digest"
        tail -c 98304 "$p" 2>/dev/null || true
        printf '\nEND kernel:%s\n' "${p##*/}"
        collected=$((collected + 1))
    done
}

delete_kernel() {
    [ "$#" = 2 ] || fail '内核日志参数无效'
    name=$1
    digest=$2
    case "$name" in
        dmesg-ramoops*|console-ramoops*)
            case "$name" in *[!a-zA-Z0-9_.-]*) fail '日志名称无效' ;; esac
            path=/sys/fs/pstore/$name
            ;;
        last_kmsg.txt) path=$vendor ;;
        *) fail '此日志源不支持删除' ;;
    esac
    case "$digest" in *[!a-f0-9]*) fail '日志校验值无效' ;; esac
    [ "${#digest}" = 64 ] || fail '日志校验值无效'
    [ ! -L "$path" ] || fail '日志路径已变更，请刷新后重试'
    [ -e "$path" ] || return 0
    sum=$(sha256sum "$path") || fail "无法校验 $name"
    [ "${sum%% *}" = "$digest" ] || return 0
    unlink "$path" || fail "系统拒绝删除 $name"
    [ ! -e "$path" ] || fail "无法确认 $name 已删除"
}

delete_critical() {
    case "$#" in
        0) ;;
        2)
            wanted_date=$1; wanted_marker=$2
            [ "${#wanted_date}" = 19 ] || fail '崩溃记录时间无效'
            case "$wanted_marker" in kernel_panic*) ;; *) fail '崩溃记录标记无效' ;; esac
            ;;
        *) fail '厂商日志参数无效' ;;
    esac
    [ ! -L "$critical" ] || fail '厂商日志路径已变更'
    [ -e "$critical" ] || return 0
    [ -f "$critical" ] || fail '厂商日志不是普通文件'

    exec 9<"$critical"
    flock -n -x 0 <&9 || fail '厂商日志正在使用，请稍后重试'
    sig=$(stat -Lc '%d:%i:%s:%y:%z' "/proc/$$/fd/9") || fail '无法读取厂商日志文件状态'
    result=$(mktemp "${critical%/*}/.suntuner-crash.XXXXXX") || fail '无法准备设备日志更新'
    filter=
    trap 'unlink "$result" 2>/dev/null || true; [ -z "$filter" ] || unlink "$filter" 2>/dev/null || true' EXIT

    mkdir -p "$tool_root" || fail '无法准备 Root 日志工具目录'
    filter=$(mktemp "$tool_root/crash-log-filter.XXXXXX") || fail '无法准备日志过滤工具'
    cp "${0%/*}/crash_log_filter" "$filter" && chmod 700 "$filter" || fail '无法加载日志过滤工具'
    code=0
    "$filter" "$@" <&9 > "$result" || code=$?
    case "$code" in
        0) ;;
        3) return 0 ;;
        *) fail '无法过滤厂商日志，原文件未修改' ;;
    esac

    ownership=$(stat -c '%u:%g' "$critical")
    mode=$(stat -c '%a' "$critical")
    label=$(ls -Zd "$critical")
    chown "$ownership" "$result" && chmod "$mode" "$result" && chcon "${label%% *}" "$result" ||
        fail '无法保留厂商日志属性'
    sync || fail '写入厂商日志临时文件失败'
    if command -v lsof >/dev/null 2>&1; then
        holders=$(lsof -t "$critical" 9<&-) || fail '无法确认厂商日志是否正在使用'
        others=$(printf '%s\n' "$holders" | awk -v self="$$" 'NF && $0 != self')
        [ -z "$others" ] || fail '厂商进程正在使用日志，请稍后重试'
    fi
    [ "$(stat -c '%d:%i:%s:%y:%z' "$critical")" = "$sig" ] ||
        fail '厂商日志正在更新，请刷新后重试'
    mv -f "$result" "$critical" || fail '系统拒绝更新厂商日志'
}

action=${1:-collect}
[ "$#" = 0 ] || shift
case "$action" in
    collect) [ "$#" = 0 ] || fail '日志参数无效'; collect ;;
    delete_critical) delete_critical "$@" ;;
    delete_kernel) delete_kernel "$@" ;;
    *) fail '日志操作无效' ;;
esac
