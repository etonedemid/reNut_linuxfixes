#pragma once
#include "renut_logging.h"
#include <chrono>
#include <thread>
#ifdef _WIN32
#include <timeapi.h>
#else
#include <sched.h>
#endif

#if defined(__x86_64__) || defined(_M_X64) || defined(__i386__) || defined(_M_IX86)
#include <immintrin.h>
#define REX_SPIN_PAUSE() _mm_pause()
#elif defined(__aarch64__) || defined(_M_ARM64)
#define REX_SPIN_PAUSE() __asm__ volatile("yield")
#else
#define REX_SPIN_PAUSE() ((void)0)
#endif


std::once_flag g_timer_init;

void EnableHighResTimer() {
    std::call_once(g_timer_init, [] {
#ifdef _WIN32
        timeBeginPeriod(1);
#endif
        RNUT_INFO("[threading] high-res timer enabled");
        });
}

void DisableHighResTimer() {
#ifdef _WIN32
    timeEndPeriod(1);
#endif
    RNUT_INFO("[threading] high-res timer disabled");
}

// Sleep CRT hook
u32 Sleep_hook(u32 ms) {
    EnableHighResTimer();

    if (ms == 0) {
#ifdef _WIN32
        SwitchToThread();
#else
        sched_yield();
#endif
        return 0;
    }

    auto target = std::chrono::steady_clock::now()
        + std::chrono::milliseconds(uint32_t(ms));

    if (ms >= 2) {
        std::this_thread::sleep_for(
            std::chrono::milliseconds(uint32_t(ms)) - std::chrono::microseconds(1500));
    }
    else {
        // Must yield to OS so lower-priority threads get scheduled
#ifdef _WIN32
        ::Sleep(1);
#else
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
#endif
    }

    while (std::chrono::steady_clock::now() < target)
        REX_SPIN_PAUSE();

    return 0;
}
REX_HOOK(sub_82715B60, Sleep_hook);