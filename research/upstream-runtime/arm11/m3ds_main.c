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
    const size_t socket_buffer_size = 0x100000;
    void *socket_buffer = NULL;
    bool socket_ready = false;
    if (argc > 1 && strcmp(argv[1], "m3ds-stream-logs") == 0) {
        // Keep the native option out of the Java positional map/save arguments.
        argv[1] = argv[0];
        ++argv;
        --argc;
        socket_buffer = memalign(0x1000, socket_buffer_size);
        if (socket_buffer && R_SUCCEEDED(socInit(socket_buffer, socket_buffer_size))) {
            socket_ready = true;
            for (int attempt = 0; attempt < 3 && m3ds_log_socket < 0; ++attempt) {
                svcSleepThread(100000000);
                m3ds_log_socket = link3dsConnectToHost(false, false);
            }
        }
    }
    char memory[120];
    int memory_length = snprintf(memory, sizeof(memory),
        "UPSTREAM_MEMORY appHeap=%lu linearHeap=%lu stack=%lu\n",
        (unsigned long)envGetHeapSize(), (unsigned long)envGetLinearHeapSize(),
        (unsigned long)__stacksize__);
    if (memory_length > 0 && memory_length < (int)sizeof(memory)) {
        m3ds_debug_write(memory, (size_t)memory_length);
    }
    int result = m3ds_teavm_main(argc, argv);
    m3ds_flush_log();
    if (m3ds_log_socket >= 0) {
        close(m3ds_log_socket);
        m3ds_log_socket = -1;
    }
    if (socket_ready) socExit();
    free(socket_buffer);
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
