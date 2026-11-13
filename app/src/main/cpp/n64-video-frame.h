#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define N64_VIDEO_PIXEL_FORMAT_RGBA8888 1u

typedef struct N64VideoFrame {
    const uint8_t* pixels;
    uint32_t width;
    uint32_t height;
    uint32_t stride_bytes;
    uint32_t pixel_format;
    uint64_t frame_number;
} N64VideoFrame;

/*
 * The callback must consume/copy the frame before it returns. The producer
 * retains ownership of pixels and may reuse the memory after the callback.
 */
typedef void (*N64VideoFrameCallback)(
    const N64VideoFrame* frame,
    void* user_data
);

#ifdef __cplusplus
}
#endif
