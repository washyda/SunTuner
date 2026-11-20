#define _GNU_SOURCE
#include <errno.h>
#include <inttypes.h>
#include <linux/perf_event.h>
#include <poll.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

enum {
    CORE_COUNT = 8,
    MAX_THREADS = 32,
    MAX_POINTS = 256,
    INT_LANES = 16,
    INT_BATCH = 2048,
    INT_OPS_PER_ITER = 16,
    INT_OPS_PER_BATCH = INT_BATCH * INT_LANES * INT_OPS_PER_ITER,
    FP_ROWS = 64,
    FP_COLUMNS = 64,
    FP_DEPTH = 128,
    FP_SUB_ITERS = 16,
    FP_PRODUCTS_PER_BATCH = FP_ROWS * FP_COLUMNS * FP_DEPTH * FP_SUB_ITERS,
    FP_FLOPS_PER_BATCH = 2 * FP_ROWS * FP_COLUMNS * FP_DEPTH * FP_SUB_ITERS,
    STEP_POLL_MS = 500,
};
enum workload_kind { WORKLOAD_INT = 0, WORKLOAD_FP = 1, WORKLOAD_MIXED = 2 };
static const uint64_t half_second = 500000000;
static const unsigned policies[] = {0, 6};

struct worker {
    pthread_t thread;
    unsigned core;
    int affinity_error;
    uint64_t cpu_ns;
    uint64_t int_ns;
    uint64_t int_batches;
    uint64_t int_cycles;
    uint64_t fp_ns;
    uint64_t fp_batches;
    uint64_t fp_cycles;
    uint64_t checksum;
    uint64_t cycles;
};

struct range {
    unsigned freq;
    uint64_t ticks;
};

struct policy_sample {
    unsigned nominal_max;
    unsigned lowest_limit;
    unsigned highest_limit;
    unsigned lowest_floor;
    unsigned highest_floor;
    unsigned current_floor;
    unsigned current_ceiling;
};

static pthread_mutex_t gate = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t changed = PTHREAD_COND_INITIALIZER;
static unsigned ready;
static atomic_bool running = true;
static atomic_int active_kind = WORKLOAD_INT;
static atomic_bool affinity_lost;
static atomic_bool fp_failed;

extern int sm8750_sgemm_kernel(long rows, long columns, long depth, float alpha,
                               const float *packed_a, const float *packed_b, float *c, long ldc);
static bool started;
static volatile sig_atomic_t interrupted;

static uint64_t ns(clockid_t clock) {
    struct timespec now;
    clock_gettime(clock, &now);
    return (uint64_t) now.tv_sec * 1000000000 + now.tv_nsec;
}

static void stop_signal(int signal_number) {
    (void) signal_number;
    interrupted = 1;
}

static int open_cycles(void) {
    struct perf_event_attr attr = {
        .type = PERF_TYPE_HARDWARE,
        .size = sizeof(attr),
        .config = PERF_COUNT_HW_CPU_CYCLES,
        .disabled = 1,
        .read_format = PERF_FORMAT_TOTAL_TIME_ENABLED | PERF_FORMAT_TOTAL_TIME_RUNNING,
    };
    return (int) syscall(__NR_perf_event_open, &attr, 0, -1, -1, PERF_FLAG_FD_CLOEXEC);
}

struct perf_sample {
    uint64_t count;
    uint64_t enabled;
    uint64_t running;
};

static bool read_perf(int fd, struct perf_sample *out) {
    return fd >= 0 && read(fd, out, sizeof(*out)) == sizeof(*out);
}

static uint64_t scale_perf_delta(uint64_t dc, uint64_t de, uint64_t dr) {
    if (!dc || !de || !dr || dr < de * 0.95) return 0;
    return (uint64_t) ((long double) dc * de / dr);
}

static void add_perf_delta(const struct perf_sample *before, const struct perf_sample *after, struct perf_sample *acc) {
    if (after->count >= before->count && after->enabled >= before->enabled && after->running >= before->running) {
        acc->count += after->count - before->count;
        acc->enabled += after->enabled - before->enabled;
        acc->running += after->running - before->running;
    }
}

static void run_int_batch(uint64_t x[restrict INT_LANES], uint64_t y[restrict INT_LANES]) {
    for (unsigned i = 0; i < INT_BATCH; ++i) {
        #pragma clang loop unroll(enable)
        for (unsigned lane = 0; lane < INT_LANES; ++lane) {
            uint64_t a = x[lane];
            uint64_t b = y[lane];
            a += b;
            b ^= a;
            a = (a >> 3) ^ (a << 61);
            b = (b << 5) | (b >> 59);
            b -= a;
            a ^= UINT64_C(0xf0f0f0f0f0f0f0f0);
            b ^= UINT64_C(0x1000100010001000);
            a *= 3;
            b *= 7;
            a += (a / 77);
            b -= (b / 3);
            a ^= (a >> 17);
            b ^= (b << 23);
            a = a * UINT64_C(6364136223846793005) + 1;
            b = b * UINT64_C(1442695040888963407) + 1;
            x[lane] = a;
            y[lane] = b;
        }
    }
}

struct fp_work {
    _Alignas(64) float a[FP_ROWS * FP_DEPTH];
    _Alignas(64) float b[FP_DEPTH * FP_COLUMNS];
    _Alignas(64) float expected[FP_ROWS * FP_COLUMNS];
    _Alignas(64) float output[FP_ROWS * FP_COLUMNS];
};

static void prepare_fp_work(struct fp_work *fp, unsigned core) {
    for (unsigned i = 0; i < FP_ROWS * FP_DEPTH; ++i) {
        fp->a[i] = (float)((i + core) % 17) / 16.0f;
    }
    for (unsigned i = 0; i < FP_DEPTH * FP_COLUMNS; ++i) {
        fp->b[i] = (float)((i + core) % 13) / 16.0f;
    }
    // The kernel packs A in 16-row panels and B in 4-column panels.
    // Binary fractions keep the products and all accumulated sums exact.
    for (unsigned col = 0; col < FP_COLUMNS; ++col) {
        for (unsigned row = 0; row < FP_ROWS; ++row) {
            float sum = 0.0f;
            for (unsigned k = 0; k < FP_DEPTH; ++k) {
                unsigned a = (row / 16 * FP_DEPTH + k) * 16 + row % 16;
                unsigned b = (col / 4 * FP_DEPTH + k) * 4 + col % 4;
                sum += fp->a[a] * fp->b[b];
            }
            fp->expected[col * FP_ROWS + row] = sum * FP_SUB_ITERS;
        }
    }
}

static bool run_fp_batch(struct fp_work *fp) {
    memset(fp->output, 0, sizeof(fp->output));
    for (int iter = 0; iter < FP_SUB_ITERS; ++iter) {
        if (sm8750_sgemm_kernel(FP_ROWS, FP_COLUMNS, FP_DEPTH, 1.0f,
                                fp->a, fp->b, fp->output, FP_ROWS) != 0) {
            return false;
        }
    }
    return memcmp(fp->output, fp->expected, sizeof(fp->output)) == 0;
}

static void *load_core(void *arg) {
    struct worker *worker = arg;
    cpu_set_t mask;
    CPU_ZERO(&mask);
    CPU_SET(worker->core, &mask);
    worker->affinity_error = sched_setaffinity(0, sizeof(mask), &mask) == -1 ? errno : 0;
    int counter = worker->affinity_error ? -1 : open_cycles();

    struct fp_work fp;
    if (!worker->affinity_error) prepare_fp_work(&fp, worker->core);

    pthread_mutex_lock(&gate);
    ++ready;
    pthread_cond_broadcast(&changed);
    while (!started) pthread_cond_wait(&changed, &gate);
    pthread_mutex_unlock(&gate);

    if (worker->affinity_error) return NULL;
    uint64_t x[INT_LANES], y[INT_LANES];
    for (unsigned lane = 0; lane < INT_LANES; ++lane) {
        x[lane] = UINT64_C(0x839587471c) + worker->core * 101U + lane * 31U;
        y[lane] = UINT64_C(0xfedcba0987654321) - worker->core * 73U - lane * 17U;
    }

    if (counter >= 0 && ioctl(counter, PERF_EVENT_IOC_ENABLE, 0) == -1) {
        close(counter);
        counter = -1;
    }
    struct perf_sample total_before = {0}, total_after = {0};
    struct perf_sample int_acc = {0}, fp_acc = {0};
    bool have_total_before = read_perf(counter, &total_before);
    uint64_t total_start = ns(CLOCK_THREAD_CPUTIME_ID);

    while (atomic_load_explicit(&running, memory_order_relaxed)) {
        int kind = atomic_load_explicit(&active_kind, memory_order_relaxed);
        struct perf_sample p0 = {0}, p1 = {0};
        bool ok_p0 = read_perf(counter, &p0);
        uint64_t t0 = ns(CLOCK_THREAD_CPUTIME_ID);
        uint64_t b = 0;
        if (kind == WORKLOAD_INT) {
            do {
                run_int_batch(x, y);
                ++b;
                if ((b & 127) == 0 && sched_getcpu() != (int) worker->core) {
                    atomic_store(&affinity_lost, true);
                    break;
                }
            } while (atomic_load_explicit(&running, memory_order_relaxed) &&
                     atomic_load_explicit(&active_kind, memory_order_relaxed) == WORKLOAD_INT);
            worker->int_ns += ns(CLOCK_THREAD_CPUTIME_ID) - t0;
            worker->int_batches += b;
            if (ok_p0 && read_perf(counter, &p1)) add_perf_delta(&p0, &p1, &int_acc);
        } else {
            do {
                if (!run_fp_batch(&fp)) {
                    atomic_store(&fp_failed, true);
                    break;
                }
                ++b;
                if ((b & 31) == 0 && sched_getcpu() != (int) worker->core) {
                    atomic_store(&affinity_lost, true);
                    break;
                }
            } while (atomic_load_explicit(&running, memory_order_relaxed) &&
                     atomic_load_explicit(&active_kind, memory_order_relaxed) == WORKLOAD_FP);
            worker->fp_ns += ns(CLOCK_THREAD_CPUTIME_ID) - t0;
            worker->fp_batches += b;
            if (ok_p0 && read_perf(counter, &p1)) add_perf_delta(&p0, &p1, &fp_acc);
        }
        if (atomic_load(&affinity_lost) || atomic_load(&fp_failed)) break;
    }

    worker->cpu_ns = ns(CLOCK_THREAD_CPUTIME_ID) - total_start;
    if (counter >= 0) {
        if (ioctl(counter, PERF_EVENT_IOC_DISABLE, 0) != -1 && have_total_before && read_perf(counter, &total_after)) {
            if (total_after.count >= total_before.count &&
                total_after.enabled >= total_before.enabled &&
                total_after.running >= total_before.running) {
                worker->cycles = scale_perf_delta(
                    total_after.count - total_before.count,
                    total_after.enabled - total_before.enabled,
                    total_after.running - total_before.running);
            }
            worker->int_cycles = scale_perf_delta(int_acc.count, int_acc.enabled, int_acc.running);
            worker->fp_cycles = scale_perf_delta(fp_acc.count, fp_acc.enabled, fp_acc.running);
        }
        close(counter);
    }
    uint64_t cs = 0;
    for (unsigned lane = 0; lane < INT_LANES; ++lane) {
        cs ^= (x[lane] ^ y[lane]);
    }
    worker->checksum = cs;
    return NULL;
}

static unsigned read_residency(unsigned policy, struct range values[MAX_POINTS]) {
    char path[160];
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpufreq/policy%u/stats/time_in_state", policy);
    FILE *file = fopen(path, "r");
    if (!file) return 0;

    unsigned count = 0;
    unsigned freq;
    uint64_t ticks;
    int fields;
    while ((fields = fscanf(file, "%u %" SCNu64, &freq, &ticks)) == 2) {
        if (!freq || count == MAX_POINTS) break;
        values[count++] = (struct range){freq, ticks};
    }
    bool valid = fields == EOF && !ferror(file);
    fclose(file);
    return valid ? count : 0;
}

static long read_arg(const char *text) {
    char *end;
    errno = 0;
    long value = strtol(text, &end, 10);
    return errno || end == text || *end ? -1 : value;
}

static unsigned read_policy(unsigned policy, const char *attribute) {
    char path[160];
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpufreq/policy%u/%s", policy, attribute);
    FILE *file = fopen(path, "r");
    if (!file) return 0;
    unsigned value = 0;
    if (fscanf(file, "%u", &value) != 1) value = 0;
    fclose(file);
    return value;
}

static void sample_policy(unsigned policy, struct policy_sample *sample) {
    unsigned floor = read_policy(policy, "scaling_min_freq");
    unsigned limit = read_policy(policy, "scaling_max_freq");
    sample->current_floor = floor;
    sample->current_ceiling = limit;
    if (floor && (!sample->lowest_floor || floor < sample->lowest_floor)) sample->lowest_floor = floor;
    if (floor > sample->highest_floor) sample->highest_floor = floor;
    if (limit && (!sample->lowest_limit || limit < sample->lowest_limit)) sample->lowest_limit = limit;
    if (limit > sample->highest_limit) sample->highest_limit = limit;
}

static int wait_until(uint64_t deadline) {
    struct pollfd input = {.fd = STDIN_FILENO, .events = POLLIN};
    while (!interrupted) {
        uint64_t current = ns(CLOCK_MONOTONIC);
        if (current >= deadline) return 0;
        int timeout = (int) ((deadline - current + 999999) / 1000000);
        int result = poll(&input, 1, timeout);
        if (result > 0) return 1;
        if (result < 0 && errno != EINTR) return -1;
    }
    return 1;
}

int main(int argc, char *argv[]) {
    if (argc != 4 && argc != 5) return 2;
    long mask = read_arg(argv[1]);
    long threads = read_arg(argv[2]);
    long seconds = read_arg(argv[3]);
    enum workload_kind workload = WORKLOAD_MIXED;
    if (argc == 5) {
        if (strcmp(argv[4], "mixed") == 0) workload = WORKLOAD_MIXED;
        else if (strcmp(argv[4], "int") == 0) workload = WORKLOAD_INT;
        else if (strcmp(argv[4], "fp") == 0) workload = WORKLOAD_FP;
        else {
            fprintf(stderr, "ERROR 算力测试类型无效\n");
            return 2;
        }
    }
    if (mask < 1 || mask > 255 || threads < 1 || threads > MAX_THREADS ||
        seconds < 5 || seconds > 3600) {
        fprintf(stderr, "ERROR 烤机参数无效\n");
        return 2;
    }
    unsigned selected[CORE_COUNT], cores = 0;
    for (unsigned core = 0; core < CORE_COUNT; ++core) {
        if (mask & (1L << core)) selected[cores++] = core;
    }
    if (threads < cores) {
        fprintf(stderr, "ERROR 每个选中的核心至少需要一个线程\n");
        return 2;
    }

    struct sigaction action = {.sa_handler = stop_signal};
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);
    sigaction(SIGHUP, &action, NULL);

    struct worker workers[MAX_THREADS] = {0};
    unsigned created = 0;
    for (; created < (unsigned) threads; ++created) {
        workers[created].core = selected[created % cores];
        if (pthread_create(&workers[created].thread, NULL, load_core, &workers[created])) break;
    }

    pthread_mutex_lock(&gate);
    while (ready < created) pthread_cond_wait(&changed, &gate);
    bool valid = created == (unsigned) threads;
    for (unsigned i = 0; i < created; ++i) {
        if (workers[i].affinity_error) {
            fprintf(stderr, "ERROR CPU%u 绑核失败，errno=%d\n", workers[i].core, workers[i].affinity_error);
            valid = false;
        }
    }
    bool use_policy[] = {(mask & 0x3f) != 0, (mask & 0xc0) != 0};
    struct range before[2][MAX_POINTS], after[2][MAX_POINTS];
    unsigned point_count[2] = {0};
    struct policy_sample samples[2] = {0};
    for (unsigned i = 0; valid && i < 2; ++i) {
        if (!use_policy[i]) continue;
        samples[i].nominal_max = read_policy(policies[i], "cpuinfo_max_freq");
        sample_policy(policies[i], &samples[i]);
        point_count[i] = read_residency(policies[i], before[i]);
        if (!point_count[i] || !samples[i].nominal_max || !samples[i].highest_limit) {
            fprintf(stderr, "ERROR policy%u 频率驻留表不可用\n", policies[i]);
            valid = false;
        }
    }
    int cur_kind = (workload == WORKLOAD_FP) ? WORKLOAD_FP : WORKLOAD_INT;
    atomic_store_explicit(&active_kind, cur_kind, memory_order_relaxed);
    if (!valid || interrupted) atomic_store(&running, false);
    uint64_t begin = ns(CLOCK_MONOTONIC);
    started = true;
    pthread_cond_broadcast(&changed);
    pthread_mutex_unlock(&gate);

    const char *pvc_ready = getenv("SM8750_PVC_READY_FILE");
    if (valid) {
        printf("RUNNING\n");
        fflush(stdout);
        if (pvc_ready && *pvc_ready) {
            FILE *rf = fopen(pvc_ready, "w");
            if (rf) fclose(rf);
        }
    }
    bool canceled = interrupted != 0;
    uint64_t int_wall_ns = 0, fp_wall_ns = 0, phase_begin = begin;
    unsigned load_steps = (unsigned) seconds * (1000 / STEP_POLL_MS);
    for (unsigned step = 1; valid && !canceled && step <= load_steps; ++step) {
        int result = wait_until(begin + step * half_second);
        uint64_t now = ns(CLOCK_MONOTONIC);
        if (cur_kind == WORKLOAD_INT) int_wall_ns += (now - phase_begin);
        else fp_wall_ns += (now - phase_begin);
        phase_begin = now;
        if (result > 0) canceled = true;
        if (result < 0) {
            valid = false;
            fprintf(stderr, "ERROR 烤机控制管道异常\n");
        }
        if (atomic_load(&affinity_lost)) {
            valid = false;
            fprintf(stderr, "ERROR 压测线程离开了指定核心\n");
        }
        if (!valid || canceled || atomic_load(&fp_failed)) break;
        if (workload == WORKLOAD_MIXED && step < load_steps) {
            cur_kind = (cur_kind == WORKLOAD_INT) ? WORKLOAD_FP : WORKLOAD_INT;
            atomic_store_explicit(&active_kind, cur_kind, memory_order_relaxed);
        }
        if ((step % (1000 / STEP_POLL_MS)) == 0) {
            for (unsigned i = 0; i < 2; ++i) {
                if (!use_policy[i]) continue;
                sample_policy(policies[i], &samples[i]);
                unsigned khz = read_policy(policies[i], "cpuinfo_cur_freq");
                printf("PROGRESS %u %u %u %u %u\n", policies[i], khz, samples[i].lowest_limit,
                       samples[i].current_floor, samples[i].current_ceiling);
            }
            fflush(stdout);
        } else {
            for (unsigned i = 0; i < 2; ++i) {
                if (use_policy[i]) sample_policy(policies[i], &samples[i]);
            }
        }
    }
    atomic_store_explicit(&running, false, memory_order_relaxed);
    if (pvc_ready && *pvc_ready) unlink(pvc_ready);
    uint64_t end_wall = ns(CLOCK_MONOTONIC);
    if (end_wall > phase_begin) {
        if (cur_kind == WORKLOAD_INT) int_wall_ns += (end_wall - phase_begin);
        else fp_wall_ns += (end_wall - phase_begin);
    }
    uint64_t elapsed = end_wall - begin;
    for (unsigned i = 0; valid && !canceled && i < 2; ++i) {
        if (use_policy[i] && read_residency(policies[i], after[i]) != point_count[i]) {
            fprintf(stderr, "ERROR policy%u 频率驻留复读失败\n", policies[i]);
            valid = false;
        }
    }
    for (unsigned i = 0; i < created; ++i) pthread_join(workers[i].thread, NULL);
    if (atomic_load(&affinity_lost)) valid = false;
    if (atomic_load(&fp_failed)) {
        fprintf(stderr, "ERROR OpenBLAS 矩阵乘法结果校验失败\n");
        valid = false;
    }
    if (!valid) {
        fprintf(stderr, "ERROR 未完成所有绑核负载和采样\n");
        return 1;
    }
    if (canceled) {
        puts("STOPPED");
        return 0;
    }

    for (unsigned i = 0; i < 2; ++i) {
        if (!use_policy[i]) continue;
        for (unsigned j = 0; j < point_count[i]; ++j) {
            if (after[i][j].freq != before[i][j].freq || after[i][j].ticks < before[i][j].ticks) {
                fprintf(stderr, "ERROR policy%u 烤机期间频率计数被重置\n", policies[i]);
                return 1;
            }
        }
    }

    const char *summary_path = getenv("SM8750_SUMMARY_FILE");
    FILE *summary_file = (summary_path && *summary_path) ? fopen(summary_path, "w") : NULL;
    FILE *streams[2] = {stdout, summary_file};
    for (int s = 0; s < 2; ++s) {
        FILE *out = streams[s];
        if (!out) continue;
        fprintf(out, "TIME %" PRIu64 " %" PRIu64 " %" PRIu64 "\n", elapsed, int_wall_ns, fp_wall_ns);
        for (unsigned i = 0; i < cores; ++i) {
            unsigned core = selected[i], count = 0;
            uint64_t busy = 0, work = 0, checksum = 0, cycles = 0;
            uint64_t int_ns = 0, int_ops = 0, int_cycles = 0;
            uint64_t fp_ns = 0, fp_ops = 0, fp_cycles = 0;
            bool have_cycles = true, have_int_cycles = (int_wall_ns > 0), have_fp_cycles = (fp_wall_ns > 0);
            for (unsigned j = i; j < created; j += cores) {
                busy += workers[j].cpu_ns;
                work += workers[j].int_batches * INT_BATCH * INT_LANES + workers[j].fp_batches * FP_PRODUCTS_PER_BATCH;
                checksum ^= workers[j].checksum;
                cycles += workers[j].cycles;
                if (!workers[j].cycles) have_cycles = false;
                int_ns += workers[j].int_ns;
                int_ops += workers[j].int_batches * INT_OPS_PER_BATCH;
                int_cycles += workers[j].int_cycles;
                if (int_wall_ns > 0 && !workers[j].int_cycles) have_int_cycles = false;
                fp_ns += workers[j].fp_ns;
                fp_ops += workers[j].fp_batches * FP_FLOPS_PER_BATCH;
                fp_cycles += workers[j].fp_cycles;
                if (fp_wall_ns > 0 && !workers[j].fp_cycles) have_fp_cycles = false;
                ++count;
            }
            fprintf(out, "CPU %u %u %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64 " %" PRIu64 "\n",
                    core, count, busy, work, have_cycles ? cycles : 0, checksum,
                    int_ns, int_ops, have_int_cycles ? int_cycles : 0,
                    fp_ns, fp_ops, have_fp_cycles ? fp_cycles : 0);
        }
        for (unsigned i = 0; i < 2; ++i) {
            if (!use_policy[i]) continue;
            fprintf(out, "LIMIT %u %u %u %u %u %u\n", policies[i], samples[i].nominal_max,
                    samples[i].lowest_limit, samples[i].highest_limit,
                    samples[i].lowest_floor, samples[i].highest_floor);
            for (unsigned j = 0; j < point_count[i]; ++j) {
                fprintf(out, "FREQ %u %u %" PRIu64 "\n", policies[i], after[i][j].freq,
                        after[i][j].ticks - before[i][j].ticks);
            }
        }
    }
    if (summary_file) fclose(summary_file);
    fflush(stdout);
    puts("DONE");
    return 0;
}
