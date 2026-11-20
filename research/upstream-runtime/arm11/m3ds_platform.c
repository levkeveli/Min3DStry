/* Minimal TeaVM platform services for the ARM11 smoke probe. */
#include <3ds.h>
#include <limits.h>
#include <malloc.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/time.h>
#include <m3ds_map_file.h>

static LightEvent m3ds_event;
static u64 m3ds_initial_tick;
static char m3ds_log_line[192];
static size_t m3ds_log_size;

void* m3ds_map_open(void) {
    return fopen("sdmc:/3ds/min3dstry/glacier.msav", "rb");
}

int32_t m3ds_map_read_byte(void* file) {
    FILE* stream = file;
    int value = fgetc(stream);
    return value == EOF ? (ferror(stream) ? -2 : -1) : value;
}

int32_t m3ds_map_read(void* file, void* destination, int32_t length) {
    FILE* stream = file;
    size_t count = fread(destination, 1, (size_t)length, stream);
    return count ? (int32_t)count : (ferror(stream) ? -2 : -1);
}

void m3ds_map_close(void* file) {
    fclose(file);
}

void teavm_initFiber(void) {
    LightEvent_Init(&m3ds_event, RESET_ONESHOT);
}

void teavm_waitFor(int64_t timeout_ms) {
    if (timeout_ms < 0) {
        LightEvent_Wait(&m3ds_event);
        return;
    }
    const int64_t ns_per_ms = 1000000;
    int64_t timeout_ns = timeout_ms > INT64_MAX / ns_per_ms
        ? INT64_MAX : timeout_ms * ns_per_ms;
    (void)LightEvent_WaitTimeout(&m3ds_event, timeout_ns);
}

void teavm_interrupt(void) {
    LightEvent_Signal(&m3ds_event);
}

void teavm_initTime(void) {
    m3ds_initial_tick = svcGetSystemTick();
}

int64_t teavm_currentTimeMillis(void) {
    struct timeval value;
    if (gettimeofday(&value, NULL) != 0) {
        return 0;
    }
    return (int64_t)value.tv_sec * 1000 + value.tv_usec / 1000;
}

int64_t teavm_currentTimeNano(void) {
    uint64_t elapsed = svcGetSystemTick() - m3ds_initial_tick;
    uint64_t seconds = elapsed / SYSCLOCK_ARM11;
    uint64_t ticks = elapsed % SYSCLOCK_ARM11;
    return seconds * UINT64_C(1000000000) + ticks * UINT64_C(1000000000) / SYSCLOCK_ARM11;
}

static int64_t teavm_pageSize(void) {
    return 4096;
}

static void* teavm_virtualAlloc(int64_t size) {
    if (size == 0) {
        size = teavm_pageSize();
    }
    if (size < 0 || (uint64_t)size > SIZE_MAX) {
        abort();
    }
    void* memory = memalign((size_t)teavm_pageSize(), (size_t)size);
    if (!memory) {
        char diagnostic[112];
        int length = snprintf(diagnostic, sizeof(diagnostic),
            "UPSTREAM_MEMORY allocation-failed bytes=%lu\n", (unsigned long)size);
        if (length > 0 && length < (int)sizeof(diagnostic)) {
            svcOutputDebugString(diagnostic, length);
        }
        abort();
    }
    memset(memory, 0, (size_t)size);
    return memory;
}

/* The smoke heap is backed for its entire lifetime. */
static void teavm_virtualCommit(void* address, int64_t size) {
    (void)address;
    (void)size;
}

static void teavm_virtualUncommit(void* address, int64_t size) {
    memset(address, 0, (size_t)size);
}

static void m3ds_flush_log(void) {
    if (m3ds_log_size == 0) {
        return;
    }
    fwrite(m3ds_log_line, 1, m3ds_log_size, stdout);
    svcOutputDebugString(m3ds_log_line, (s32)m3ds_log_size);
    m3ds_log_size = 0;
}

static void m3ds_log_codepoint(int32_t codepoint) {
    uint8_t bytes[4];
    ssize_t size = encode_utf8(bytes, (uint32_t)codepoint);
    if (size < 0) {
        return;
    }
    if (m3ds_log_size + (size_t)size > sizeof(m3ds_log_line)) {
        m3ds_flush_log();
    }
    memcpy(m3ds_log_line + m3ds_log_size, bytes, (size_t)size);
    m3ds_log_size += (size_t)size;
    if (codepoint == '\n') {
        m3ds_flush_log();
    }
}
