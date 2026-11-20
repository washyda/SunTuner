#!/system/bin/sh
# Trace only in a private instance. Closing stdin stops the session.
set -u
base=/sys/kernel/tracing/instances
[ -d "$base" ] || base=/sys/kernel/debug/tracing/instances
[ -d "$base" ] || exit 1
instance="$base/suntuner-game-$$"
mkdir "$instance" || exit 1
reader=
cleanup() {
    echo 0 > "$instance/tracing_on"
    if [ -n "$reader" ]; then
        kill "$reader" 2>/dev/null || true
        wait "$reader" 2>/dev/null || true
    fi
    rmdir "$instance"
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

echo 0 > "$instance/tracing_on" || exit 1
echo mono > "$instance/trace_clock" || exit 1
echo 256 > "$instance/buffer_size_kb" || exit 1
sched_filter=
for tid in "$@"; do
    case "$tid" in ''|*[!0-9]*) exit 2 ;; esac
    sched_filter="${sched_filter:+$sched_filter || }prev_pid == $tid || next_pid == $tid"
done
sched=0
freq=0
switch="$instance/events/sched/sched_switch"
frequency="$instance/events/power/cpu_frequency"
if [ -n "$sched_filter" ] && [ -d "$switch" ] &&
    printf '%s\n' "$sched_filter" > "$switch/filter" &&
    echo 1 > "$switch/enable"; then sched=1; fi
if [ -d "$frequency" ] && echo 1 > "$frequency/enable"; then freq=1; fi
[ "$sched$freq" != 00 ] || exit 1

for policy in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do
    [ -r "$policy/related_cpus" ] || continue
    cpus=$(cat "$policy/related_cpus")
    initial=$(cat "$policy/scaling_cur_freq" 2>/dev/null) || initial=0
    printf 'POLICY %s %s %s\n' "${policy##*/}" "$initial" "$cpus"
done
echo 1 > "$instance/tracing_on" || exit 1
printf 'READY %s %s\n' "$sched" "$freq"
cat "$instance/trace_pipe" &
reader=$!
read -r stop || true
echo 0 > "$instance/tracing_on"
# Report overflow separately so zero events can be distinguished from data loss.
awk '/^(overrun|commit overrun|dropped events):/ { lost += $NF } END { print "LOST", lost + 0 }' "$instance"/per_cpu/cpu*/stats
