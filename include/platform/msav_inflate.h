#ifndef MIN3DSTRY_PLATFORM_MSAV_INFLATE_H
#define MIN3DSTRY_PLATFORM_MSAV_INFLATE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Decodes the beginning of a zlib-wrapped stream (RFC 1950/1951) into out.
 * Only the first out_capacity bytes are produced, so a save container can be
 * inspected without inflating the whole world.
 *
 * Returns false for a malformed header, a truncated stream, or a broken
 * DEFLATE block before any output; otherwise true and sets *out_size to the
 * number of bytes written (at most out_capacity).
 */
bool m3ds_msav_inflate_prefix(
    const uint8_t *zlib_data, size_t zlib_size, uint8_t *out,
    size_t out_capacity, size_t *out_size);

#ifdef __cplusplus
}
#endif

#endif /* MIN3DSTRY_PLATFORM_MSAV_INFLATE_H */
