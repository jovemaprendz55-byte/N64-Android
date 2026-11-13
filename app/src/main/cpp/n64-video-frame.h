#pragma once

#include <stdint.h>
#include <stddef.h>

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
 * Frame contract:
 * - RGBA8888 means four bytes per pixel, in R, G, B, A order.
 * - Rows are top-to-bottom.
 * - stride_bytes may be larger than width * 4; consumers must honor it.
 * - The producer owns pixels and may reuse them as soon as the callback
 *   returns. A Vulkan upload implementation must therefore copy the data
 *   into staging memory before returning.
 */
static inline int n64VideoFrameIsValid(const N64VideoFrame* frame) {
    if (frame == NULL || frame->pixels == NULL) return 0;
    if (frame->width == 0 || frame->height == 0) return 0;
    if (frame->pixel_format != N64_VIDEO_PIXEL_FORMAT_RGBA8888) return 0;
    if ((uint64_t)frame->width * 4u > UINT32_MAX) return 0;
    if (frame->stride_bytes < frame->width * 4u) return 0;
    if ((uint64_t)frame->stride_bytes * frame->height > SIZE_MAX) return 0;
    return 1;
}

/*
 * The callback must validate the frame and consume/copy its pixels before
 * returning. It must not retain frame->pixels or the frame pointer.
 */
/*
 * Vulkan backend submission API. The implementation copies the frame before
 * returning. Current texture contract is fixed at 256x192 RGBA8888.
 * Returns non-zero when the upload was accepted and completed.
 */
#ifdef __cplusplus
bool n64VulkanSubmitVideoFrame(const N64VideoFrame* frame);
#endif

typedef void (*N64VideoFrameCallback)(
    const N64VideoFrame* frame,
    void* user_data
);

#ifdef __cplusplus
/* Function with the exact signature expected by N64VideoFrameCallback. */
void n64VulkanVideoFrameCallback(const N64VideoFrame* frame, void* user_data);
#endif

#ifdef __cplusplus
}
#endif
