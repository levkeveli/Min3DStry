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
#ifdef M3DS_SMOKE_MISSING
    bool ok = m3ds_teavm_world_load(
        "/3ds/min3dstry/does-not-exist.msav", &tiles);
    bool verified = !ok;
    char line[224];
    int length = snprintf(line, sizeof(line),
        "EMBED_LOADER missing=%d ok=%d verified=%d version=%u\n",
        ok ? 0 : 1, ok ? 1 : 0, verified ? 1 : 0,
        (unsigned)m3ds_teavm_world_loaded_version());
#else
    bool ok = m3ds_teavm_world_load(
        "/3ds/min3dstry/glacier-v13.msav", &tiles);
    bool verified = ok
        && tiles.count == 37500u && tiles.building_count == 2u
        && tiles.hash == UINT64_C(0x783fbd80e415dcd7)
        && tiles.tile_state_hash == UINT64_C(0xe35397041df8e5fe)
        && tiles.save_state_hash == UINT64_C(0x3a375fd4f81c5680);
    char line[256];
    int length = snprintf(line, sizeof(line),
        "EMBED_LOADER missing=0 ok=%d verified=%d version=%u tiles=%u"
        " buildings=%u flat=%016llx tile=%016llx save=%016llx\n",
        ok ? 1 : 0, verified ? 1 : 0,
        (unsigned)m3ds_teavm_world_loaded_version(),
        (unsigned)(ok ? tiles.count : 0u),
        (unsigned)(ok ? tiles.building_count : 0u),
        (unsigned long long)(ok ? tiles.hash : 0u),
        (unsigned long long)(ok ? tiles.tile_state_hash : 0u),
        (unsigned long long)(ok ? tiles.save_state_hash : 0u));
#endif
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
    return verified ? 0 : 1;
}
