// libctru's 32 KiB default stack is too small for the generated save serializer.
u32 __stacksize__ = 256 * 1024;

#ifdef M3DS_TERRAIN_RENDERER
#include "m3ds_terrain_renderer.h"
u32 __ctru_linear_heap_size = 4 * 1024 * 1024;
#endif

int main(int argc, char** argv) {
    gfxInitDefault();
#ifdef M3DS_TERRAIN_RENDERER
    consoleInit(GFX_BOTTOM, NULL);
#else
    consoleInit(GFX_TOP, NULL);
#endif
    int result = m3ds_teavm_main(argc, argv);
    m3ds_flush_log();
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
