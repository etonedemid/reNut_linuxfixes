/**
 * nullmemcpy.cpp — Guard against memcpy/memmove with null destination.
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
