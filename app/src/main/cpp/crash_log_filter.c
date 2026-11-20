#define _POSIX_C_SOURCE 200809L
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define MAX_ARCHIVE_BYTES (32U * 1024U * 1024U)
#define DATE_BYTES 19U

static bool starts_with(const char *line, size_t length, const char *prefix) {
    size_t prefix_length = strlen(prefix);
    return length >= prefix_length && memcmp(line, prefix, prefix_length) == 0;
}

static bool write_span(const char *start, const char *end) {
    size_t length = (size_t)(end - start);
    return fwrite(start, 1, length, stdout) == length;
}

static int filter(const char *data, size_t length, const char *date, const char *marker) {
    const char *end = data + length;
    const char *copy_from = data;
    const char *block = NULL;
    bool date_matches = false;
    bool marker_matches = false;
    bool removed = false;
    size_t marker_length = marker ? strlen(marker) : 0;

    for (const char *line = data; line < end;) {
        const char *newline = memchr(line, '\n', (size_t)(end - line));
        const char *next = newline ? newline + 1 : end;
        size_t bytes = (size_t)((newline ? newline : end) - line);
        if (bytes > 0 && line[bytes - 1] == '\r') {
            bytes--;
        }

        if (starts_with(line, bytes, "<kernel-panic-begin>")) {
            block = line;
            date_matches = date == NULL;
            marker_matches = false;
        } else if (block != NULL && starts_with(line, bytes, "log-time: ") &&
                   date != NULL && bytes >= sizeof("log-time: ") - 1 + DATE_BYTES &&
                   memcmp(line + sizeof("log-time: ") - 1, date, DATE_BYTES) == 0) {
            date_matches = true;
        } else if (block != NULL && starts_with(line, bytes, "kernel_panic") &&
                   (marker == NULL || (bytes == marker_length && memcmp(line, marker, bytes) == 0))) {
            marker_matches = true;
        } else if (block != NULL && newline != NULL && starts_with(line, bytes, "<kernel-panic-end>")) {
            if (date_matches && marker_matches) {
                if (!write_span(copy_from, block)) {
                    return 1;
                }
                copy_from = next;
                removed = true;
            }
            block = NULL;
        }
        line = next;
    }

    if (!removed) {
        return 3;
    }
    return write_span(copy_from, end) && fflush(stdout) == 0 ? 0 : 1;
}

int main(int argc, char **argv) {
    if (argc != 1 && (argc != 3 || strlen(argv[1]) != DATE_BYTES)) {
        return 2;
    }
    struct stat info;
    if (fstat(STDIN_FILENO, &info) != 0 || !S_ISREG(info.st_mode) ||
        info.st_size < 0 || (unsigned long long)info.st_size > MAX_ARCHIVE_BYTES) {
        return 1;
    }
    size_t length = (size_t)info.st_size;
    if (length == 0) {
        return 3;
    }
    char *data = malloc(length);
    if (data == NULL) {
        return 1;
    }
    int result = 1;
    if (fread(data, 1, length, stdin) == length && fgetc(stdin) == EOF && !ferror(stdin)) {
        result = filter(data, length, argc == 3 ? argv[1] : NULL, argc == 3 ? argv[2] : NULL);
    }
    free(data);
    return result;
}
