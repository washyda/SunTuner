#ifndef SUNTUNER_CDT_FDT_H
#define SUNTUNER_CDT_FDT_H

#include <stddef.h>
#include <stdint.h>

void cdt_visit_platform_info(const uint8_t *image, size_t bytes,
                             void (*visit)(const uint8_t *, size_t, void *), void *context);

#endif
