#include <3ds.h>
#include <citro2d.h>
#include <malloc.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include "audio/audio_ambient.h"
#include "audio/audio_events.h"
#include "audio/audio_mixer.h"
#include "audio/audio_registry.h"
#include "build-config.h"
#include "citro2d_backend.h"
#include "ndsp_backend.h"
#include "platform/hardware_profile.h"
#include "platform/memory_budget.h"
#include "platform/msav_inflate.h"
#include "platform/msav_world.h"
#include "platform/save_slot.h"
#include "platform/sector_catalog.h"
#include "platform/sd_paths.h"
#include "platform/settings_store.h"
#include "platform/worker_benchmark.h"
#include "render/factory_demo.h"
#include "render/minimap.h"
#include "render/save_world_preview.h"
#include "render/scene_2d.h"
#include "sim/parity_scenario.h"
#include "ui/pause_menu.h"
#include "ui/pause_overlay.h"
#include "ui/touch_controls.h"

#if M3DS_AUDIO_ROMFS
#include "audio-index.h"
#endif

#define M3DS_MEMORY_RESERVE_BYTES (16U * 1024U * 1024U)
#define M3DS_MEMORY_ARENA_BYTES (1U * 1024U * 1024U)
#define M3DS_MEMORY_PROBE_WORLD_BYTES (200U * 200U * 12U)
#define M3DS_MEMORY_PROBE_TEXTURE_BYTES (8U * 1024U)
#define M3DS_MEMORY_PROBE_TRANSIENT_BYTES (64U * 1024U)
#define M3DS_DEMO_WAVE_TICK 5u
#define M3DS_DEMO_WAVE_X 9u
#define M3DS_DEMO_WAVE_Y 1u
#define M3DS_DEMO_WAVE_COUNT 2u
#define M3DS_DEMO_WALL_TICK 12u
#define M3DS_DEMO_WALL_REMOVE_TICK 15u
#define M3DS_DEMO_WALL_X 10u
#define M3DS_DEMO_WALL_Y 3u
/*
 * Demo invariants: (10,3) starts empty and START_COPPER covers the wall cost
 * (2). If either changes, the block place/break emu-test assertions need to be
 * updated with the demo.
 */
#define M3DS_DEMO_START_COPPER 10u

static uint8_t g_memory_arena[M3DS_MEMORY_ARENA_BYTES]
    __attribute__((aligned(M3DS_MEMORY_ALIGNMENT)));

static void m3ds_emit_debug_line(int log_socket, const char *line) {
    size_t len = strlen(line);
    (void)svcOutputDebugString(line, (s32)len);
    if (log_socket >= 0) {
        (void)send(log_socket, line, len, MSG_DONTWAIT);
    }
}

static void m3ds_play_ui_click(
    M3dsAudioRegistry *events,
    M3dsAudioMixer *mixer,
    int fallback_clip) {
    M3dsAudioPlayParams params =
        m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
    params.volume = 0.5f;
    if (events &&
        m3ds_audio_event_play(events, M3DS_AUDIO_EVENT_UI_BUTTON, &params) !=
            M3DS_AUDIO_INVALID_VOICE) {
        return;
    }
    if (fallback_clip != M3DS_AUDIO_INVALID_CLIP) {
        (void)m3ds_audio_mixer_play(mixer, fallback_clip, &params);
    }
}

static uint32_t m3ds_play_event(
    M3dsAudioRegistry *events,
    M3dsAudioEvent event,
    float volume,
    bool looping,
    int *out_clip) {
    int clip = M3DS_AUDIO_INVALID_CLIP;
    uint32_t voice = M3DS_AUDIO_INVALID_VOICE;
    if (events) {
        clip = m3ds_audio_event_clip(events, event);
        M3dsAudioPlayParams params =
            m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
        params.volume = volume;
        params.looping = looping;
        voice = m3ds_audio_event_play(events, event, &params);
    }
    if (out_clip) {
        *out_clip = clip;
    }
    return voice;
}

static M3dsWorldTiles g_save_tiles;
static M3dsMsavWorld g_save_world;
static bool g_save_world_ready;

int main(int argc, char *argv[]) {
    gfxInitDefault();
    C3D_Init(C3D_DEFAULT_CMDBUF_SIZE);
    C2D_Init(C2D_DEFAULT_MAX_OBJECTS);
    C2D_Prepare();
    consoleInit(GFX_BOTTOM, NULL);

    const u32 soc_buffer_size = 0x100000;
    void *soc_buffer = NULL;
    bool soc_ready = false;
    int log_socket = -1;
    if (argc > 1 && strcmp(argv[1], "m3ds-stream-logs") == 0) {
        soc_buffer = memalign(0x1000, soc_buffer_size);
        if (soc_buffer && R_SUCCEEDED(socInit(soc_buffer, soc_buffer_size))) {
            soc_ready = true;
            // The host starts listening just after the Netloader upload completes.
            for (int attempt = 0; attempt < 3 && log_socket < 0; ++attempt) {
                svcSleepThread(100000000);
                log_socket = link3dsConnectToHost(false, false);
            }
        }
    }

    M3dsHardwareProfile hw_profile;
    char hw_summary[96] = "Hardware: unknown";
    bool hw_ok = m3ds_hardware_probe(&hw_profile);
    if (hw_ok) {
        m3ds_hardware_profile_format(&hw_profile, hw_summary, sizeof(hw_summary));
    }
    char dbg_line[320];
    snprintf(dbg_line, sizeof(dbg_line), "[MIN3DSTRY] HW: %s\n", hw_summary);
    m3ds_emit_debug_line(log_socket, dbg_line);

    char settings_path[96];
    char sector_path[96];
    if (m3ds_sd_settings_path(M3DS_SD_ROOT, settings_path, sizeof(settings_path)) &&
        m3ds_sd_sector_save_path(
            M3DS_SD_ROOT, "serpulo", 170u, sector_path, sizeof(sector_path))) {
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] STORAGE root=%s settings=%s sector=%s\n",
                 M3DS_SD_ROOT, settings_path, sector_path);
    } else {
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] STORAGE status=invalid\n");
    }
    m3ds_emit_debug_line(log_socket, dbg_line);

    (void)mkdir("sdmc:/3ds", 0777);
    (void)mkdir("sdmc:/3ds/min3dstry", 0777);
    size_t probe_size = 0u;
    if (m3ds_storage_probe_roundtrip("sdmc:/3ds/min3dstry", &probe_size)) {
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] STORAGE_ROUNDTRIP status=ok bytes=%u root=sdmc:/3ds/min3dstry\n",
                 (unsigned)probe_size);
    } else {
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] STORAGE_ROUNDTRIP status=failed root=sdmc:/3ds/min3dstry\n");
    }
    m3ds_emit_debug_line(log_socket, dbg_line);

    M3dsSettingsStatus settings_status;
    if (m3ds_settings_store_status(M3DS_SD_ROOT, &settings_status)) {
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] SETTINGS primary=%d backup=%d primary_bytes=%u backup_bytes=%u\n",
                 settings_status.primary_present ? 1 : 0,
                 settings_status.backup_present ? 1 : 0,
                 (unsigned)settings_status.primary_bytes,
                 (unsigned)settings_status.backup_bytes);
    } else {
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] SETTINGS status=invalid\n");
    }
    m3ds_emit_debug_line(log_socket, dbg_line);

    M3dsSaveSlotInfo save_slot = {0};
    char save_slot_path[96];
    if (m3ds_sd_sector_save_path(
            "sdmc:/3ds/min3dstry", "serpulo", 170u, save_slot_path, sizeof(save_slot_path))) {
        (void)m3ds_save_slot_probe(save_slot_path, &save_slot);
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] SAVE_SLOT present=%d valid=%d compressed=%d version=%u bytes=%u path=%s\n",
                 save_slot.present ? 1 : 0, save_slot.valid ? 1 : 0,
                 save_slot.compressed ? 1 : 0, (unsigned)save_slot.version,
                 (unsigned)save_slot.bytes, save_slot_path);
    } else {
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] SAVE_SLOT status=invalid_path\n");
    }
    m3ds_emit_debug_line(log_socket, dbg_line);
    if (save_slot.has_meta) {
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] SAVE_META map=%s wave=%u\n",
                 save_slot.map_name, (unsigned)save_slot.wave);
        m3ds_emit_debug_line(log_socket, dbg_line);
    }

    M3dsSectorSlot sectors[M3DS_SECTOR_CATALOG_MAX];
    size_t sector_count = m3ds_sector_catalog_scan(
        "sdmc:/3ds/min3dstry", sectors, M3DS_SECTOR_CATALOG_MAX);
    snprintf(dbg_line, sizeof(dbg_line),
             "[MIN3DSTRY] SECTOR_CATALOG count=%u\n", (unsigned)sector_count);
    m3ds_emit_debug_line(log_socket, dbg_line);
    for (size_t i = 0; i < sector_count; ++i) {
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] SECTOR_SLOT planet=%.15s id=%u valid=%d compressed=%d bytes=%u",
                 sectors[i].planet, (unsigned)sectors[i].id,
                 sectors[i].valid ? 1 : 0, sectors[i].compressed ? 1 : 0,
                 (unsigned)sectors[i].bytes);
        size_t used = strlen(dbg_line);
        if (sectors[i].has_meta && used + 2u < sizeof(dbg_line)) {
            snprintf(dbg_line + used, sizeof(dbg_line) - used,
                     " map=%.47s wave=%u", sectors[i].map_name,
                     (unsigned)sectors[i].wave);
        }
        used = strlen(dbg_line);
        if (used < sizeof(dbg_line) - 1u) {
            dbg_line[used++] = '\n';
            dbg_line[used] = '\0';
        }
        m3ds_emit_debug_line(log_socket, dbg_line);
    }

    if (sector_count > 0u) {
        const size_t inflate_limit = 8u * 1024u * 1024u;
        uint8_t *raw = NULL;
        uint8_t *inflated = NULL;
        size_t raw_size = 0u;
        size_t inflated_size = 0u;
        bool too_large = false;
        bool read_ok = m3ds_sector_store_read_alloc(
            "sdmc:/3ds/min3dstry", sectors[0].planet, sectors[0].id,
            (void **)&raw, &raw_size);
        bool inflate_ok = read_ok
            && m3ds_msav_inflate_all(
                raw, raw_size, &inflated, &inflated_size, inflate_limit,
                &too_large)
            && inflated_size >= 8u
            && memcmp(inflated, "MSAV", 4) == 0;
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] SECTOR_INFLATE planet=%s id=%u raw=%u bytes=%u limit=%u status=%s\n",
                 sectors[0].planet, (unsigned)sectors[0].id,
                 (unsigned)raw_size, (unsigned)inflated_size,
                 (unsigned)inflate_limit,
                 inflate_ok ? "ok" : too_large ? "too_large" : "failed");
        m3ds_emit_debug_line(log_socket, dbg_line);
        M3dsMsavWorld world;
        bool world_ok = inflate_ok
            && m3ds_msav_parse_world(inflated, inflated_size, &world);
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] SECTOR_WORLD version=%u width=%u height=%u status=%s\n",
                 world_ok ? (unsigned)world.version : 0u,
                 world_ok ? (unsigned)world.width : 0u,
                 world_ok ? (unsigned)world.height : 0u,
                 world_ok ? "ok" : "failed");
        m3ds_emit_debug_line(log_socket, dbg_line);
        if (world_ok) {
            snprintf(dbg_line, sizeof(dbg_line),
                     "[MIN3DSTRY] SECTOR_TILES floors=%u blocks=%u data=%u entities=%u map=%u entity_region=%u hash=%016llx\n",
                     (unsigned)world.floor_tiles, (unsigned)world.block_records,
                     (unsigned)world.data_tiles, (unsigned)world.entity_tiles,
                     (unsigned)(world.width * world.height),
                     (unsigned)world.entity_region_bytes,
                     (unsigned long long)world.map_hash);
            m3ds_emit_debug_line(log_socket, dbg_line);
            snprintf(dbg_line, sizeof(dbg_line),
                     "[MIN3DSTRY] SECTOR_ENTITIES mapping=%u teams=%u team_blocks=%u records=%u partial=%d hash=%016llx\n",
                     (unsigned)world.entity_mapping,
                     (unsigned)world.entity_teams,
                     (unsigned)world.entity_team_blocks,
                     (unsigned)world.entity_records,
                     world.entities_partial ? 1 : 0,
                     (unsigned long long)world.entity_hash);
            m3ds_emit_debug_line(log_socket, dbg_line);
        }
        M3dsWorldTiles tiles = {0};
        bool alloc_ok = world_ok
            && m3ds_msav_alloc_world(inflated, inflated_size, &tiles);
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] SECTOR_ALLOC tiles=%u bytes=%u hash=%016llx status=%s\n",
                 (unsigned)(alloc_ok ? tiles.count : 0u),
                 (unsigned)(alloc_ok ? m3ds_msav_world_bytes(&tiles) : 0u),
                 (unsigned long long)(alloc_ok ? tiles.hash : 0u),
                 alloc_ok ? "ok" : "failed");
        m3ds_emit_debug_line(log_socket, dbg_line);
        size_t linked_buildings = 0u;
        if (alloc_ok) {
            for (size_t i = 0; i < tiles.building_count; ++i) {
                if (tiles.buildings[i].tile) {
                    ++linked_buildings;
                }
            }
        }
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] SECTOR_TILE_STATE tiles=%u buildings=%u linked=%u hash=%016llx status=%s\n",
                 (unsigned)(alloc_ok ? tiles.count : 0u),
                 (unsigned)(alloc_ok ? tiles.building_count : 0u),
                 (unsigned)linked_buildings,
                 (unsigned long long)(alloc_ok ? tiles.tile_state_hash : 0u),
                 alloc_ok ? "ok" : "failed");
        m3ds_emit_debug_line(log_socket, dbg_line);
        uint32_t building_bytes = 0u;
        if (alloc_ok) {
            for (size_t i = 0; i < tiles.building_count; ++i) {
                building_bytes += tiles.buildings[i].payload_size + 1u;
            }
        }
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] SECTOR_BUILDINGS count=%u bytes=%u team=%u rot=%u health=%.0f enabled=%u modules=%u items=%u item0=%u:%u power=%u:%.1f liquid=%u:%u:%.2f extra=%u partial=%d trunc=%d hash=%016llx status=%s\n",
                 (unsigned)(alloc_ok ? tiles.building_count : 0u),
                 (unsigned)(alloc_ok ? building_bytes : 0u),
                 (unsigned)(alloc_ok && tiles.building_count > 0u
                     ? tiles.buildings[0].team : 0u),
                 (unsigned)(alloc_ok && tiles.building_count > 0u
                     ? tiles.buildings[0].rotation : 0u),
                 (double)(alloc_ok && tiles.building_count > 0u
                     ? tiles.buildings[0].health : 0.0f),
                 (unsigned)(alloc_ok && tiles.building_count > 0u
                     ? tiles.buildings[0].enabled : 0u),
                 (unsigned)(alloc_ok && tiles.building_count > 0u
                     ? tiles.buildings[0].modules : 0u),
                 (unsigned)(alloc_ok && tiles.building_count > 0u
                     ? tiles.buildings[0].item_count : 0u),
                 (unsigned)(alloc_ok && tiles.building_count > 0u
                     && tiles.buildings[0].item_count > 0u
                     ? tiles.buildings[0].items[0].item_id : 0u),
                 (unsigned)(alloc_ok && tiles.building_count > 0u
                     && tiles.buildings[0].item_count > 0u
                     ? tiles.buildings[0].items[0].amount : 0u),
                 (unsigned)(alloc_ok && tiles.building_count > 0u
                     ? tiles.buildings[0].power_links : 0u),
                 (double)(alloc_ok && tiles.building_count > 0u
                     ? tiles.buildings[0].power_status : 0.0f),
                 (unsigned)(alloc_ok && tiles.building_count > 0u
                     ? tiles.buildings[0].liquid_count : 0u),
                 (unsigned)(alloc_ok && tiles.building_count > 0u
                     ? tiles.buildings[0].liquid0_id : 0u),
                 (double)(alloc_ok && tiles.building_count > 0u
                     ? tiles.buildings[0].liquid0_amount : 0.0f),
                 (unsigned)(alloc_ok && tiles.building_count > 0u
                     ? tiles.buildings[0].extra_size : 0u),
                 alloc_ok && tiles.building_count > 0u
                     && tiles.buildings[0].partial ? 1 : 0,
                 alloc_ok && tiles.building_count > 0u
                     && tiles.buildings[0].items_truncated ? 1 : 0,
                 (unsigned long long)(alloc_ok ? tiles.building_hash : 0u),
                 alloc_ok ? "ok" : "failed");
        m3ds_emit_debug_line(log_socket, dbg_line);
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] SECTOR_PARITY buildings=%u hash=%016llx status=%s\n",
                 (unsigned)(alloc_ok ? tiles.building_count : 0u),
                 (unsigned long long)(alloc_ok ? tiles.save_state_hash : 0u),
                 alloc_ok ? "ok" : "failed");
        m3ds_emit_debug_line(log_socket, dbg_line);
        if (alloc_ok) {
            g_save_tiles = tiles;
            g_save_world = world;
            g_save_world_ready = true;
        } else {
            m3ds_msav_free_world(&tiles);
        }
        free(raw);
        free(inflated);
    }

    {
        M3dsMemoryBudget memory_budget;
        uint32_t app_mem_bytes = hw_ok ? hw_profile.app_mem_bytes : 0U;
        if (app_mem_bytes == 0U) {
            app_mem_bytes = 64U * 1024U * 1024U;
        }
        size_t available = m3ds_memory_budget_available(
            app_mem_bytes, M3DS_MEMORY_RESERVE_BYTES);
        size_t capacity = available < sizeof(g_memory_arena)
            ? available
            : sizeof(g_memory_arena);
        char mem_diag[256];
        if (capacity > 0U &&
            m3ds_memory_budget_init(
                &memory_budget,
                g_memory_arena,
                capacity,
                capacity / 2U,
                capacity / 4U,
                capacity / 4U) == M3DS_MEMORY_OK) {
            void *world = m3ds_memory_budget_alloc(
                &memory_budget, M3DS_MEMORY_WORLD, M3DS_MEMORY_PROBE_WORLD_BYTES);
            void *textures = m3ds_memory_budget_alloc(
                &memory_budget, M3DS_MEMORY_TEXTURES, M3DS_MEMORY_PROBE_TEXTURE_BYTES);
            void *transient = m3ds_memory_budget_alloc(
                &memory_budget, M3DS_MEMORY_TRANSIENT, M3DS_MEMORY_PROBE_TRANSIENT_BYTES);
            (void)world;
            (void)textures;
            (void)transient;
            int prefix = snprintf(
                mem_diag,
                sizeof(mem_diag),
                "[MIN3DSTRY] MEM app=%luMiB reserve=%luMiB ",
                (unsigned long)(app_mem_bytes / (1024U * 1024U)),
                (unsigned long)(M3DS_MEMORY_RESERVE_BYTES / (1024U * 1024U)));
            if (prefix < 0) {
                prefix = 0;
            }
            if ((size_t)prefix >= sizeof(mem_diag)) {
                prefix = (int)(sizeof(mem_diag) - 1U);
            }
            m3ds_memory_budget_format(
                &memory_budget,
                mem_diag + (size_t)prefix,
                sizeof(mem_diag) - (size_t)prefix);
            m3ds_emit_debug_line(log_socket, mem_diag);
            m3ds_emit_debug_line(log_socket, "\n");
            m3ds_memory_budget_reset(&memory_budget);
        } else {
            m3ds_emit_debug_line(
                log_socket,
                "[MIN3DSTRY] MEM unavailable: application memory region is too small\n");
        }
    }

    M3dsParityRunResult parity_run;
    m3ds_parity_run_canonical(&parity_run);
    if (parity_run.checkpoint_count > 0u) {
        const M3dsParitySnapshot *p_final =
            &parity_run.checkpoints[parity_run.checkpoint_count - 1u];
        snprintf(
            dbg_line,
            sizeof(dbg_line),
            "[MIN3DSTRY] PARITY scenario=%s ticks=%lu hash=0x%08lx copper=%lu delivered=%lu killed=%lu hp=%lu\n",
            parity_run.scenario_id,
            (unsigned long)parity_run.total_ticks,
            (unsigned long)parity_run.trace_hash,
            (unsigned long)p_final->core_copper,
            (unsigned long)p_final->items_delivered,
            (unsigned long)p_final->enemies_killed,
            (unsigned long)p_final->core_hp);
        m3ds_emit_debug_line(log_socket, dbg_line);
    }

    /*
     * Evaluate deterministic worker scheduling on an independent 200x200 grid
     * hash. The simulation tick itself stays single-threaded; this benchmark
     * only checks that the same read-only work produces the same checksum.
     */
    for (uint32_t workers = 2u; workers <= 4u; workers += 2u) {
        M3dsWorkerBenchmarkResult worker_result;
        if (m3ds_worker_benchmark_run(workers, 4u, &worker_result)) {
            snprintf(
                dbg_line,
                sizeof(dbg_line),
                "[MIN3DSTRY] WORKERS workers=%u active=%u chunks=%u items=%u match=%d checksum=0x%08lx serial_us=%llu parallel_us=%llu\n",
                (unsigned)worker_result.workers,
                (unsigned)worker_result.active_workers,
                (unsigned)worker_result.chunks,
                (unsigned)worker_result.items,
                worker_result.match ? 1 : 0,
                (unsigned long)worker_result.serial_checksum,
                (unsigned long long)worker_result.serial_micros,
                (unsigned long long)worker_result.parallel_micros);
            m3ds_emit_debug_line(log_socket, dbg_line);
        }
    }

    /* The PARITY run above is the canonical fixture; the interactive demo
       below applies its own inputs and does not feed that trace hash. */
    C3D_RenderTarget *top = C2D_CreateScreenTarget(GFX_TOP, GFX_LEFT);

    M3dsBoundedScene scene;
    M3dsParityState factory;
    m3ds_factory_demo_init(&scene, &factory);
    m3ds_factory_demo_add_turret(&scene, &factory);
    factory.core_copper = M3DS_DEMO_START_COPPER;
    snprintf(dbg_line, sizeof(dbg_line), "[MIN3DSTRY] VIEW zoom=%.2f lighting=%d\n",
             (double)m3ds_scene_zoom(&scene), m3ds_scene_lighting(&scene) ? 1 : 0);
    m3ds_emit_debug_line(log_socket, dbg_line);

    M3dsCitro2dContext c2d_ctx;
    M3dsRenderBackend backend;
    bool romfs_ready = R_SUCCEEDED(romfsInit());
    C2D_SpriteSheet sprites = romfs_ready ? C2D_SpriteSheetLoad("romfs:/sprites.t3x") : NULL;
    if (sprites) {
        C3D_TexSetFilter(C2D_SpriteSheetGetImage(sprites, 0).tex, GPU_NEAREST, GPU_NEAREST);
    }
    m3ds_emit_debug_line(log_socket, sprites ? "[MIN3DSTRY] ATLAS loaded\n" : "[MIN3DSTRY] ATLAS unavailable\n");
    m3ds_citro2d_backend_init(&backend, &c2d_ctx, top, sprites);

    M3dsNdspContext ndsp_ctx;
    M3dsAudioBackend audio_backend;
    M3dsAudioMixer audio_mixer;
    m3ds_ndsp_backend_init(&audio_backend, &ndsp_ctx);
    m3ds_audio_mixer_init(&audio_mixer, &audio_backend);

    int clip_ui = M3DS_AUDIO_INVALID_CLIP;
    M3dsAudioRegistry *audio_events = NULL;
#if M3DS_AUDIO_ROMFS
    static int16_t romfs_audio_pcm[M3DS_AUDIO_TOTAL_BYTES / 2];
    M3dsAudioRegistry audio_registry;
    M3dsAudioRegistryStatus audio_status = m3ds_audio_registry_load(
        &audio_registry,
        &audio_mixer,
        m3ds_audio_index,
        M3DS_AUDIO_CLIP_COUNT,
        m3ds_audio_read_file_stdio,
        NULL,
        romfs_audio_pcm,
        sizeof(romfs_audio_pcm));
    if (audio_status == M3DS_AUDIO_REGISTRY_OK) {
        audio_events = &audio_registry;
        clip_ui = m3ds_audio_registry_find(&audio_registry, "ui.button");
    }
    snprintf(dbg_line, sizeof(dbg_line),
             "[MIN3DSTRY] AUDIO_ROMFS status=%s clips=%u bytes=%u ui=%d\n",
             m3ds_audio_registry_status_name(audio_status),
             (unsigned)audio_registry.clip_count,
             (unsigned)audio_registry.used_bytes,
             clip_ui);
    m3ds_emit_debug_line(log_socket, dbg_line);
#endif

    static int16_t sfx_deliver_pcm[1764];
    static int16_t sfx_ui_pcm[772];
    m3ds_audio_synth_tone_pcm16(
        sfx_deliver_pcm, 1764u, M3DS_AUDIO_DEFAULT_SAMPLE_RATE, 880u, 9000u);
    m3ds_audio_synth_tone_pcm16(
        sfx_ui_pcm, 772u, M3DS_AUDIO_DEFAULT_SAMPLE_RATE, 520u, 7500u);

    const M3dsAudioClipDesc deliver_desc = {
        sfx_deliver_pcm, 1764u, M3DS_AUDIO_DEFAULT_SAMPLE_RATE, 1u};
    const M3dsAudioClipDesc ui_desc = {
        sfx_ui_pcm, 772u, M3DS_AUDIO_DEFAULT_SAMPLE_RATE, 1u};
    int clip_deliver = m3ds_audio_mixer_register_clip(&audio_mixer, &deliver_desc);
    if (clip_ui == M3DS_AUDIO_INVALID_CLIP) {
        clip_ui = m3ds_audio_mixer_register_clip(&audio_mixer, &ui_desc);
    }

    char audio_diag[160];
    m3ds_audio_mixer_format_diag(&audio_mixer, audio_diag, sizeof(audio_diag));
    snprintf(dbg_line, sizeof(dbg_line), "[MIN3DSTRY] AUDIO %s\n", audio_diag);
    m3ds_emit_debug_line(log_socket, dbg_line);

    M3dsAudioAmbient ambient_loops = {
        M3DS_AUDIO_INVALID_VOICE,
        M3DS_AUDIO_INVALID_VOICE,
    };
    if (audio_events) {
        m3ds_audio_ambient_start(audio_events, 0.2f, &ambient_loops);
        int conveyor_clip = m3ds_audio_event_clip(
            audio_events, M3DS_AUDIO_EVENT_CONVEYOR_LOOP);
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] AUDIO_EVENT conveyor.loop clip=%d voice=%u\n",
                 conveyor_clip,
                 (unsigned int)ambient_loops.conveyor_voice);
        m3ds_emit_debug_line(log_socket, dbg_line);

        int drill_clip = m3ds_audio_event_clip(
            audio_events, M3DS_AUDIO_EVENT_DRILL_LOOP);
        snprintf(dbg_line, sizeof(dbg_line),
                 "[MIN3DSTRY] AUDIO_EVENT drill.loop clip=%d voice=%u\n",
                 drill_clip,
                 (unsigned int)ambient_loops.drill_voice);
        m3ds_emit_debug_line(log_socket, dbg_line);
    }

    M3dsDrawStats stats = {0};
    bool emitted_frame_diag = false;
    uint32_t prev_delivered = factory.items_delivered;
    uint32_t prev_enemies_spawned = factory.enemies_spawned;
    uint32_t prev_shots_fired = factory.shots_fired;
    uint32_t prev_enemies_killed = factory.enemies_killed;
    bool demo_wave_spawned = false;
    bool demo_wall_placed = false;
    bool demo_wall_removed = false;
    bool paused_before_menu = false;
    bool quit_requested = false;
    M3dsTouchAction touch_held = M3DS_TOUCH_ACTION_NONE;
    M3dsPauseMenu pause_menu;
    m3ds_pause_menu_init(&pause_menu);
    snprintf(dbg_line, sizeof(dbg_line),
             "[MIN3DSTRY] MENU state=closed actions=%u\n",
             (unsigned)M3DS_PAUSE_ACTION_COUNT);
    m3ds_emit_debug_line(log_socket, dbg_line);

    while (aptMainLoop()) {
        hidScanInput();
        u32 kDown = hidKeysDown();
        u32 kHeld = hidKeysHeld();

        if (kDown & KEY_TOUCH) {
            touchPosition touch;
            hidTouchRead(&touch);
            bool handled_menu_row = false;
            if (pause_menu.open) {
                int row = m3ds_touch_menu_row((float)touch.py, M3DS_PAUSE_ACTION_COUNT);
                if (row >= 0) {
                    pause_menu.selected = (uint8_t)row;
                    kDown |= KEY_A;
                    handled_menu_row = true;
                    snprintf(dbg_line, sizeof(dbg_line),
                             "[MIN3DSTRY] TOUCH action=menu.row row=%d\n", row);
                    m3ds_emit_debug_line(log_socket, dbg_line);
                }
            }
            if (!handled_menu_row) {
                M3dsTouchAction action =
                    m3ds_touch_action_at((float)touch.px, (float)touch.py);
                if (action != M3DS_TOUCH_ACTION_NONE) {
                    snprintf(dbg_line, sizeof(dbg_line),
                             "[MIN3DSTRY] TOUCH action=%s x=%u y=%u\n",
                             m3ds_touch_action_id(action),
                             (unsigned)touch.px, (unsigned)touch.py);
                    m3ds_emit_debug_line(log_socket, dbg_line);
                }
                if (action == M3DS_TOUCH_ACTION_MENU) {
                    kDown |= KEY_SELECT;
                } else if (action == M3DS_TOUCH_ACTION_ZOOM_IN) {
                    kDown |= KEY_L;
                } else if (action == M3DS_TOUCH_ACTION_ZOOM_OUT) {
                    kDown |= KEY_R;
                } else if (m3ds_touch_action_is_pan(action)) {
                    touch_held = action;
                }
            }
        }
        if (!(kHeld & KEY_TOUCH)) {
            touch_held = M3DS_TOUCH_ACTION_NONE;
        }

        if (kDown & KEY_START) {
            break;
        }
        if (kDown & KEY_SELECT) {
            if (pause_menu.open) {
                m3ds_pause_menu_close(&pause_menu);
                scene.paused = paused_before_menu;
                if (!scene.paused) {
                    m3ds_audio_ambient_start(audio_events, 0.2f, &ambient_loops);
                }
            } else {
                paused_before_menu = scene.paused;
                scene.paused = true;
                m3ds_audio_ambient_stop(&audio_mixer, &ambient_loops);
                m3ds_pause_menu_open(&pause_menu);
            }
            snprintf(dbg_line, sizeof(dbg_line),
                     "[MIN3DSTRY] MENU state=%s selected=%s\n",
                     pause_menu.open ? "open" : "closed",
                     m3ds_pause_menu_action_id(m3ds_pause_menu_action(&pause_menu)));
            m3ds_emit_debug_line(log_socket, dbg_line);
            m3ds_play_ui_click(audio_events, &audio_mixer, clip_ui);
        }

        if (pause_menu.open) {
            if (kDown & KEY_UP) {
                (void)m3ds_pause_menu_move(&pause_menu, -1);
            }
            if (kDown & KEY_DOWN) {
                (void)m3ds_pause_menu_move(&pause_menu, 1);
            }
            if (kDown & KEY_A) {
                M3dsPauseAction action = m3ds_pause_menu_action(&pause_menu);
                snprintf(dbg_line, sizeof(dbg_line),
                         "[MIN3DSTRY] MENU action=%s\n",
                         m3ds_pause_menu_action_id(action));
                m3ds_emit_debug_line(log_socket, dbg_line);
                if (action == M3DS_PAUSE_ACTION_RESUME) {
                    m3ds_pause_menu_close(&pause_menu);
                    scene.paused = false;
                    m3ds_audio_ambient_start(audio_events, 0.2f, &ambient_loops);
                } else if (action == M3DS_PAUSE_ACTION_RESET_CAMERA) {
                    m3ds_scene_reset_camera(&scene);
                } else if (action == M3DS_PAUSE_ACTION_QUIT) {
                    quit_requested = true;
                }
                m3ds_play_ui_click(audio_events, &audio_mixer, clip_ui);
            }
            if (quit_requested) {
                break;
            }
        } else {
            if (kDown & KEY_A) {
                bool pause = !scene.paused;
                if (pause) {
                    m3ds_audio_ambient_stop(&audio_mixer, &ambient_loops);
                } else {
                    m3ds_audio_ambient_start(audio_events, 0.2f, &ambient_loops);
                }
                scene.paused = pause;
                m3ds_play_ui_click(audio_events, &audio_mixer, clip_ui);
            }
            if (kDown & KEY_Y) {
                m3ds_scene_reset_camera(&scene);
                m3ds_play_ui_click(audio_events, &audio_mixer, clip_ui);
            }
            if (kDown & KEY_X) {
                m3ds_audio_mixer_set_muted(
                    &audio_mixer, !m3ds_audio_mixer_is_muted(&audio_mixer));
            }
            if (kDown & KEY_L) {
                m3ds_scene_zoom_by(&scene, 0.25f);
                m3ds_play_ui_click(audio_events, &audio_mixer, clip_ui);
            }
            if (kDown & KEY_R) {
                m3ds_scene_zoom_by(&scene, -0.25f);
                m3ds_play_ui_click(audio_events, &audio_mixer, clip_ui);
            }
            if (kDown & KEY_B) {
                m3ds_scene_set_lighting(
                    &scene, !m3ds_scene_lighting(&scene));
                m3ds_play_ui_click(audio_events, &audio_mixer, clip_ui);
            }

            float pan_dx = 0.0f;
            float pan_dy = 0.0f;
            if (kHeld & KEY_LEFT)  pan_dx -= 2.0f;
            if (kHeld & KEY_RIGHT) pan_dx += 2.0f;
            if (kHeld & KEY_UP)    pan_dy -= 2.0f;
            if (kHeld & KEY_DOWN)  pan_dy += 2.0f;
            if (touch_held == M3DS_TOUCH_ACTION_CAMERA_LEFT)  pan_dx -= 2.0f;
            if (touch_held == M3DS_TOUCH_ACTION_CAMERA_RIGHT) pan_dx += 2.0f;
            if (touch_held == M3DS_TOUCH_ACTION_CAMERA_UP)    pan_dy -= 2.0f;
            if (touch_held == M3DS_TOUCH_ACTION_CAMERA_DOWN)  pan_dy += 2.0f;

            circlePosition cpad;
            hidCircleRead(&cpad);
            if (cpad.dx > 20 || cpad.dx < -20) {
                pan_dx += (float)cpad.dx / 40.0f;
            }
            if (cpad.dy > 20 || cpad.dy < -20) {
                pan_dy -= (float)cpad.dy / 40.0f;
            }

            if (pan_dx != 0.0f || pan_dy != 0.0f) {
                m3ds_scene_pan(&scene, pan_dx, pan_dy);
            }
        }

        if (!scene.paused && !demo_wall_placed &&
            factory.tick >= M3DS_DEMO_WALL_TICK) {
            M3dsParityInput wall_input;
            memset(&wall_input, 0, sizeof(wall_input));
            wall_input.tick = factory.tick;
            wall_input.kind = M3DS_PINPUT_PLACE_BLOCK;
            wall_input.x = M3DS_DEMO_WALL_X;
            wall_input.y = M3DS_DEMO_WALL_Y;
            wall_input.block = M3DS_PBLOCK_WALL;
            demo_wall_placed = m3ds_parity_apply_input(&factory, &wall_input);
            if (demo_wall_placed) {
                int place_clip = M3DS_AUDIO_INVALID_CLIP;
                uint32_t place_voice = m3ds_play_event(
                    audio_events, M3DS_AUDIO_EVENT_BLOCK_PLACE, 0.5f, false,
                    &place_clip);
                snprintf(dbg_line, sizeof(dbg_line),
                         "[MIN3DSTRY] AUDIO_EVENT block.place clip=%d voice=%u\n",
                         place_clip,
                         (unsigned int)place_voice);
                m3ds_emit_debug_line(log_socket, dbg_line);
            }
        }
        if (!scene.paused && demo_wall_placed && !demo_wall_removed &&
            factory.tick >= M3DS_DEMO_WALL_REMOVE_TICK) {
            M3dsParityInput wall_input;
            memset(&wall_input, 0, sizeof(wall_input));
            wall_input.tick = factory.tick;
            wall_input.kind = M3DS_PINPUT_REMOVE_BLOCK;
            wall_input.x = M3DS_DEMO_WALL_X;
            wall_input.y = M3DS_DEMO_WALL_Y;
            demo_wall_removed = m3ds_parity_apply_input(&factory, &wall_input);
            if (demo_wall_removed) {
                int break_clip = M3DS_AUDIO_INVALID_CLIP;
                uint32_t break_voice = m3ds_play_event(
                    audio_events, M3DS_AUDIO_EVENT_BLOCK_BREAK, 0.5f, false,
                    &break_clip);
                snprintf(dbg_line, sizeof(dbg_line),
                         "[MIN3DSTRY] AUDIO_EVENT block.break clip=%d voice=%u\n",
                         break_clip,
                         (unsigned int)break_voice);
                m3ds_emit_debug_line(log_socket, dbg_line);
            }
        }
        if (!scene.paused && !demo_wave_spawned &&
            factory.tick >= M3DS_DEMO_WAVE_TICK) {
            M3dsParityInput wave_input;
            memset(&wave_input, 0, sizeof(wave_input));
            wave_input.tick = factory.tick;
            wave_input.kind = M3DS_PINPUT_SPAWN_WAVE;
            wave_input.x = M3DS_DEMO_WAVE_X;
            wave_input.y = M3DS_DEMO_WAVE_Y;
            wave_input.block = M3DS_DEMO_WAVE_COUNT;
            demo_wave_spawned = m3ds_parity_apply_input(&factory, &wave_input);
        }
        m3ds_factory_demo_step(&scene, &factory);
        uint32_t spawned_now = factory.enemies_spawned;
        if (spawned_now > prev_enemies_spawned) {
            int wave_clip = M3DS_AUDIO_INVALID_CLIP;
            uint32_t wave_voice = m3ds_play_event(
                audio_events, M3DS_AUDIO_EVENT_WAVE_SPAWN, 0.7f, false, &wave_clip);
            snprintf(dbg_line, sizeof(dbg_line),
                     "[MIN3DSTRY] AUDIO_EVENT wave.spawn spawned=%lu clip=%d voice=%u\n",
                     (unsigned long)spawned_now,
                     wave_clip,
                     (unsigned int)wave_voice);
            m3ds_emit_debug_line(log_socket, dbg_line);
        }
        prev_enemies_spawned = spawned_now;

        if (factory.shots_fired > prev_shots_fired) {
            int shoot_clip = M3DS_AUDIO_INVALID_CLIP;
            uint32_t shoot_voice = m3ds_play_event(
                audio_events, M3DS_AUDIO_EVENT_SHOOT_DUO, 0.55f, false, &shoot_clip);
            snprintf(dbg_line, sizeof(dbg_line),
                     "[MIN3DSTRY] AUDIO_EVENT shoot.duo fired=%lu clip=%d voice=%u\n",
                     (unsigned long)factory.shots_fired,
                     shoot_clip,
                     (unsigned int)shoot_voice);
            m3ds_emit_debug_line(log_socket, dbg_line);
        }
        prev_shots_fired = factory.shots_fired;

        if (factory.enemies_killed > prev_enemies_killed) {
            int kill_clip = M3DS_AUDIO_INVALID_CLIP;
            uint32_t kill_voice = m3ds_play_event(
                audio_events, M3DS_AUDIO_EVENT_ENEMY_KILL, 0.7f, false, &kill_clip);
            snprintf(dbg_line, sizeof(dbg_line),
                     "[MIN3DSTRY] AUDIO_EVENT enemy.kill killed=%lu clip=%d voice=%u\n",
                     (unsigned long)factory.enemies_killed,
                     kill_clip,
                     (unsigned int)kill_voice);
            m3ds_emit_debug_line(log_socket, dbg_line);
        }
        prev_enemies_killed = factory.enemies_killed;
        if (factory.items_delivered > prev_delivered) {
            M3dsAudioPlayParams deliver_p =
                m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
            deliver_p.volume = 0.65f;
            (void)m3ds_audio_mixer_play(&audio_mixer, clip_deliver, &deliver_p);
            prev_delivered = factory.items_delivered;
        }
        m3ds_audio_mixer_update(&audio_mixer);
        if (!scene.paused && factory.tick == 60u) {
            snprintf(dbg_line, sizeof(dbg_line),
                     "[MIN3DSTRY] FACTORY tick=%lu delivered=%lu inventory=%lu hash=0x%08lx\n",
                     (unsigned long)factory.tick, (unsigned long)factory.items_delivered,
                     (unsigned long)factory.core_copper, (unsigned long)m3ds_parity_hash_state(&factory));
            m3ds_emit_debug_line(log_socket, dbg_line);
        }

        C3D_FrameBegin(C3D_FRAME_SYNCDRAW);
        m3ds_scene_render(&scene, &backend, &stats);
        (void)m3ds_minimap_render(
            &scene, &factory, &backend, M3DS_TOP_SCREEN_WIDTH - 104.0f, 8.0f,
            96.0f, 64.0f);
        if (g_save_world_ready) {
            (void)m3ds_save_world_preview_render(
                &g_save_tiles, &backend, 8.0f, 8.0f, 96.0f, 64.0f);
        }
        M3dsPauseOverlayStats overlay_stats;
        (void)m3ds_pause_overlay_render(
            &backend, &scene.viewport, scene.paused, &overlay_stats);
        C3D_FrameEnd(0);

        printf("\x1b[1;1HMin3DStry - Factory Prototype\x1b[K");
        printf("\x1b[2;1HTick: %lu (%s)\x1b[K",
               (unsigned long)scene.tick, scene.paused ? "PAUSED" : "RUN");
        printf("\x1b[6;1HCore inventory: %lu\x1b[K", (unsigned long)factory.core_copper);
        printf("\x1b[3;1HCam:  (%5.1f, %5.1f)\x1b[K",
               scene.camera_x, scene.camera_y);
        printf("\x1b[4;1HDraw: R:%lu L:%lu T:%lu C:%lu\x1b[K",
               (unsigned long)stats.drawn_rects,
               (unsigned long)stats.drawn_lines,
               (unsigned long)stats.drawn_triangles,
               (unsigned long)stats.drawn_circles);
        printf("\x1b[5;1HCull:%lu Sw:%lu Drop:%lu E:%lu L:%lu\x1b[K",
               (unsigned long)stats.culled_primitives,
               (unsigned long)stats.state_switches,
               (unsigned long)stats.budget_dropped,
               (unsigned long)stats.effects_dropped,
               (unsigned long)stats.light_circles);
        printf("\x1b[7;1HCPU:     %6.2f%%\x1b[K", C3D_GetProcessingTime() * 6.0f);
        printf("\x1b[8;1HGPU:     %6.2f%%\x1b[K", C3D_GetDrawingTime() * 6.0f);
        printf("\x1b[9;1HCmdBuf:  %6.2f%%\x1b[K", C3D_GetCmdBufUsage() * 100.0f);
        printf("\x1b[10;1HAudio:   %s (%s)\x1b[K",
               m3ds_audio_state_name(m3ds_audio_mixer_state(&audio_mixer)),
               m3ds_audio_mixer_is_muted(&audio_mixer) ? "MUTED" : "ON");
        printf("\x1b[11;1HD-Pad/CPad: Pan  A: Pause  L/R: Zoom\x1b[K");
        printf("\x1b[12;1HY: Reset Cam  SELECT: Menu  START: Exit\x1b[K");
        printf("\x1b[13;1HX:Mute B:Light Zoom:%.2f\x1b[K",
               (double)m3ds_scene_zoom(&scene));
        printf("\x1b[14;1H%s\x1b[K", hw_summary);
        printf("\x1b[19;1HTouch: pad / zoom / menu\x1b[K");
        if (save_slot.present && save_slot.valid) {
            printf("\x1b[20;1HSave: v%u %uB\x1b[K",
                   (unsigned)save_slot.version, (unsigned)save_slot.bytes);
        } else if (save_slot.present && save_slot.compressed) {
            printf("\x1b[20;1HSave: zlib %uB\x1b[K", (unsigned)save_slot.bytes);
        } else if (save_slot.present) {
            printf("\x1b[20;1HSave: invalid %uB\x1b[K", (unsigned)save_slot.bytes);
        } else {
            printf("\x1b[20;1HSave: none\x1b[K");
        }
        printf("\x1b[21;1HSectors: %u\x1b[K", (unsigned)sector_count);
        for (size_t i = 0; i < 4u; ++i) {
            char label[64];
            if (i < sector_count && m3ds_sector_catalog_label(
                    &sectors[i], label, sizeof(label))) {
                printf("\x1b[%u;1H %s\x1b[K", (unsigned)(22u + i), label);
            } else {
                printf("\x1b[%u;1H\x1b[K", (unsigned)(22u + i));
            }
        }
        if (save_slot.has_meta) {
            printf("\x1b[26;1HMap: %s wave=%u\x1b[K",
                   save_slot.map_name, (unsigned)save_slot.wave);
        } else {
            printf("\x1b[26;1H\x1b[K");
        }
        if (g_save_world_ready) {
            printf("\x1b[27;1HWorld: %ux%u build=%u\x1b[K",
                   (unsigned)g_save_world.width,
                   (unsigned)g_save_world.height,
                   (unsigned)g_save_tiles.building_count);
        } else {
            printf("\x1b[27;1H\x1b[K");
        }
        if (pause_menu.open) {
            printf("\x1b[15;1H=== PAUSED ===  Up/Down + A\x1b[K");
            for (uint8_t i = 0u; i < M3DS_PAUSE_ACTION_COUNT; ++i) {
                printf("\x1b[%u;1H%c %s\x1b[K",
                       (unsigned)(16u + i),
                       i == pause_menu.selected ? '>' : ' ',
                       m3ds_pause_menu_action_label((M3dsPauseAction)i));
            }
        } else {
            for (unsigned line = 15u; line <= 18u; ++line) {
                printf("\x1b[%u;1H\x1b[K", line);
            }
        }

        if (!emitted_frame_diag && scene.tick >= 30u) {
            snprintf(
                dbg_line,
                sizeof(dbg_line),
                "[MIN3DSTRY] FRAME tick=%lu paused=%d cam=(%.1f,%.1f) "
                "draw=R:%lu,L:%lu,T:%lu,C:%lu cull=%lu sw=%lu drop=%lu "
                "cpu=%.2f%% gpu=%.2f%% cmdbuf=%.2f%%\n",
                (unsigned long)scene.tick,
                scene.paused ? 1 : 0,
                (double)scene.camera_x,
                (double)scene.camera_y,
                (unsigned long)stats.drawn_rects,
                (unsigned long)stats.drawn_lines,
                (unsigned long)stats.drawn_triangles,
                (unsigned long)stats.drawn_circles,
                (unsigned long)stats.culled_primitives,
                (unsigned long)stats.state_switches,
                (unsigned long)stats.budget_dropped,
                (double)(C3D_GetProcessingTime() * 6.0f),
                (double)(C3D_GetDrawingTime() * 6.0f),
                (double)(C3D_GetCmdBufUsage() * 100.0f));
            m3ds_emit_debug_line(log_socket, dbg_line);
            emitted_frame_diag = true;
        }
    }

    m3ds_audio_mixer_shutdown(&audio_mixer);
    if (log_socket >= 0) {
        close(log_socket);
    }
    if (soc_ready) {
        socExit();
    }
    free(soc_buffer);
    if (sprites) {
        C2D_SpriteSheetFree(sprites);
    }
    if (romfs_ready) {
        romfsExit();
    }
    C2D_Fini();
    C3D_Fini();
    gfxExit();
    return 0;
}
