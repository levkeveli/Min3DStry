#include <3ds.h>
#include <stdio.h>
#include <string.h>

#include "platform/msav_world.h"
#include "platform/teavm_world_loader.h"

u32 __stacksize__ = 256 * 1024;
u32 __ctru_linear_heap_size = 4 * 1024 * 1024;

int main(void) {
    gfxInitDefault();
    consoleInit(GFX_TOP, NULL);
    M3dsWorldTiles tiles = {0};
    bool ok = m3ds_teavm_world_load(
        "/3ds/min3dstry/glacier-v13.msav", &tiles);
    char line[224];
    int length = snprintf(line, sizeof(line),
        "EMBED_LOADER ok=%d tiles=%u buildings=%u flat=%016llx"
        " tile=%016llx save=%016llx\n",
        ok ? 1 : 0, (unsigned)(ok ? tiles.count : 0u),
        (unsigned)(ok ? tiles.building_count : 0u),
        (unsigned long long)(ok ? tiles.hash : 0u),
        (unsigned long long)(ok ? tiles.tile_state_hash : 0u),
        (unsigned long long)(ok ? tiles.save_state_hash : 0u));
    if (length > 0 && length < (int)sizeof(line)) {
        svcOutputDebugString(line, length);
    }
    if (ok) {
        m3ds_msav_free_world(&tiles);
    }
    while (aptMainLoop()) {
        hidScanInput();
        if (hidKeysDown() & KEY_START) {
            break;
        }
        gspWaitForVBlank();
    }
    gfxExit();
    return ok ? 0 : 1;
}
