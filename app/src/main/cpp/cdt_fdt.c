#include "cdt_fdt.h"

#include <string.h>

enum { FDT_BEGIN_NODE = 1, FDT_END_NODE, FDT_PROP, FDT_NOP, FDT_END = 9, FDT_HEADER_SIZE = 40 };

static uint32_t read_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3];
}

static size_t aligned_length(size_t bytes) {
    return (bytes + 3u) & ~(size_t)3u;
}

static const uint8_t *platform_property(const uint8_t *tree, size_t size, size_t *length) {
    const size_t structure = read_be32(tree + 8);
    const size_t strings = read_be32(tree + 12);
    const size_t string_size = read_be32(tree + 32);
    const size_t structure_size = read_be32(tree + 36);
    if (structure < FDT_HEADER_SIZE || (structure & 3u) || structure > size ||
        structure_size > size - structure || strings < FDT_HEADER_SIZE || strings > size ||
        string_size > size - strings) return NULL;

    static const char *const path[] = {"", "soc", "PlatformInfo"};
    size_t depth = 0, prefix = 0;
    int root_seen = 0;
    const uint8_t *property = NULL;
    const uint8_t *p = tree + structure;
    const uint8_t *end = p + structure_size;
    while ((size_t)(end - p) >= 4) {
        uint32_t token = read_be32(p);
        p += 4;
        if (token == FDT_BEGIN_NODE) {
            const uint8_t *nul = memchr(p, 0, (size_t)(end - p));
            if (!nul) return NULL;
            if (!depth) {
                if (root_seen || p != nul) return NULL;
                root_seen = 1;
            }
            size_t advance = aligned_length((size_t)(nul - p) + 1);
            if (advance > (size_t)(end - p)) return NULL;
            if (depth == prefix && prefix < sizeof(path) / sizeof(*path) &&
                strcmp((const char *)p, path[prefix]) == 0) ++prefix;
            ++depth;
            p += advance;
        } else if (token == FDT_END_NODE) {
            if (!depth) return NULL;
            if (prefix > --depth) prefix = depth;
        } else if (token == FDT_PROP) {
            if (!depth || (size_t)(end - p) < 8) return NULL;
            size_t bytes = read_be32(p);
            size_t name_offset = read_be32(p + 4);
            p += 8;
            if (bytes > (size_t)(end - p) || aligned_length(bytes) > (size_t)(end - p) ||
                name_offset >= string_size) return NULL;
            const char *name = (const char *)(tree + strings + name_offset);
            if (!memchr(name, 0, string_size - name_offset)) return NULL;
            if (depth == 3 && prefix == 3 && strcmp(name, "PlatformInfo") == 0) {
                if (property) return NULL;
                property = p;
                *length = bytes;
            }
            p += aligned_length(bytes);
        } else if (token == FDT_END) {
            return depth == 0 ? property : NULL;
        } else if (token != FDT_NOP) {
            return NULL;
        }
    }
    return NULL;
}

void cdt_visit_platform_info(const uint8_t *image, size_t bytes,
                             void (*visit)(const uint8_t *, size_t, void *), void *context) {
    for (size_t offset = 0; bytes - offset >= FDT_HEADER_SIZE; ++offset) {
        const uint8_t *tree = image + offset;
        if (tree[0] != 0xd0 || read_be32(tree) != UINT32_C(0xd00dfeed)) continue;
        size_t size = read_be32(tree + 4);
        if (size < FDT_HEADER_SIZE || size > bytes - offset || read_be32(tree + 20) < 17 ||
            read_be32(tree + 24) > 17) continue;
        size_t length = 0;
        const uint8_t *property = platform_property(tree, size, &length);
        if (property) visit(property, length, context);
    }
}
