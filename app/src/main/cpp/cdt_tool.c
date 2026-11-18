#define _POSIX_C_SOURCE 200809L

#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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
};

typedef struct {
    uint16_t offset;
    uint16_t size;
} cdt_desc_t;

typedef struct {
    int present;
    uint8_t ver;
    uint8_t platform_id;
    uint8_t major;
    uint8_t minor;
    uint8_t subtype;
    uint8_t fusion;
} platform_hint_t;

typedef enum {
    DISK_EMPTY = 0,
    DISK_STOCK = 1,
    DISK_SLT_ENABLED = 2,
    DISK_CORRUPT = 3,
} disk_state_t;

typedef struct {
    disk_state_t state;
    uint8_t version;
    uint8_t platform_id;
    uint8_t major;
    uint8_t minor;
    uint8_t subtype;
    uint8_t fusion;
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

static int read_entire_fd(int fd, uint8_t **out_buf, size_t *out_len) {
    off_t end = lseek(fd, 0, SEEK_END);
    if (end < 0) return -1;
    if (end < CDT_MIN_PART_SIZE || end > CDT_MAX_PART_SIZE) {
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

static int read_file_exact(const char *path, uint8_t **out_buf, size_t *out_len) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    int rc = read_entire_fd(fd, out_buf, out_len);
    int saved = errno;
    close(fd);
    errno = saved;
    return rc;
}

static int write_all_fd(int fd, const uint8_t *buf, size_t len) {
    size_t done = 0;
    while (done < len) {
        ssize_t n = write(fd, buf + done, len - done);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        done += (size_t)n;
    }
    return fsync(fd) == 0 ? 0 : -1;
}

static int write_block_and_verify(const char *path, const uint8_t *buf, size_t len) {
    int fd = open(path, O_RDWR);
    if (fd < 0) return -1;
    off_t end = lseek(fd, 0, SEEK_END);
    if (end < 0 || (size_t)end != len || lseek(fd, 0, SEEK_SET) < 0) {
        close(fd);
        errno = EINVAL;
        return -1;
    }
    if (write_all_fd(fd, buf, len) != 0) {
        int saved = errno;
        close(fd);
        errno = saved;
        return -1;
    }
#ifdef BLKFLSBUF
    struct stat st;
    if (fstat(fd, &st) == 0 && S_ISBLK(st.st_mode)) {
        (void)ioctl(fd, BLKFLSBUF);
    }
#endif
    close(fd);

    uint8_t *verify = NULL;
    size_t verify_len = 0;
    if (read_file_exact(path, &verify, &verify_len) != 0) return -1;
    int match = (verify_len == len && memcmp(buf, verify, len) == 0);
    free(verify);
    if (!match) {
        errno = EIO;
        return -1;
    }
    return 0;
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
    if (write_all_fd(fd, buf, len) != 0 || fsync(fd) != 0) {
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

static int parse_cdt(const uint8_t *buf, size_t len, cdt_parsed_t *out) {
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
    (void)size0;

    const uint8_t *p = buf + off0;
    uint16_t psz = out->descs[0].size;
    if (psz < 6) {
        out->state = DISK_CORRUPT;
        return -1;
    }
    out->version = p[0];
    if (out->version < 3) {
        out->state = DISK_CORRUPT;
        return -1;
    }
    out->platform_id = p[1];
    out->major = p[2];
    out->minor = p[3];
    out->subtype = p[4];

    size_t hdr_len = (out->version == 3) ? 6 : 7;
    if (psz < hdr_len) {
        out->state = DISK_CORRUPT;
        return -1;
    }
    out->fusion = (out->version >= 5) ? p[5] : 0;
    out->key_count = (out->version == 3) ? p[5] : p[6];
    if (hdr_len + (size_t)out->key_count * 2u > psz) {
        out->state = DISK_CORRUPT;
        return -1;
    }
    for (uint8_t i = 0; i < out->key_count; i++) {
        uint8_t k = p[hdr_len + i * 2u];
        uint8_t v = p[hdr_len + i * 2u + 1u];
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

static const char *detect_boot_cdt_state(platform_hint_t *hint) {
    static char boot_buf[262144];
    memset(hint, 0, sizeof(*hint));
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
                hint->present = 1;
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

    if (!hint->present) {
        char hw[64] = {0}, pver[64] = {0}, sub[64] = {0};
        if (read_small_text("/sys/devices/soc0/hw_platform", hw, sizeof(hw)) == 0) trim_ws(hw);
        if (strcmp(hw, "MTP") == 0 || saw_mtp) {
            unsigned long ver_num = 0x10000UL;
            unsigned long sub_num = 0UL;
            if (read_small_text("/sys/devices/soc0/platform_version", pver, sizeof(pver)) == 0) {
                ver_num = strtoul(pver, NULL, 0);
            }
            if (read_small_text("/sys/devices/soc0/platform_subtype_id", sub, sizeof(sub)) == 0) {
                sub_num = strtoul(sub, NULL, 0);
            }
            hint->present = 1;
            hint->ver = 5;
            hint->platform_id = 8; /* MTP verified in XBL FALLBACK_RECORD 0x148D28B0 */
            hint->major = (uint8_t)((ver_num >> 16) & 0xffu);
            hint->minor = (uint8_t)(ver_num & 0xffu);
            hint->subtype = (uint8_t)(sub_num & 0xffu);
            hint->fusion = 0;
        }
    }

    if (saw_slt_not_found || saw_cdt_not_found) return "not_found";
    if (saw_version) return "applied";
    return "unknown";
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
        if (!hint->present) {
            free(img);
            errno = ENODEV;
            return -1;
        }
        memcpy(img, "CDT\0", 4);
        write_u16_le(img + CDT_HEADER_SIZE, 18);
        write_u16_le(img + CDT_HEADER_SIZE + 2, 9);
        img[18] = hint->ver;
        img[19] = hint->platform_id;
        img[20] = hint->major;
        img[21] = hint->minor;
        img[22] = hint->subtype;
        img[23] = hint->fusion;
        img[24] = 1;
        img[25] = CDT_SLT_KEY;
        img[26] = key7_value;
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
        parsed->version == 5 && parsed->fusion == 0 && hint->present &&
        parsed->platform_id == hint->platform_id && parsed->major == hint->major &&
        parsed->minor == hint->minor && parsed->subtype == hint->subtype) {
        /* Fallback clean stock when partition originally held only our injected Key 7 record. */
        *out_img = img;
        return 0;
    }

    size_t hdr_len = (parsed->version == 3) ? 6 : 7;
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
    if (parsed->version == 3) {
        img[cursor + 5] = new_count;
    } else {
        img[cursor + 6] = new_count;
    }
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
        default: return "corrupt";
    }
}

static int cmd_status(const char *dev_path) {
    uint8_t *buf = NULL;
    size_t len = 0;
    if (read_file_exact(dev_path, &buf, &len) != 0) {
        fprintf(stderr, "cdt_tool: read %s failed: %s\n", dev_path, strerror(errno));
        return 1;
    }
    cdt_parsed_t parsed;
    parse_cdt(buf, len, &parsed);
    free(buf);

    platform_hint_t hint;
    const char *boot_state = detect_boot_cdt_state(&hint);

    printf("CDT_DEV=%s\n", dev_path);
    printf("CDT_SIZE=%zu\n", len);
    printf("CDT_DISK=%s\n", disk_state_str(parsed.state));
    if (parsed.has_key7) {
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
    printf("BOOT_CDT=%s\n", boot_state);
    if (hint.present) {
        printf("BOOT_PLATFORM_INFO=%u,%u,%u,%u,%u,%u\n",
               hint.ver, hint.platform_id, hint.major, hint.minor, hint.subtype, hint.fusion);
    } else {
        printf("BOOT_PLATFORM_INFO=unknown\n");
    }
    return 0;
}

static int cmd_enable_with_hint(
    const char *dev_path,
    const char *backup_path,
    const platform_hint_t *override_hint
) {
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

    if (parsed.state == DISK_SLT_ENABLED && parsed.key7_val == 1) {
        free(buf);
        printf("CDT_ACTION=already_enabled\n");
        return 0;
    }

    /* Persist original unmodified partition ONCE; never overwrite with a Key7!=0 image. */
    if (access(backup_path, F_OK) != 0 && parsed.state != DISK_SLT_ENABLED) {
        if (save_backup_atomic(backup_path, buf, len) != 0) {
            free(buf);
            fprintf(stderr, "cdt_tool: failed to save original CDT backup %s: %s\n",
                    backup_path, strerror(errno));
            return 3;
        }
    }

    platform_hint_t detected;
    if (override_hint) {
        detected = *override_hint;
    } else {
        detect_boot_cdt_state(&detected);
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
    free(buf);

    if (write_block_and_verify(dev_path, new_img, len) != 0) {
        free(new_img);
        fprintf(stderr, "cdt_tool: write & verify %s failed: %s\n", dev_path, strerror(errno));
        return 5;
    }
    free(new_img);
    printf("CDT_ACTION=enabled_verified\n");
    return 0;
}

static int cmd_restore_with_hint(
    const char *dev_path,
    const char *backup_path,
    int allow_fallback_clean,
    const platform_hint_t *override_hint
) {
    uint8_t *cur = NULL;
    size_t cur_len = 0;
    if (read_file_exact(dev_path, &cur, &cur_len) != 0) {
        fprintf(stderr, "cdt_tool: read %s failed: %s\n", dev_path, strerror(errno));
        return 1;
    }

    if (access(backup_path, F_OK) == 0) {
        uint8_t *bak = NULL;
        size_t bak_len = 0;
        if (read_file_exact(backup_path, &bak, &bak_len) != 0 || bak_len != cur_len) {
            free(bak);
            free(cur);
            fprintf(stderr, "cdt_tool: backup %s size mismatch or unreadable\n", backup_path);
            return 2;
        }
        cdt_parsed_t bak_parsed;
        if (parse_cdt(bak, bak_len, &bak_parsed) != 0 || bak_parsed.state == DISK_SLT_ENABLED) {
            free(bak);
            free(cur);
            fprintf(stderr, "cdt_tool: backup %s is corrupt or not stock\n", backup_path);
            return 3;
        }
        if (memcmp(cur, bak, cur_len) == 0) {
            free(bak);
            free(cur);
            printf("CDT_ACTION=already_restored\n");
            return 0;
        }
        int rc = write_block_and_verify(dev_path, bak, cur_len);
        free(bak);
        free(cur);
        if (rc != 0) {
            fprintf(stderr, "cdt_tool: restore write/verify failed: %s\n", strerror(errno));
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
    free(cur);
    if (write_block_and_verify(dev_path, clean_img, cur_len) != 0) {
        free(clean_img);
        fprintf(stderr, "cdt_tool: clean restore write/verify failed\n");
        return 5;
    }
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

    platform_hint_t mtp = {1, 5, 8, 1, 0, 0, 0};
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
    if (cmd_restore_with_hint(dev, bak, 0, &mtp) != 0) return 1;
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
    if (cmd_restore_with_hint(dev, bak, 0, &mtp) == 0) {
        fprintf(stderr, "selftest: expected restore without backup/flag to fail\n");
        return 1;
    }
    if (cmd_restore_with_hint(dev, bak, 1, &mtp) != 0) return 1;
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
    if (cmd_restore_with_hint(dev, bak, 0, &mtp) != 0) return 1;
    if (read_file_exact(dev, &restored, &rlen) != 0 || memcmp(restored, multi, part_size) != 0) {
        fprintf(stderr, "selftest: multi-block restore mismatch\n");
        return 1;
    }
    free(restored);
    free(multi);

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
    if (argc == 3 && strcmp(argv[1], "status") == 0) {
        return cmd_status(argv[2]);
    }
    if (argc == 4 && strcmp(argv[1], "backup") == 0) {
        return cmd_backup(argv[2], argv[3]);
    }
    if (argc == 4 && strcmp(argv[1], "enable") == 0) {
        return cmd_enable_with_hint(argv[2], argv[3], NULL);
    }
    if ((argc == 4 || (argc == 5 && strcmp(argv[4], "--fallback-clean") == 0)) &&
        strcmp(argv[1], "restore") == 0) {
        return cmd_restore_with_hint(argv[2], argv[3], argc == 5, NULL);
    }
    fprintf(stderr, "Usage: cdt_tool status <dev> | backup <dev> <image> | enable <dev> <backup> | restore <dev> <backup> [--fallback-clean] | selftest\n");
    return 2;
}
