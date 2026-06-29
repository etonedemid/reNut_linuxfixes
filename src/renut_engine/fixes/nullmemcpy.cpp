/**
 * nullmemcpy.cpp -- Guard against memcpy/memmove with null destination.
 *
 * The game's movie player (CCalVideoRenderer → CCalMoviePlayer) calls memcpy
 * with a null destination (r3=0) when a video buffer hasn't been allocated.
 * On original Xbox 360 hardware this may have been a no-op due to the memory
 * layout, but on the host it causes a crash. This override silently skips the
 * copy when dst is 0.
 */

#include "generated/renut_init.h"
#include <cstring>

// memcpy  @ 0x82BB4510
REX_EXTERN(sub_82BB4510) {
    REX_FUNC_PROLOGUE();
    uint32_t dst = ctx.r3.u32;
    uint32_t src = ctx.r4.u32;
    uint32_t n   = ctx.r5.u32;
    if (!dst || !src || !n) {
        // Return dst unchanged (C memcpy contract)
        return;
    }
    std::memcpy(REX_RAW_ADDR(dst), REX_RAW_ADDR(src), n);
}

// memmove @ 0x82BB2C70
REX_EXTERN(sub_82BB2C70) {
    REX_FUNC_PROLOGUE();
    uint32_t dst = ctx.r3.u32;
    uint32_t src = ctx.r4.u32;
    uint32_t n   = ctx.r5.u32;
    if (!dst || !src || !n) {
        return;
    }
    std::memmove(REX_RAW_ADDR(dst), REX_RAW_ADDR(src), n);
}

// nut_Render_CCalVideoRenderer @ 0x82A0D9E8
//
// Blits a decoded intro-movie frame. Video decode currently produces invalid
// XMEDIA_VIDEO_FRAMEs (garbage width/height/pitch), so the internal scanline
// copy runs with an absurd size (~8.5 GB observed) and SIGSEGVs on the first
// logo -- on every GPU. (On RADV the same bad surface also crashes the GPU-side
// IssueCopy resolve first.) There is no valid frame to draw, so skip the render
// entirely; the movie's audio and timing still run, the player advances, and the
// game boots into 3D. Movies show black until the video frame path is fixed.
REX_EXTERN(sub_82A0D9E8) {
    REX_FUNC_PROLOGUE();
    ctx.r3.u64 = 0;
}
