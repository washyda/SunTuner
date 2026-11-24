#define _POSIX_C_SOURCE 200809L

#include "cdt_fdt.h"

#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>
#ifdef __linux__
#include <linux/fs.h>
#endif

enum {
    CDT_HEADER_SIZE = 14,
    CDT_MAX_PAYLOAD = 1024,
    CDT_MIN_PART_SIZE = 1024,
    CDT_MAX_PART_SIZE = 1048576,
    CDT_MAX_BLOCKS = 16,
    CDT_SLT_KEY = 7,
    CDT_PLATFORM_MTP = 8,
    CDT_PLATFORM_QRD = 11,
    XBL_CONFIG_MAX_SIZE = 64 * 1024 * 1024,
    CDB_MAX_SIZE = 10 + 2 * UINT8_MAX,
};

typedef struct {
    uint8_t first_version;
    uint8_t last_version;
    size_t header_size;
    size_t count_offset;
    const char *name;
} cdb_layout_t;

static const cdb_layout_t cdb_v6_legacy = {6, 6, 7, 6, "v6_legacy_7"};
static const cdb_layout_t cdb_layouts[] = {
    {3, 3, 6, 5, "v3_6"},
    {4, 5, 7, 6, "v4_v5_7"},
    {6, 6, 10, 9, "v6_10"},
};

static int cdb_keys_fit(const uint8_t *data, size_t length, const cdb_layout_t *layout, int exact) {
    if (length < layout->header_size) return 0;
    size_t needed = layout->header_size + 2u * data[layout->count_offset];
    return exact ? needed == length : needed <= length;
}

static const cdb_layout_t *find_cdb_layout(uint8_t version, const uint8_t *data, size_t length, int exact) {
    const cdb_layout_t *match = NULL;
    for (size_t i = 0; i < sizeof(cdb_layouts) / sizeof(*cdb_layouts); ++i) {
        const cdb_layout_t *layout = &cdb_layouts[i];
        if (version < layout->first_version || version > layout->last_version ||
            (data && !cdb_keys_fit(data, length, layout, exact))) continue;
        if (match) return NULL;
        match = layout;
    }
    return match;
}

typedef struct {
    uint16_t offset;
    uint16_t size;
} cdt_desc_t;

typedef enum {
    PLATFORM_UNKNOWN = 0,
    PLATFORM_IDENTIFIED,
    PLATFORM_NO_DEFAULT,
} platform_state_t;

typedef struct {
    platform_state_t state;
    uint8_t ver;
    uint8_t platform_id;
    uint8_t major;
    uint8_t minor;
    uint8_t subtype;
    uint8_t fusion;
    int requires_template;
    const cdb_layout_t *template_layout;
    size_t template_size;
    uint8_t template[CDB_MAX_SIZE];
} platform_hint_t;

typedef enum {
    DISK_EMPTY = 0,
    DISK_STOCK = 1,
    DISK_SLT_ENABLED = 2,
    DISK_CORRUPT = 3,
    DISK_LEGACY_V6,
} disk_state_t;

typedef struct {
    disk_state_t state;
    uint8_t version;
    uint8_t platform_id;
    uint8_t major;
    uint8_t minor;
    uint8_t subtype;
    uint8_t fusion;
    const cdb_layout_t *layout;
    uint8_t key_count;
    uint8_t keys[255];
    uint8_t vals[255];
    int has_key7;
    uint8_t key7_val;
    int block_count;
    cdt_desc_t descs[CDT_MAX_BLOCKS];
} cdt_parsed_t;

static uint16_t read_u16_le(const uint8_t *p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

static void write_u16_le(uint8_t *p, uint16_t v) {
    p[0] = (uint8_t)(v & 0xffu);
    p[1] = (uint8_t)((v >> 8) & 0xffu);
}

static int read_entire_fd(int fd, uint8_t **out_buf, size_t *out_len, size_t max_len) {
    off_t end = lseek(fd, 0, SEEK_END);
    if (end < 0) return -1;
    if (end < CDT_MIN_PART_SIZE || (uintmax_t)end > max_len) {
        errno = EINVAL;
        return -1;
    }
    if (lseek(fd, 0, SEEK_SET) < 0) return -1;
    size_t size = (size_t)end;
    uint8_t *buf = malloc(size);
    if (!buf) return -1;
    size_t done = 0;
    while (done < size) {
        ssize_t n = read(fd, buf + done, size - done);
        if (n < 0) {
            if (errno == EINTR) continue;
            free(buf);
            return -1;
        }
        if (n == 0) {
            free(buf);
            errno = EIO;
            return -1;
        }
        done += (size_t)n;
    }
    *out_buf = buf;
    *out_len = size;
    return 0;
}

static int read_file(const char *path, uint8_t **out_buf, size_t *out_len, size_t max_len) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    int rc = read_entire_fd(fd, out_buf, out_len, max_len);
    int saved = errno;
    close(fd);
    errno = saved;
    return rc;
}

static int read_file_exact(const char *path, uint8_t **out_buf, size_t *out_len) {
    return read_file(path, out_buf, out_len, CDT_MAX_PART_SIZE);
}

static int sync_fd(int fd) {
    int rc;
    do { rc = fsync(fd); } while (rc < 0 && errno == EINTR);
    return rc;
}

static int write_all_fd(int fd, const uint8_t *buf, size_t len) {
    size_t done = 0;
    while (done < len) {
        ssize_t n = write(fd, buf + done, len - done);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) {
            int saved = n == 0 ? EIO : errno;
            fprintf(stderr, "CDT_STEP=write_failed bytes=%zu/%zu errno=%d (%s)\n",
                    done, len, saved, strerror(saved));
            errno = saved;
            return -1;
        }
        done += (size_t)n;
    }
    int rc = sync_fd(fd);
    if (rc != 0) {
        int saved = errno;
        fprintf(stderr, "CDT_STEP=fsync_failed bytes=%zu errno=%d (%s)\n",
                len, saved, strerror(saved));
        errno = saved;
    }
    return rc;
}

static void invalidate_block_cache(int fd) {
#ifdef BLKFLSBUF
    struct stat st;
    if (fstat(fd, &st) == 0 && S_ISBLK(st.st_mode)) {
        (void)ioctl(fd, BLKFLSBUF);
    }
#else
    (void)fd;
#endif
}

static int write_block(const char *path, const uint8_t *buf, size_t len, int *attempted) {
    *attempted = 0;
    int fd = open(path, O_RDWR);
    if (fd < 0) return -1;
    off_t end = lseek(fd, 0, SEEK_END);
    if (end < 0 || (size_t)end != len || lseek(fd, 0, SEEK_SET) < 0) {
        close(fd);
        errno = EINVAL;
        return -1;
    }
    *attempted = 1;
    if (write_all_fd(fd, buf, len) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
    invalidate_block_cache(fd);
    return close(fd);
}

/* A fresh open and a full comparison distinguish a committed image from a
 * partial write. Retry read errors and mismatches before deciding to roll back. */
static int image_matches(const char *path, const uint8_t *buf, size_t len) {
    for (int i = 0; i < 3; i++) {
        uint8_t *verify = NULL;
        size_t verify_len = 0;
        if (read_file_exact(path, &verify, &verify_len) == 0) {
            int match = verify_len == len && memcmp(buf, verify, len) == 0;
            free(verify);
            if (match) return 1;
            errno = EIO;
        }
    }
    return 0;
}

static int sync_block(const char *path) {
    int fd = open(path, O_RDWR);
    if (fd < 0) return -1;
    int rc = sync_fd(fd);
    int saved = errno;
    if (rc == 0) {
        invalidate_block_cache(fd);
        return close(fd);
    }
    close(fd);
    errno = saved;
    return -1;
}

static int save_backup_atomic(const char *backup_path, const uint8_t *buf, size_t len) {
    char tmp_path[1024];
    int n = snprintf(tmp_path, sizeof(tmp_path), "%s.tmp", backup_path);
    if (n < 0 || (size_t)n >= sizeof(tmp_path)) {
        errno = ENAMETOOLONG;
        return -1;
    }
    int fd = open(tmp_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) return -1;
    if (write_all_fd(fd, buf, len) != 0) {
        int saved = errno;
        close(fd);
        unlink(tmp_path);
        errno = saved;
        return -1;
    }
    if (close(fd) != 0) {
        int saved = errno;
        unlink(tmp_path);
        errno = saved;
        return -1;
    }
    if (rename(tmp_path, backup_path) != 0) {
        int saved = errno;
        unlink(tmp_path);
        errno = saved;
        return -1;
    }
    return 0;
}

static int transaction_path(const char *backup_path, char *path, size_t size) {
    int n = snprintf(path, size, "%s.transaction", backup_path);
    if (n < 0 || (size_t)n >= size) {
        errno = ENAMETOOLONG;
        return -1;
    }
    return 0;
}

static int sync_parent(const char *path) {
    char dir[1024];
    const char *slash = strrchr(path, '/');
    size_t len = slash ? (size_t)(slash - path) : 0;
    if (len == 0) {
        strcpy(dir, slash ? "/" : ".");
    } else {
        memcpy(dir, path, len);
        dir[len] = '\0';
    }
    int fd = open(dir, O_RDONLY | O_DIRECTORY);
    if (fd < 0) return -1;
    int rc = sync_fd(fd);
    int saved = errno;
    close(fd);
    errno = saved;
    return rc;
}

static void finish_transaction(const char *journal) {
    if (unlink(journal) != 0 || sync_parent(journal) != 0)
        fprintf(stderr, "CDT_INFO=journal_cleanup_failed: %s\n", strerror(errno));
}

static const char *operation_result = "unchanged";

static int restore_previous(const char *device, const uint8_t *previous, size_t len) {
    if (image_matches(device, previous, len) && sync_block(device) == 0 &&
        image_matches(device, previous, len)) return 0;
    for (int i = 0; i < 3; i++) {
        fprintf(stderr, "CDT_STEP=rollback_write attempt=%d\n", i + 1);
        int attempted;
        int rc = write_block(device, previous, len, &attempted);
        if ((rc == 0 || sync_block(device) == 0) && image_matches(device, previous, len))
            return 0;
    }
    return -1;
}

static int recover_transaction(const char *device, const char *backup_path) {
    char journal[1024];
    uint8_t *record = NULL, *current = NULL;
    size_t size = 0, len = 0;
    if (transaction_path(backup_path, journal, sizeof(journal)) != 0) goto failed;
    if (read_file(journal, &record, &size, 2 * CDT_MAX_PART_SIZE) != 0) {
        if (errno == ENOENT) return 0;
        goto failed;
    }
    if (read_file_exact(device, &current, &len) != 0) goto failed;
    if (size != 2 * len) { errno = EINVAL; goto failed; }

    // A completed write can outlive the process that was supposed to report it.
    if (memcmp(current, record + len, len) == 0) {
        if (sync_block(device) == 0 && image_matches(device, record + len, len)) {
            printf("CDT_RECOVERY=target_verified\n");
            goto complete;
        }
    } else {
        // Do not overwrite an unrelated image installed outside the app.
        for (size_t i = 0; i < len; i++) {
            if (current[i] != record[i] && current[i] != record[len + i]) {
                errno = EINVAL;
                goto failed;
            }
        }
    }
    if (restore_previous(device, record, len) != 0) goto failed;
    printf("CDT_RECOVERY=previous_verified\n");
complete:
    finish_transaction(journal);
    free(current);
    free(record);
    return 0;
failed:
    operation_result = "unknown";
    fprintf(stderr, "CDT_ERR=recovery_failed: %s\n", strerror(errno));
    free(current);
    free(record);
    return -1;
}

static int replace_cdt(const char *device, const char *backup_path,
                       const uint8_t *previous, const uint8_t *target, size_t len) {
    fprintf(stderr, "CDT_STEP=prepare_journal image_bytes=%zu\n", len);
    char journal[1024];
    if (transaction_path(backup_path, journal, sizeof(journal)) != 0) return -1;
    uint8_t *record = malloc(2 * len);
    if (!record) return -1;
    memcpy(record, previous, len);
    memcpy(record + len, target, len);
    int rc = save_backup_atomic(journal, record, 2 * len);
    free(record);
    if (rc != 0 || sync_parent(journal) != 0) return -1;

    int attempted;
    fprintf(stderr, "CDT_STEP=write_target\n");
    rc = write_block(device, target, len, &attempted);
    int write_error = rc == 0 ? EIO : errno;
    if (rc != 0) {
        fprintf(stderr, "CDT_STEP=target_write_error attempted=%d errno=%d (%s)\n",
                attempted, write_error, strerror(write_error));
    }
    if (attempted) fprintf(stderr, "CDT_STEP=verify_target\n");
    if (attempted && image_matches(device, target, len) &&
        (rc == 0 || (sync_block(device) == 0 && image_matches(device, target, len)))) {
        finish_transaction(journal);
        fprintf(stderr, "CDT_STEP=target_verified\n");
        if (rc != 0) fprintf(stderr, "CDT_INFO=commit_reconciled\n");
        return 0;
    }
    if (attempted) fprintf(stderr, "CDT_STEP=verify_target_failed; checking rollback\n");
    if (!attempted || restore_previous(device, previous, len) == 0) {
        finish_transaction(journal);
        operation_result = attempted ? "rolled_back" : "unchanged";
        fprintf(stderr, "CDT_ERR=%s\n", operation_result);
    } else {
        operation_result = "unknown";
        fprintf(stderr, "CDT_ERR=rollback_failed: %s\n", strerror(errno));
    }
    errno = write_error;
    return -1;
}

static int parse_cdt_layout(const uint8_t *buf, size_t len, cdt_parsed_t *out, int legacy_v6) {
    memset(out, 0, sizeof(*out));
    int all_zero = 1;
    for (size_t i = 0; i < len; i++) {
        if (buf[i] != 0) {
            all_zero = 0;
            break;
        }
    }
    if (all_zero) {
        out->state = DISK_EMPTY;
        return 0;
    }

    if (len < CDT_HEADER_SIZE + 4 || memcmp(buf, "CDT\0", 4) != 0) {
        out->state = DISK_CORRUPT;
        return -1;
    }

    uint16_t off0 = read_u16_le(buf + CDT_HEADER_SIZE);
    uint16_t size0 = read_u16_le(buf + CDT_HEADER_SIZE + 2);
    if (off0 < CDT_HEADER_SIZE + 4 || off0 >= CDT_MAX_PAYLOAD || size0 == 0 ||
        (size_t)off0 + (size_t)size0 > CDT_MAX_PAYLOAD || (size_t)off0 + (size_t)size0 > len ||
        ((off0 - CDT_HEADER_SIZE) % 4) != 0) {
        out->state = DISK_CORRUPT;
        return -1;
    }

    int slots = (off0 - CDT_HEADER_SIZE) / 4;
    if (slots <= 0 || slots > CDT_MAX_BLOCKS) {
        out->state = DISK_CORRUPT;
        return -1;
    }
    out->block_count = slots;
    for (int i = 0; i < slots; i++) {
        uint16_t off = read_u16_le(buf + CDT_HEADER_SIZE + i * 4);
        uint16_t sz = read_u16_le(buf + CDT_HEADER_SIZE + i * 4 + 2);
        out->descs[i].offset = off;
        out->descs[i].size = sz;
        if (sz > 0) {
            if (off < off0 || (size_t)off + (size_t)sz > CDT_MAX_PAYLOAD || (size_t)off + (size_t)sz > len) {
                out->state = DISK_CORRUPT;
                return -1;
            }
        }
    }
    const uint8_t *p = buf + off0;
    const cdb_layout_t *layout = legacy_v6
        ? (p[0] == 6 && cdb_keys_fit(p, size0, &cdb_v6_legacy, 0) ? &cdb_v6_legacy : NULL)
        : find_cdb_layout(p[0], p, size0, p[0] >= 6);
    if (!layout) {
        out->state = DISK_CORRUPT;
        return -1;
    }
    out->version = p[0];
    out->layout = layout;
    out->platform_id = p[1];
    out->major = p[2];
    out->minor = p[3];
    out->subtype = p[4];

    out->fusion = (out->version == 5 || legacy_v6) ? p[5] : 0;
    out->key_count = p[layout->count_offset];
    for (uint8_t i = 0; i < out->key_count; i++) {
        uint8_t k = p[layout->header_size + i * 2u];
        uint8_t v = p[layout->header_size + i * 2u + 1u];
        out->keys[i] = k;
        out->vals[i] = v;
        if (k == CDT_SLT_KEY) {
            out->has_key7 = 1;
            out->key7_val = v;
        }
    }
    out->state = (out->has_key7 && out->key7_val != 0) ? DISK_SLT_ENABLED : DISK_STOCK;
    return 0;
}

static int parse_cdt(const uint8_t *buf, size_t len, cdt_parsed_t *out) {
    return parse_cdt_layout(buf, len, out, 0);
}

static int read_small_text(const char *path, char *buf, size_t cap) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    size_t total = 0;
    while (total + 1 < cap) {
        ssize_t n = read(fd, buf + total, cap - 1 - total);
        if (n < 0) {
            if (errno == EINTR) continue;
            close(fd);
            return -1;
        }
        if (n == 0) break;
        total += (size_t)n;
    }
    close(fd);
    buf[total] = '\0';
    return 0;
}

static void trim_ws(char *s) {
    size_t len = strlen(s);
    while (len > 0 && isspace((unsigned char)s[len - 1])) s[--len] = '\0';
}

static int read_platform_value(const char *path, unsigned long valid_bits, unsigned long *value) {
    char buf[64];
    if (read_small_text(path, buf, sizeof(buf)) != 0) return 0;
    trim_ws(buf);
    if (!isdigit((unsigned char)buf[0])) return 0;
    errno = 0;
    char *end;
    unsigned long parsed = strtoul(buf, &end, 0);
    if (errno || *end != '\0' || (parsed & ~valid_bits) != 0) return 0;
    *value = parsed;
    return 1;
}

static uint8_t default_cdt_version(int *requires_template) {
    *requires_template = 0;
#ifdef __ANDROID__
    char soc[PROP_VALUE_MAX];
    if (__system_property_get("ro.soc.model", soc) > 0) {
        if (strcasecmp(soc, "SM8750") == 0) return 5;
        if (strcasecmp(soc, "SM8845") == 0) {
            *requires_template = 1;
            return 5;
        }
        if (strcasecmp(soc, "SM8850") == 0 || strcasecmp(soc, "SM8850P") == 0) return 6;
    }
#endif
    return 0;
}

typedef struct {
    platform_hint_t *hint;
    int unsupported;
    int ambiguous;
} platform_match_t;

static void match_platform_info(const uint8_t *data, size_t length, void *context) {
    platform_match_t *match = context;
    platform_hint_t *hint = match->hint;
    const uint8_t identity[] = {hint->ver, hint->platform_id, hint->major, hint->minor, hint->subtype};
    if (length < sizeof(identity) || memcmp(data, identity, sizeof(identity)) != 0) return;
    const cdb_layout_t *layout = find_cdb_layout(data[0], data, length, 1);
    if (!layout || length > sizeof(hint->template)) {
        match->unsupported = 1;
        return;
    }
    if (hint->template_size != 0) {
        if (hint->template_size != length || memcmp(hint->template, data, length) != 0) {
            match->ambiguous = 1;
        }
        return;
    }
    memcpy(hint->template, data, length);
    hint->template_size = length;
    hint->template_layout = layout;
}

static const char *read_firmware_template(platform_hint_t *hint) {
    const char *path = getenv("SUNTUNER_XBL_CONFIG");
    uint8_t *image;
    size_t size;
    hint->template_size = 0;
    hint->template_layout = NULL;
    if (!path || !*path || read_file(path, &image, &size, XBL_CONFIG_MAX_SIZE) != 0) return "unavailable";
    platform_match_t match = {.hint = hint};
    cdt_visit_platform_info(image, size, match_platform_info, &match);
    free(image);
    if (match.ambiguous || (match.unsupported && hint->template_size != 0)) {
        hint->template_size = 0;
        hint->template_layout = NULL;
        return "ambiguous";
    }
    if (match.unsupported) return "unsupported";
    return hint->template_size != 0 ? "confirmed" : "no_match";
}

static platform_hint_t platform_from_cdt(const cdt_parsed_t *cdt) {
    return (platform_hint_t){
        .state = PLATFORM_IDENTIFIED, .ver = cdt->version, .platform_id = cdt->platform_id,
        .major = cdt->major, .minor = cdt->minor, .subtype = cdt->subtype, .fusion = cdt->fusion,
    };
}

static const cdb_layout_t *resolve_empty_cdt_hint(platform_hint_t *hint, const char **check) {
    int requires_template;
    uint8_t default_version = default_cdt_version(&requires_template);
    /* Preserve the established SM8750 default when neither the boot log nor sysfs identify a board. */
    if (hint->state == PLATFORM_UNKNOWN && default_version == 5 && !requires_template) {
        *hint = (platform_hint_t){
            .state = PLATFORM_IDENTIFIED, .ver = default_version, .platform_id = CDT_PLATFORM_MTP, .major = 1,
        };
    }
    if (hint->state != PLATFORM_IDENTIFIED) {
        *check = "platform_unknown";
        return NULL;
    }
    if (hint->ver >= 6 || hint->requires_template) {
        *check = read_firmware_template(hint);
        return hint->template_layout;
    }
    const cdb_layout_t *layout = default_version != 6 && hint->ver >= 4
        ? find_cdb_layout(hint->ver, NULL, 0, 0) : NULL;
    *check = layout ? "confirmed" : "unsupported";
    return layout;
}

static const char *detect_boot_cdt_state(platform_hint_t *hint) {
    static char boot_buf[262144];
    memset(hint, 0, sizeof(*hint));
    int requires_template;
    uint8_t default_version = default_cdt_version(&requires_template);
    int saw_version = 0;
    int saw_slt_not_found = 0;
    int saw_cdt_not_found = 0;
    int saw_mtp = 0;

    if (read_small_text("/proc/bootloader_log", boot_buf, sizeof(boot_buf)) == 0) {
        const char *ver_hit = strstr(boot_buf, "CDT Version:");
        if (ver_hit) {
            unsigned v = 0, p = 0, maj = 0, min = 0, sub = 0;
            if (sscanf(ver_hit, "CDT Version:%u,Platform ID:%u,Major ID:%u,Minor ID:%u,Subtype:%u",
                       &v, &p, &maj, &min, &sub) == 5 &&
                v <= 255 && p <= 255 && maj <= 255 && min <= 255 && sub <= 255) {
                saw_version = 1;
                hint->state = PLATFORM_IDENTIFIED;
                hint->ver = (uint8_t)v;
                hint->platform_id = (uint8_t)p;
                hint->major = (uint8_t)maj;
                hint->minor = (uint8_t)min;
                hint->subtype = (uint8_t)sub;
                hint->fusion = 0;
            }
        }
        if (strstr(boot_buf, "SLT Platform Key not found:") != NULL) saw_slt_not_found = 1;
        if (strstr(boot_buf, "CDT not found in any storage media") != NULL) saw_cdt_not_found = 1;
        const char *plat = strstr(boot_buf, "Platform");
        while (plat) {
            const char *colon = strchr(plat, ':');
            const char *eol = strpbrk(plat, "\r\n");
            if (colon && (!eol || colon < eol)) {
                colon++;
                while (*colon == ' ' || *colon == '\t') colon++;
                if (strncmp(colon, "MTP", 3) == 0) {
                    saw_mtp = 1;
                    break;
                }
            }
            plat = strstr(plat + 8, "Platform");
        }
    }

    if (hint->state == PLATFORM_UNKNOWN) {
        char hw[64] = {0};
        if (read_small_text("/sys/devices/soc0/hw_platform", hw, sizeof(hw)) == 0) trim_ws(hw);
        int hw_unknown = hw[0] == '\0' || strcasecmp(hw, "Unknown") == 0;
        uint8_t platform_id = 0;
        if (strcasecmp(hw, "MTP") == 0 || (hw_unknown && saw_mtp)) platform_id = CDT_PLATFORM_MTP;
        if (strcasecmp(hw, "QRD") == 0) platform_id = CDT_PLATFORM_QRD;
        if (platform_id) {
            unsigned long ver_num = 0x10000UL;
            unsigned long sub_num = 0UL;
            int has_version = read_platform_value("/sys/devices/soc0/platform_version", 0x00ff00ffUL, &ver_num);
            int has_subtype = read_platform_value("/sys/devices/soc0/platform_subtype_id", UINT8_MAX, &sub_num);
            if ((platform_id == CDT_PLATFORM_QRD || requires_template) &&
                (!default_version || !has_version || !has_subtype)) {
                hint->state = PLATFORM_NO_DEFAULT;
            } else {
                *hint = (platform_hint_t){
                    .state = PLATFORM_IDENTIFIED,
                    .ver = default_version ? default_version : 5,
                    .platform_id = platform_id,
                    .major = (uint8_t)(ver_num >> 16),
                    .minor = (uint8_t)ver_num,
                    .subtype = (uint8_t)sub_num,
                };
            }
        } else if (!hw_unknown) {
            hint->state = PLATFORM_NO_DEFAULT;
        }
    }

    hint->requires_template = requires_template;
    if (saw_slt_not_found || saw_cdt_not_found) return "not_found";
    if (saw_version) return "applied";
    return "unknown";
}

/* Shift later blocks only when matching images written by earlier builds. */
static int edit_cdb_key7(const uint8_t *orig, size_t part_len, const cdt_parsed_t *parsed,
                        uint8_t value, uint8_t *img, int shift_following_blocks) {
    const size_t header = parsed->descs[0].offset;
    const size_t key_start = header + parsed->layout->header_size;
    const size_t growth = parsed->has_key7 ? 0 : 2;
    if (growth && parsed->key_count == UINT8_MAX) {
        errno = EOVERFLOW;
        return -1;
    }
    const size_t insert = key_start + (size_t)parsed->key_count * 2;
    size_t end = header + parsed->descs[0].size;
    for (int i = 1; i < parsed->block_count; i++) {
        const cdt_desc_t *block = &parsed->descs[i];
        if (!block->size) continue;
        const size_t block_end = (size_t)block->offset + block->size;
        if (!shift_following_blocks) {
            if (block->offset < end + growth && block_end > header) {
                errno = ENOSPC;
                return -1;
            }
            continue;
        }
        for (int j = 0; j < i; j++) {
            const cdt_desc_t *previous = &parsed->descs[j];
            if (previous->size && block->offset < (size_t)previous->offset + previous->size &&
                previous->offset < block_end) {
                errno = EINVAL;
                return -1;
            }
        }
        if (block_end > end) end = block_end;
    }
    if (growth && (end + 2 > CDT_MAX_PAYLOAD || end + 2 > part_len || orig[end] || orig[end + 1])) {
        errno = ENOSPC;
        return -1;
    }
    if (!img) return 0;
    memcpy(img, orig, part_len);
    if (parsed->has_key7) {
        for (size_t i = 0; i < parsed->key_count; i++) {
            if (parsed->keys[i] == CDT_SLT_KEY) img[key_start + i * 2 + 1] = value;
        }
        return 0;
    }
    memmove(img + insert + 2, img + insert, end - insert);
    img[header + parsed->layout->count_offset] = parsed->key_count + 1;
    img[insert] = CDT_SLT_KEY;
    img[insert + 1] = value;
    write_u16_le(img + CDT_HEADER_SIZE + 2, parsed->descs[0].size + 2);
    for (int i = 1; shift_following_blocks && i < parsed->block_count; i++) {
        if (parsed->descs[i].size) {
            write_u16_le(img + CDT_HEADER_SIZE + i * 4, parsed->descs[i].offset + 2);
        }
    }
    return 0;
}

static int build_empty_cdt(uint8_t *image, const platform_hint_t *hint,
                           const cdb_layout_t *layout, uint8_t value) {
    const size_t offset = CDT_HEADER_SIZE + 4;
    uint8_t *cdb = image + offset;
    if (hint->template_size) {
        memcpy(cdb, hint->template, hint->template_size);
    } else {
        cdb[0] = hint->ver;
        cdb[1] = hint->platform_id;
        cdb[2] = hint->major;
        cdb[3] = hint->minor;
        cdb[4] = hint->subtype;
        if (hint->ver == 5 || layout == &cdb_v6_legacy) cdb[5] = hint->fusion;
    }
    size_t count = cdb[layout->count_offset];
    uint8_t *keys = cdb + layout->header_size;
    int found = 0;
    for (size_t i = 0; i < count; ++i) {
        if (keys[2 * i] != CDT_SLT_KEY) continue;
        keys[2 * i + 1] = value;
        found = 1;
    }
    if (!found) {
        if (count == UINT8_MAX) {
            errno = ENOSPC;
            return -1;
        }
        keys[2 * count] = CDT_SLT_KEY;
        keys[2 * count + 1] = value;
        cdb[layout->count_offset] = (uint8_t)++count;
    }
    memcpy(image, "CDT\0", 4);
    write_u16_le(image + CDT_HEADER_SIZE, offset);
    write_u16_le(image + CDT_HEADER_SIZE + 2, (uint16_t)(layout->header_size + 2 * count));
    return 0;
}

static int build_cdt_with_key7(
    const uint8_t *orig,
    size_t part_len,
    const cdt_parsed_t *parsed,
    const platform_hint_t *hint,
    int set_key7,
    uint8_t key7_value,
    uint8_t **out_img
) {
    uint8_t *img = calloc(1, part_len);
    if (!img) return -1;

    if (parsed->state == DISK_EMPTY) {
        if (!set_key7) {
            *out_img = img;
            return 0;
        }
        const cdb_layout_t *layout = hint->template_layout;
        if (!layout && hint->ver < 6 && !hint->requires_template) {
            layout = find_cdb_layout(hint->ver, NULL, 0, 0);
        }
        if (hint->state != PLATFORM_IDENTIFIED || !layout) {
            free(img);
            errno = ENODEV;
            return -1;
        }
        if (build_empty_cdt(img, hint, layout, key7_value) != 0) {
            free(img);
            return -1;
        }
        *out_img = img;
        return 0;
    }

    if (set_key7 && parsed->version >= 6) {
        if (edit_cdb_key7(orig, part_len, parsed, key7_value, img, 0) != 0) {
            free(img);
            return -1;
        }
        *out_img = img;
        return 0;
    }

    uint8_t new_keys[255];
    uint8_t new_vals[255];
    uint8_t new_count = 0;
    int replaced = 0;
    for (uint8_t i = 0; i < parsed->key_count; i++) {
        if (parsed->keys[i] == CDT_SLT_KEY) {
            if (set_key7) {
                new_keys[new_count] = CDT_SLT_KEY;
                new_vals[new_count] = key7_value;
                new_count++;
                replaced = 1;
            }
        } else {
            new_keys[new_count] = parsed->keys[i];
            new_vals[new_count] = parsed->vals[i];
            new_count++;
        }
    }
    if (set_key7 && !replaced) {
        if (new_count == 255) {
            free(img);
            errno = EOVERFLOW;
            return -1;
        }
        new_keys[new_count] = CDT_SLT_KEY;
        new_vals[new_count] = key7_value;
        new_count++;
    }

    int only_block0_active = 1;
    for (int i = 1; i < parsed->block_count; i++) {
        if (parsed->descs[i].size > 0) {
            only_block0_active = 0;
            break;
        }
    }
    if (!set_key7 && new_count == 0 && only_block0_active &&
        parsed->version == 5 && parsed->fusion == 0 && hint->state == PLATFORM_IDENTIFIED &&
        parsed->platform_id == hint->platform_id && parsed->major == hint->major &&
        parsed->minor == hint->minor && parsed->subtype == hint->subtype) {
        /* Fallback clean stock when partition originally held only our injected Key 7 record. */
        *out_img = img;
        return 0;
    }

    size_t hdr_len = parsed->layout->header_size;
    size_t new_b0_size = hdr_len + (size_t)new_count * 2u;
    size_t desc_end = CDT_HEADER_SIZE + (size_t)parsed->block_count * 4u;

    /* Copy the 14-byte CDT header cleanly while re-packing CDB payloads without gaps. */
    memcpy(img, orig, CDT_HEADER_SIZE);
    size_t cursor = desc_end;
    if (cursor + new_b0_size > CDT_MAX_PAYLOAD || new_b0_size > UINT16_MAX) {
        free(img);
        errno = ENOSPC;
        return -1;
    }

    write_u16_le(img + CDT_HEADER_SIZE, (uint16_t)cursor);
    write_u16_le(img + CDT_HEADER_SIZE + 2, (uint16_t)new_b0_size);
    memcpy(img + cursor, orig + parsed->descs[0].offset, hdr_len);
    img[cursor + parsed->layout->count_offset] = new_count;
    for (uint8_t i = 0; i < new_count; i++) {
        img[cursor + hdr_len + i * 2u] = new_keys[i];
        img[cursor + hdr_len + i * 2u + 1u] = new_vals[i];
    }
    cursor += new_b0_size;

    for (int i = 1; i < parsed->block_count; i++) {
        uint16_t old_off = parsed->descs[i].offset;
        uint16_t old_sz = parsed->descs[i].size;
        if (old_sz == 0) continue;
        if (cursor + old_sz > CDT_MAX_PAYLOAD) {
            free(img);
            errno = ENOSPC;
            return -1;
        }
        write_u16_le(img + CDT_HEADER_SIZE + i * 4, (uint16_t)cursor);
        write_u16_le(img + CDT_HEADER_SIZE + i * 4 + 2, old_sz);
        memcpy(img + cursor, orig + old_off, old_sz);
        cursor += old_sz;
    }

    *out_img = img;
    return 0;
}

static const char *disk_state_str(disk_state_t s) {
    switch (s) {
        case DISK_EMPTY: return "empty";
        case DISK_STOCK: return "stock";
        case DISK_SLT_ENABLED: return "slt_enabled";
        case DISK_LEGACY_V6: return "legacy_v6";
        default: return "corrupt";
    }
}

static int is_legacy_enabled(const uint8_t *data, size_t len, cdt_parsed_t *cdt) {
    return parse_cdt_layout(data, len, cdt, 1) == 0 && cdt->layout == &cdb_v6_legacy &&
           cdt->state == DISK_SLT_ENABLED && cdt->key7_val == 1;
}

static int matches_legacy_backup(const uint8_t *backup, const uint8_t *current, size_t len,
                                 int empty_backup) {
    cdt_parsed_t active;
    if (!is_legacy_enabled(current, len, &active)) return 0;
    uint8_t *expected = calloc(1, len);
    if (!expected) return 0;
    int match = 0;
    if (empty_backup) {
        platform_hint_t hint = platform_from_cdt(&active);
        match = build_empty_cdt(expected, &hint, &cdb_v6_legacy, 1) == 0 &&
                memcmp(current, expected, len) == 0;
    } else {
        cdt_parsed_t original;
        if (parse_cdt_layout(backup, len, &original, 1) == 0 && original.state == DISK_STOCK) {
            match = edit_cdb_key7(backup, len, &original, 1, expected, 0) == 0 &&
                    memcmp(current, expected, len) == 0;
            if (!match && !original.has_key7) {
                match = edit_cdb_key7(backup, len, &original, 1, expected, 1) == 0 &&
                        memcmp(current, expected, len) == 0;
            }
        }
    }
    free(expected);
    return match;
}

static const char *compare_backup(const uint8_t *backup, size_t backup_len,
                                  const uint8_t *current, size_t len) {
    if (backup_len != len) return "invalid";
    cdt_parsed_t parsed;
    int stock = parse_cdt(backup, len, &parsed) == 0 &&
                (parsed.state == DISK_EMPTY || parsed.state == DISK_STOCK);
    if (stock) {
        if (memcmp(current, backup, len) == 0) return "match";
        cdt_parsed_t active;
        if (parse_cdt(current, len, &active) == 0 &&
            active.state == DISK_SLT_ENABLED && active.key7_val == 1) {
            platform_hint_t hint = platform_from_cdt(&active);
            if (parsed.state == DISK_EMPTY && (active.version >= 6 || active.key_count > 1)) {
                read_firmware_template(&hint);
                if (hint.template_layout != active.layout && active.key_count == 1) {
                    /* A canonical single-key image can still match an empty backup after an OTA. */
                    hint.template_layout = active.layout;
                    hint.template_size = active.layout->header_size;
                    memcpy(hint.template, current + active.descs[0].offset, hint.template_size);
                    hint.template[active.layout->count_offset] = 0;
                }
            }
            uint8_t *expected = NULL;
            if (build_cdt_with_key7(backup, len, &parsed, &hint, 1, 1, &expected) == 0) {
                int matches = memcmp(current, expected, len) == 0;
                free(expected);
                if (matches) return "enabled_match";
            }
        }
    }
    if (matches_legacy_backup(backup, current, len, stock && parsed.state == DISK_EMPTY)) {
        return "legacy_enabled_match";
    }
    return stock ? "mismatch" : "invalid";
}

static const char *backup_status(const char *path, const uint8_t *current, size_t len) {
    uint8_t *backup = NULL;
    size_t backup_len = 0;
    if (!path) return "missing";
    if (read_file_exact(path, &backup, &backup_len) != 0)
        return errno == ENOENT ? "missing" : "invalid";
    const char *status = compare_backup(backup, backup_len, current, len);
    free(backup);
    return status;
}

static disk_state_t print_disk_status(const uint8_t *buf, size_t len, const char *original_status) {
    cdt_parsed_t parsed;
    int valid = parse_cdt(buf, len, &parsed) == 0;
    cdt_parsed_t legacy;
    if (strcmp(original_status, "legacy_enabled_match") == 0 ||
        (!valid && is_legacy_enabled(buf, len, &legacy))) parsed.state = DISK_LEGACY_V6;
    printf("CDT_SIZE=%zu\n", len);
    printf("CDT_DISK=%s\n", disk_state_str(parsed.state));
    printf("CDT_BACKUP_STATUS=%s\n", original_status);
    if (parsed.has_key7 && parsed.state != DISK_LEGACY_V6) {
        printf("CDT_KEY7=%u\n", parsed.key7_val);
    } else {
        printf("CDT_KEY7=absent\n");
    }
    if (parsed.state == DISK_STOCK || parsed.state == DISK_SLT_ENABLED) {
        printf("CDT_PLATFORM=%u,%u,%u,%u,%u,%u\n",
               parsed.version, parsed.platform_id, parsed.major,
               parsed.minor, parsed.subtype, parsed.fusion);
        printf("CDT_KEYS=");
        if (parsed.key_count == 0) {
            printf("none\n");
        } else {
            for (uint8_t i = 0; i < parsed.key_count; i++) {
                printf("%s%u:%u", i ? "," : "", parsed.keys[i], parsed.vals[i]);
            }
            printf("\n");
        }
    } else {
        printf("CDT_PLATFORM=none\n");
        printf("CDT_KEYS=none\n");
    }
    return parsed.state;
}

static int cmd_status_with_hint(const char *dev_path, const char *backup_path, const platform_hint_t *override_hint) {
    uint8_t *buf = NULL;
    size_t len = 0;
    if (read_file_exact(dev_path, &buf, &len) != 0) {
        fprintf(stderr, "cdt_tool: read %s failed: %s\n", dev_path, strerror(errno));
        return 1;
    }
    printf("CDT_DEV=%s\n", dev_path);
    disk_state_t disk_state = print_disk_status(buf, len, backup_status(backup_path, buf, len));

    platform_hint_t hint;
    const char *boot_state = "unknown";
    if (override_hint) {
        hint = *override_hint;
    } else {
        boot_state = detect_boot_cdt_state(&hint);
    }
    printf("BOOT_CDT=%s\n", boot_state);
    if (hint.state == PLATFORM_IDENTIFIED) {
        printf("BOOT_PLATFORM_INFO=%u,%u,%u,%u,%u,%u\n",
               hint.ver, hint.platform_id, hint.major, hint.minor, hint.subtype, hint.fusion);
    } else {
        printf("BOOT_PLATFORM_INFO=unknown\n");
    }
    const cdb_layout_t *layout = NULL;
    const char *source = "none", *check = "unsupported";
    int can_enable = 0;
    if (disk_state == DISK_EMPTY) {
        layout = resolve_empty_cdt_hint(&hint, &check);
        source = hint.ver >= 6 || hint.requires_template ? "active_xbl_config" : "platform";
        can_enable = layout != NULL;
        if (hint.template_size && hint.template[layout->count_offset] == UINT8_MAX) {
            can_enable = 0;
            for (size_t i = layout->header_size; i < hint.template_size; i += 2) {
                if (hint.template[i] == CDT_SLT_KEY) can_enable = 1;
            }
        }
        printf("CDT_EMPTY_CAN_ENABLE=%d\n", can_enable);
    } else if (disk_state == DISK_LEGACY_V6) {
        layout = &cdb_v6_legacy;
        source = "legacy_app";
        check = "restore_required";
    } else if (disk_state == DISK_STOCK || disk_state == DISK_SLT_ENABLED) {
        cdt_parsed_t parsed;
        parse_cdt(buf, len, &parsed);
        layout = parsed.layout;
        source = "cdt";
        check = "confirmed";
        if (disk_state == DISK_STOCK) {
            can_enable = 1;
            if (parsed.version >= 6) {
                hint = platform_from_cdt(&parsed);
                check = read_firmware_template(&hint);
                source = "cdt_and_active_xbl_config";
                if (hint.template_layout && hint.template_layout != layout) check = "layout_mismatch";
                can_enable = hint.template_layout == layout && edit_cdb_key7(buf, len, &parsed, 1, NULL, 0) == 0;
                printf("CDT_V6_CAN_ENABLE=%d\n", can_enable);
            }
        }
    }
    printf("CDT_LAYOUT=%s\nCDT_LAYOUT_SOURCE=%s\nCDT_LAYOUT_CHECK=%s\nCDT_LAYOUT_CAN_ENABLE=%d\n",
           layout ? layout->name : "unknown", source, check, can_enable);
    free(buf);
    return 0;
}

static int cmd_enable_with_hint(
    const char *dev_path,
    const char *backup_path,
    const platform_hint_t *override_hint
) {
    if (recover_transaction(dev_path, backup_path) != 0) return 5;
    uint8_t *buf = NULL;
    size_t len = 0;
    if (read_file_exact(dev_path, &buf, &len) != 0) {
        fprintf(stderr, "cdt_tool: read %s failed: %s\n", dev_path, strerror(errno));
        return 1;
    }
    cdt_parsed_t parsed;
    if (parse_cdt(buf, len, &parsed) != 0 || parsed.state == DISK_CORRUPT) {
        free(buf);
        fprintf(stderr, "cdt_tool: refusing to overwrite unrecognized non-CDT partition data\n");
        return 2;
    }

    const char *original = backup_status(backup_path, buf, len);
    if (strcmp(original, "legacy_enabled_match") == 0) {
        free(buf);
        fprintf(stderr, "CDT_ERR=legacy_v6_restore_required\n");
        return 3;
    }
    if (strcmp(original, "mismatch") == 0 || strcmp(original, "invalid") == 0) {
        free(buf);
        fprintf(stderr, "CDT_ERR=backup_%s\n", original);
        return 3;
    }
    if (parsed.state == DISK_SLT_ENABLED && parsed.key7_val == 1) {
        print_disk_status(buf, len, original);
        free(buf);
        printf("CDT_ACTION=already_enabled\n");
        return 0;
    }

    platform_hint_t detected;
    if (override_hint) {
        detected = *override_hint;
    } else {
        detect_boot_cdt_state(&detected);
    }
    const char *check = "confirmed";
    if (parsed.state == DISK_EMPTY) {
        resolve_empty_cdt_hint(&detected, &check);
    } else if (parsed.version >= 6) {
        detected = platform_from_cdt(&parsed);
        check = read_firmware_template(&detected);
        if (detected.template_layout && detected.template_layout != parsed.layout) check = "layout_mismatch";
    }
    if (strcmp(check, "confirmed") != 0) {
        free(buf);
        fprintf(stderr, "CDT_ERR=layout_unconfirmed:%s\n", check);
        return 4;
    }

    uint8_t *new_img = NULL;
    if (build_cdt_with_key7(buf, len, &parsed, &detected, 1, 1, &new_img) != 0) {
        free(buf);
        if (errno == ENODEV) {
            fprintf(stderr, "cdt_tool: unable to positively identify hardware Platform ID for empty CDT\n");
        } else {
            fprintf(stderr, "cdt_tool: build SLT CDT failed: %s\n", strerror(errno));
        }
        return 4;
    }
    /* Save the original only after the candidate builds successfully. */
    if (strcmp(original, "missing") == 0 && parsed.state != DISK_SLT_ENABLED) {
        if (save_backup_atomic(backup_path, buf, len) != 0) {
            free(new_img);
            free(buf);
            fprintf(stderr, "cdt_tool: failed to save original CDT backup %s: %s\n",
                    backup_path, strerror(errno));
            return 3;
        }
        original = "match";
    }
    int rc = replace_cdt(dev_path, backup_path, buf, new_img, len);
    int saved = errno;
    free(buf);
    if (rc != 0) {
        free(new_img);
        fprintf(stderr, "cdt_tool: write & verify %s failed: %s\n", dev_path, strerror(saved));
        return 5;
    }
    print_disk_status(new_img, len, strcmp(original, "match") == 0 ? "enabled_match" : original);
    free(new_img);
    printf("CDT_ACTION=enabled_verified\n");
    return 0;
}

static int cmd_restore_with_hint(
    const char *dev_path,
    const char *backup_path,
    const char *import_path,
    int allow_fallback_clean,
    const platform_hint_t *override_hint
) {
    if (recover_transaction(dev_path, backup_path) != 0) return 5;
    uint8_t *cur = NULL;
    size_t cur_len = 0;
    if (read_file_exact(dev_path, &cur, &cur_len) != 0) {
        fprintf(stderr, "cdt_tool: read %s failed: %s\n", dev_path, strerror(errno));
        return 1;
    }

    if (import_path || access(backup_path, F_OK) == 0) {
        const char *source = import_path ? import_path : backup_path;
        uint8_t *bak = NULL;
        size_t bak_len = 0;
        if (read_file_exact(source, &bak, &bak_len) != 0 || bak_len != cur_len) {
            free(bak);
            free(cur);
            fprintf(stderr, "CDT_ERR=restore_image_size\n");
            return 2;
        }
        const char *status = compare_backup(bak, bak_len, cur, cur_len);
        int legacy_match = strcmp(status, "legacy_enabled_match") == 0;
        if (strcmp(status, "invalid") == 0) {
            free(bak);
            free(cur);
            fprintf(stderr, "CDT_ERR=restore_image_invalid\n");
            return 3;
        }
        cdt_parsed_t active, legacy;
        int possible_legacy = parse_cdt(cur, cur_len, &active) != 0 &&
                              is_legacy_enabled(cur, cur_len, &legacy);
        if (!legacy_match && (import_path || possible_legacy) &&
            strcmp(status, "match") != 0 && strcmp(status, "enabled_match") != 0) {
            free(bak);
            free(cur);
            fprintf(stderr, "CDT_ERR=restore_image_mismatch\n");
            return 3;
        }
        if (import_path) {
            const char *stored_status = backup_status(backup_path, bak, bak_len);
            if (legacy_match && strcmp(stored_status, "missing") != 0) {
                stored_status = image_matches(backup_path, bak, bak_len) ? "match" : "mismatch";
            }
            if (strcmp(stored_status, "mismatch") == 0 ||
                (strcmp(stored_status, "match") != 0 &&
                 (save_backup_atomic(backup_path, bak, bak_len) != 0 || sync_parent(backup_path) != 0))) {
                free(bak);
                free(cur);
                fprintf(stderr, "CDT_ERR=restore_backup_conflict\n");
                return 3;
            }
        }
        if (memcmp(cur, bak, cur_len) == 0) {
            print_disk_status(cur, cur_len, "match");
            free(bak);
            free(cur);
            printf("CDT_ACTION=already_restored\n");
            return 0;
        }
        int rc = replace_cdt(dev_path, backup_path, cur, bak, cur_len);
        int saved = errno;
        if (rc == 0) print_disk_status(bak, cur_len, "match");
        free(bak);
        free(cur);
        if (rc != 0) {
            fprintf(stderr, "cdt_tool: restore write/verify failed: %s\n", strerror(saved));
            return 4;
        }
        printf("CDT_ACTION=restored_from_backup\n");
        return 0;
    }

    if (!allow_fallback_clean) {
        free(cur);
        fprintf(stderr, "CDT_ERR=no_backup\n");
        return 6;
    }

    cdt_parsed_t cur_parsed;
    if (parse_cdt(cur, cur_len, &cur_parsed) != 0 || cur_parsed.state == DISK_CORRUPT) {
        free(cur);
        fprintf(stderr, "cdt_tool: cannot clean-restore corrupt CDT without backup\n");
        return 3;
    }
    if (cur_parsed.state != DISK_SLT_ENABLED) {
        print_disk_status(cur, cur_len, "missing");
        free(cur);
        printf("CDT_ACTION=already_restored\n");
        return 0;
    }

    platform_hint_t detected;
    if (override_hint) {
        detected = *override_hint;
    } else {
        detect_boot_cdt_state(&detected);
    }
    uint8_t *clean_img = NULL;
    if (build_cdt_with_key7(cur, cur_len, &cur_parsed, &detected, 0, 0, &clean_img) != 0) {
        free(cur);
        fprintf(stderr, "cdt_tool: failed to build clean stock image\n");
        return 4;
    }
    int rc = replace_cdt(dev_path, backup_path, cur, clean_img, cur_len);
    int saved = errno;
    free(cur);
    if (rc != 0) {
        free(clean_img);
        fprintf(stderr, "cdt_tool: clean restore write/verify failed: %s\n", strerror(saved));
        return 5;
    }
    print_disk_status(clean_img, cur_len, "missing");
    free(clean_img);
    printf("CDT_ACTION=restored_clean_fallback\n");
    return 0;
}

static int cmd_selftest(void) {
    char dir[] = "/tmp/cdt_tool_testXXXXXX";
    if (!mkdtemp(dir)) {
        perror("mkdtemp");
        return 1;
    }
    char dev[256], bak[256];
    snprintf(dev, sizeof(dev), "%s/cdt.img", dir);
    snprintf(bak, sizeof(bak), "%s/cdt-orig.bin", dir);

    const size_t part_size = 131072;
    uint8_t *zeros = calloc(1, part_size);
    if (!zeros) return 1;
    FILE *f = fopen(dev, "wb");
    if (!f || fwrite(zeros, 1, part_size, f) != part_size) return 1;
    fclose(f);

    platform_hint_t mtp = {.state = PLATFORM_IDENTIFIED, .ver = 5, .platform_id = 8, .major = 1};
    if (cmd_enable_with_hint(dev, bak, &mtp) != 0) return 1;

    uint8_t *enabled = NULL;
    size_t elen = 0;
    if (read_file_exact(dev, &enabled, &elen) != 0 || elen != part_size) return 1;
    const uint8_t expected_27[27] = {
        0x43, 0x44, 0x54, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x12, 0x00, 0x09, 0x00, 0x05, 0x08, 0x01, 0x00, 0x00, 0x00, 0x01, 0x07, 0x01
    };
    if (memcmp(enabled, expected_27, sizeof(expected_27)) != 0) {
        fprintf(stderr, "selftest: mismatch on 27-byte payload\n");
        return 1;
    }
    for (size_t i = sizeof(expected_27); i < part_size; i++) {
        if (enabled[i] != 0) {
            fprintf(stderr, "selftest: non-zero padding at %zu\n", i);
            return 1;
        }
    }
    cdt_parsed_t p;
    if (parse_cdt(enabled, elen, &p) != 0 || p.state != DISK_SLT_ENABLED || p.key7_val != 1) return 1;

    /* Re-enable must be a safe no-op and must not overwrite original all-zero backup. */
    if (cmd_enable_with_hint(dev, bak, &mtp) != 0) return 1;
    uint8_t *bak_buf = NULL;
    size_t blen = 0;
    if (read_file_exact(bak, &bak_buf, &blen) != 0 || blen != part_size || memcmp(bak_buf, zeros, part_size) != 0) {
        fprintf(stderr, "selftest: original backup was clobbered\n");
        return 1;
    }
    free(bak_buf);

    /* Restore from backup must return exact zeros. */
    if (cmd_restore_with_hint(dev, bak, NULL, 0, &mtp) != 0) return 1;
    uint8_t *restored = NULL;
    size_t rlen = 0;
    if (read_file_exact(dev, &restored, &rlen) != 0 || memcmp(restored, zeros, part_size) != 0) {
        fprintf(stderr, "selftest: restore mismatch\n");
        return 1;
    }
    free(restored);
    unlink(bak);

    /* Test fallback clean restore without backup (e.g., user pre-flashed CDT). */
    f = fopen(dev, "wb");
    if (!f || fwrite(enabled, 1, part_size, f) != part_size) return 1;
    fclose(f);
    if (cmd_restore_with_hint(dev, bak, NULL, 0, &mtp) == 0) {
        fprintf(stderr, "selftest: expected restore without backup/flag to fail\n");
        return 1;
    }
    if (cmd_restore_with_hint(dev, bak, NULL, 1, &mtp) != 0) return 1;
    if (read_file_exact(dev, &restored, &rlen) != 0 || memcmp(restored, zeros, part_size) != 0) {
        fprintf(stderr, "selftest: clean fallback mismatch\n");
        return 1;
    }
    free(restored);

    /* Test multi-block CDT preservation (CDB0 with Key 2=9 + CDB1 DDR blob). */
    uint8_t *multi = calloc(1, part_size);
    memcpy(multi, "CDT\0", 4);
    write_u16_le(multi + 14, 22);
    write_u16_le(multi + 16, 9);
    write_u16_le(multi + 18, 31);
    write_u16_le(multi + 20, 4);
    multi[22] = 5; multi[23] = 8; multi[24] = 1; multi[25] = 0; multi[26] = 0; multi[27] = 0;
    multi[28] = 1; multi[29] = 2; multi[30] = 9;
    multi[31] = 0xaa; multi[32] = 0xbb; multi[33] = 0xcc; multi[34] = 0xdd;
    f = fopen(dev, "wb");
    if (!f || fwrite(multi, 1, part_size, f) != part_size) return 1;
    fclose(f);
    if (cmd_enable_with_hint(dev, bak, &mtp) != 0) return 1;
    uint8_t *m_en = NULL;
    if (read_file_exact(dev, &m_en, &rlen) != 0) return 1;
    if (parse_cdt(m_en, rlen, &p) != 0 || p.state != DISK_SLT_ENABLED || p.key_count != 2 ||
        p.keys[0] != 2 || p.vals[0] != 9 || p.keys[1] != 7 || p.vals[1] != 1 ||
        memcmp(m_en + p.descs[1].offset, multi + 31, 4) != 0) {
        fprintf(stderr, "selftest: multi-block CDT enable corrupted keys or CDB1\n");
        return 1;
    }
    free(m_en);
    if (cmd_restore_with_hint(dev, bak, NULL, 0, &mtp) != 0) return 1;
    if (read_file_exact(dev, &restored, &rlen) != 0 || memcmp(restored, multi, part_size) != 0) {
        fprintf(stderr, "selftest: multi-block restore mismatch\n");
        return 1;
    }
    free(restored);
    free(multi);

    /* Verified SM8850 image from a matching stock xbl_config and the old misplaced-key image. */
    static const uint8_t v6_expected[] = {
        'C', 'D', 'T', 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        18, 0, 12, 0, 6, 8, 1, 0, 0, 0, 0, 0, 0, 1, 7, 1,
    };
    platform_hint_t v6 = {
        .state = PLATFORM_IDENTIFIED, .ver = 6, .platform_id = 8, .major = 1,
        .template_size = 10, .template = {6, 8, 1},
    };
    v6.template_layout = find_cdb_layout(v6.ver, v6.template, v6.template_size, 1);
    cdt_parsed_t empty = {.state = DISK_EMPTY};
    uint8_t *v6_image = NULL;
    if (build_cdt_with_key7(zeros, part_size, &empty, &v6, 1, 1, &v6_image) != 0) return 1;
    int v6_ok = memcmp(v6_image, v6_expected, sizeof(v6_expected)) == 0 &&
                parse_cdt(v6_image, part_size, &p) == 0 && p.key7_val == 1 &&
                strcmp(compare_backup(zeros, part_size, v6_image, part_size), "enabled_match") == 0;
    memset(v6_image, 0, part_size);
    v6.template_size = 0;
    v6_ok = v6_ok && build_empty_cdt(v6_image, &v6, &cdb_v6_legacy, 1) == 0 &&
            parse_cdt(v6_image, part_size, &p) != 0 &&
            strcmp(compare_backup(zeros, part_size, v6_image, part_size), "legacy_enabled_match") == 0;
    free(v6_image);
    if (!v6_ok) {
        fprintf(stderr, "selftest: v6 layout or legacy backup matching failed\n");
        return 1;
    }

    /* Reject corrupt non-zero non-CDT garbage. */
    memset(enabled, 0x5a, 64);
    f = fopen(dev, "wb");
    if (!f || fwrite(enabled, 1, part_size, f) != part_size) return 1;
    fclose(f);
    free(enabled);
    if (cmd_enable_with_hint(dev, bak, &mtp) == 0) {
        fprintf(stderr, "selftest: corrupt CDT was not rejected\n");
        return 1;
    }

    free(zeros);
    unlink(dev);
    unlink(bak);
    rmdir(dir);
    printf("CDT_SELFTEST=PASS\n");
    return 0;
}

static int cmd_backup(const char *device, const char *destination) {
    uint8_t *data = NULL;
    size_t size = 0;
    if (read_file_exact(device, &data, &size) != 0) {
        fprintf(stderr, "cdt_tool: read %s failed: %s\n", device, strerror(errno));
        return 1;
    }
    int rc = save_backup_atomic(destination, data, size);
    if (rc != 0) {
        fprintf(stderr, "cdt_tool: backup %s failed: %s\n", destination, strerror(errno));
    }
    free(data);
    return rc != 0;
}

int main(int argc, char **argv) {
    if (argc >= 2 && strcmp(argv[1], "selftest") == 0) {
        return cmd_selftest();
    }
    if ((argc == 3 || argc == 4) && strcmp(argv[1], "status") == 0) {
        return cmd_status_with_hint(argv[2], argc == 4 ? argv[3] : NULL, NULL);
    }
    if (argc == 4 && strcmp(argv[1], "backup") == 0) {
        return cmd_backup(argv[2], argv[3]);
    }
    if (argc == 4 && strcmp(argv[1], "recover") == 0) {
        return recover_transaction(argv[2], argv[3]) != 0;
    }
    if (argc == 4 && strcmp(argv[1], "enable") == 0) {
        int rc = cmd_enable_with_hint(argv[2], argv[3], NULL);
        printf("CDT_RESULT=%s\n", rc == 0 ? "verified" : operation_result);
        return rc;
    }
    if ((argc == 4 || (argc == 5 && strcmp(argv[4], "--fallback-clean") == 0) ||
         (argc == 6 && strcmp(argv[4], "--from-file") == 0)) &&
        strcmp(argv[1], "restore") == 0) {
        int rc = cmd_restore_with_hint(argv[2], argv[3], argc == 6 ? argv[5] : NULL, argc == 5, NULL);
        printf("CDT_RESULT=%s\n", rc == 0 ? "verified" : operation_result);
        return rc;
    }
    fprintf(stderr, "Usage: cdt_tool status <dev> [backup] | backup <dev> <image> | recover <dev> <backup> | enable <dev> <backup> | restore <dev> <backup> [--fallback-clean|--from-file <image>] | selftest\n");
    return 2;
}
