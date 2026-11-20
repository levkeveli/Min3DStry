#include <3ds.h>
#include <citro2d.h>
#include <malloc.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "audio/audio_mixer.h"
#include "audio/audio_registry.h"
#include "build-config.h"
#include "citro2d_backend.h"
#include "ndsp_backend.h"
#include "platform/hardware_profile.h"
#include "platform/memory_budget.h"
#include "render/factory_demo.h"
#include "render/scene_2d.h"
#include "sim/parity_scenario.h"

#if M3DS_AUDIO_ROMFS
#include "audio-index.h"
#endif

#define M3DS_MEMORY_RESERVE_BYTES (16U * 1024U * 1024U)
#define M3DS_MEMORY_ARENA_BYTES (1U * 1024U * 1024U)
#define M3DS_MEMORY_PROBE_WORLD_BYTES (200U * 200U * 12U)
#define M3DS_MEMORY_PROBE_TEXTURE_BYTES (8U * 1024U)
#define M3DS_MEMORY_PROBE_TRANSIENT_BYTES (64U * 1024U)

static uint8_t g_memory_arena[M3DS_MEMORY_ARENA_BYTES]
    __attribute__((aligned(M3DS_MEMORY_ALIGNMENT)));

static void m3ds_emit_debug_line(int log_socket, const char *line) {
    size_t len = strlen(line);
    (void)svcOutputDebugString(line, (s32)len);
    if (log_socket >= 0) {
        (void)send(log_socket, line, len, MSG_DONTWAIT);
    }
}

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
    char dbg_line[192];
    snprintf(dbg_line, sizeof(dbg_line), "[MIN3DSTRY] HW: %s\n", hw_summary);
    m3ds_emit_debug_line(log_socket, dbg_line);

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

    C3D_RenderTarget *top = C2D_CreateScreenTarget(GFX_TOP, GFX_LEFT);

    M3dsBoundedScene scene;
    M3dsParityState factory;
    m3ds_factory_demo_init(&scene, &factory);

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

    M3dsDrawStats stats = {0};
    bool emitted_frame_diag = false;
    uint32_t prev_delivered = factory.items_delivered;

    while (aptMainLoop()) {
        hidScanInput();
        u32 kDown = hidKeysDown();
        u32 kHeld = hidKeysHeld();

        if (kDown & KEY_START) {
            break;
        }
        if (kDown & KEY_A) {
            scene.paused = !scene.paused;
            M3dsAudioPlayParams ui_p = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
            ui_p.volume = 0.5f;
            (void)m3ds_audio_mixer_play(&audio_mixer, clip_ui, &ui_p);
        }
        if (kDown & KEY_Y) {
            m3ds_scene_reset_camera(&scene);
            M3dsAudioPlayParams ui_p = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
            ui_p.volume = 0.5f;
            (void)m3ds_audio_mixer_play(&audio_mixer, clip_ui, &ui_p);
        }
        if (kDown & KEY_X) {
            m3ds_audio_mixer_set_muted(
                &audio_mixer, !m3ds_audio_mixer_is_muted(&audio_mixer));
        }

        float pan_dx = 0.0f;
        float pan_dy = 0.0f;
        if (kHeld & KEY_LEFT)  pan_dx -= 2.0f;
        if (kHeld & KEY_RIGHT) pan_dx += 2.0f;
        if (kHeld & KEY_UP)    pan_dy -= 2.0f;
        if (kHeld & KEY_DOWN)  pan_dy += 2.0f;

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

        m3ds_factory_demo_step(&scene, &factory);
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
        printf("\x1b[5;1HCull: %lu  Sw: %lu  Drop: %lu\x1b[K",
               (unsigned long)stats.culled_primitives,
               (unsigned long)stats.state_switches,
               (unsigned long)stats.budget_dropped);
        printf("\x1b[7;1HCPU:     %6.2f%%\x1b[K", C3D_GetProcessingTime() * 6.0f);
        printf("\x1b[8;1HGPU:     %6.2f%%\x1b[K", C3D_GetDrawingTime() * 6.0f);
        printf("\x1b[9;1HCmdBuf:  %6.2f%%\x1b[K", C3D_GetCmdBufUsage() * 100.0f);
        printf("\x1b[10;1HAudio:   %s (%s)\x1b[K",
               m3ds_audio_state_name(m3ds_audio_mixer_state(&audio_mixer)),
               m3ds_audio_mixer_is_muted(&audio_mixer) ? "MUTED" : "ON");
        printf("\x1b[11;1HD-Pad/CPad: Pan  A: Pause\x1b[K");
        printf("\x1b[12;1HY: Reset Cam     START: Exit\x1b[K");
        printf("\x1b[13;1HX: Toggle Mute\x1b[K");
        printf("\x1b[14;1H%s\x1b[K", hw_summary);

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
