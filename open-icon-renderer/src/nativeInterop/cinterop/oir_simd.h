#ifndef OIR_SIMD_H
#define OIR_SIMD_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Pack ARGB8888 ints into PNG filter-None raw (per-row leading 0 byte + RGBA bytes).
 * raw length must be height * (1 + width * 4).
 */
void oir_pack_argb_to_rgba_filter_none(
    const int32_t *pixels,
    uint8_t *raw,
    int32_t width,
    int32_t height
);

/**
 * Fill opaque linear-gradient span: pixels[start + i] = lut[clamp(t0 + i*dt)].
 * lut must have 256 ARGB entries. count >= 0.
 */
void oir_fill_linear_lut_span(
    int32_t *pixels,
    int32_t start,
    int32_t count,
    const int32_t *lut,
    float t0,
    float dt
);

/**
 * Fill opaque radial-gradient span along +x at fixed y:
 * t = invR * sqrt((x0+0.5+i - cx)^2 + (y - cy)^2), pixels[start+i] = lut[clamp(t)].
 */
void oir_fill_radial_lut_span(
    int32_t *pixels,
    int32_t start,
    int32_t count,
    const int32_t *lut,
    float x0_center,
    float y,
    float cx,
    float cy,
    float invR
);

#ifdef __cplusplus
}
#endif

#endif
