// libctru's 32 KiB default stack is too small for generated save serialization.
#ifdef M3DS_SINGLEPLAYER
// Serializing objective markers in a stock campaign save can exceed 256 KiB.
u32 __stacksize__ = 512 * 1024;
#else
u32 __stacksize__ = 256 * 1024;
#endif

#ifdef M3DS_TERRAIN_RENDERER
#include "m3ds_terrain_renderer.h"
#endif

// Reserve linear memory for the console framebuffers and Citro2D.
u32 __ctru_linear_heap_size = 4 * 1024 * 1024;

static void m3ds_init_heap(int64_t minimum, int64_t maximum) {
#ifdef M3DS_SINGLEPLAYER
    const int64_t new3ds_limit = 48 * 1024 * 1024;
    const u32 required_app_heap = 72 * 1024 * 1024;
    bool is_new_3ds = false;
    // Leave space in the application heap for the GC tables, SD I/O and renderer.
    if (maximum < new3ds_limit && envGetHeapSize() >= required_app_heap
            && R_SUCCEEDED(APT_CheckNew3DS(&is_new_3ds)) && is_new_3ds) {
        maximum = new3ds_limit;
    }
#endif
    char message[80];
    int length = snprintf(message, sizeof(message), "UPSTREAM_MEMORY managedLimit=%lu\n",
        (unsigned long)maximum);
    if (length > 0 && length < (int)sizeof(message)) {
        svcOutputDebugString(message, length);
    }
    teavm_initHeap(minimum, maximum);
}

int main(int argc, char** argv) {
    gfxInitDefault();
#ifdef M3DS_TERRAIN_RENDERER
    consoleInit(GFX_BOTTOM, NULL);
#else
    consoleInit(GFX_TOP, NULL);
#endif
    char memory[120];
    int memory_length = snprintf(memory, sizeof(memory),
        "UPSTREAM_MEMORY appHeap=%lu linearHeap=%lu stack=%lu\n",
        (unsigned long)envGetHeapSize(), (unsigned long)envGetLinearHeapSize(),
        (unsigned long)__stacksize__);
    if (memory_length > 0 && memory_length < (int)sizeof(memory)) {
        svcOutputDebugString(memory, memory_length);
    }
    int result = m3ds_teavm_main(argc, argv);
    m3ds_flush_log();
#ifdef M3DS_TERRAIN_RENDERER
    if (!m3ds_terrain_palette_active())
#endif
        puts("START exits");
    while (aptMainLoop()) {
        hidScanInput();
        if (hidKeysDown() & KEY_START) {
            break;
        }
        gspWaitForVBlank();
    }
#ifdef M3DS_TERRAIN_RENDERER
    m3ds_terrain_fini();
#endif
    gfxExit();
    return result;
}
