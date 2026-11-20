#include <3ds.h>
#include <citro2d.h>
#include <malloc.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "audio/audio_mixer.h"
#include "citro2d_backend.h"
#include "ndsp_backend.h"
#include "platform/hardware_profile.h"
#include "render/factory_demo.h"
#include "render/scene_2d.h"
#include "sim/parity_scenario.h"
#include "ui/touch_build_ui.h"

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
    if (m3ds_hardware_probe(&hw_profile)) {
        m3ds_hardware_profile_format(&hw_profile, hw_summary, sizeof(hw_summary));
    }
    char dbg_line[192];
    snprintf(dbg_line, sizeof(dbg_line), "[MIN3DSTRY] HW: %s\n", hw_summary);
    m3ds_emit_debug_line(log_socket, dbg_line);

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
    C3D_RenderTarget *bottom = C2D_CreateScreenTarget(GFX_BOTTOM, GFX_LEFT);

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

    M3dsCitro2dContext bottom_ctx;
    M3dsRenderBackend bottom_backend;
    m3ds_citro2d_backend_init(&bottom_backend, &bottom_ctx, bottom, NULL);

    M3dsNdspContext ndsp_ctx;
    M3dsAudioBackend audio_backend;
    M3dsAudioMixer audio_mixer;
    m3ds_ndsp_backend_init(&audio_backend, &ndsp_ctx);
    m3ds_audio_mixer_init(&audio_mixer, &audio_backend);

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
    int clip_ui = m3ds_audio_mixer_register_clip(&audio_mixer, &ui_desc);

    char audio_diag[160];
    m3ds_audio_mixer_format_diag(&audio_mixer, audio_diag, sizeof(audio_diag));
    snprintf(dbg_line, sizeof(dbg_line), "[MIN3DSTRY] AUDIO %s\n", audio_diag);
    m3ds_emit_debug_line(log_socket, dbg_line);

    M3dsTouchBuildUi ui;
    m3ds_touch_ui_init(&ui);
    m3ds_touch_ui_sync_scene_cursor(&ui, &factory, &scene);

    char ui_diag[160];
    m3ds_touch_ui_format_diag(&ui, ui_diag, sizeof(ui_diag));
    snprintf(dbg_line, sizeof(dbg_line), "[MIN3DSTRY] UI %s\n", ui_diag);
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
        if (kDown & KEY_L) {
            m3ds_touch_ui_rotate(&ui, -1);
            M3dsAudioPlayParams ui_p = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
            ui_p.volume = 0.5f;
            (void)m3ds_audio_mixer_play(&audio_mixer, clip_ui, &ui_p);
        }
        if (kDown & KEY_R) {
            m3ds_touch_ui_rotate(&ui, 1);
            M3dsAudioPlayParams ui_p = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
            ui_p.volume = 0.5f;
            (void)m3ds_audio_mixer_play(&audio_mixer, clip_ui, &ui_p);
        }
        if (kDown & KEY_B) {
            (void)m3ds_touch_ui_apply_to_factory(&ui, &factory, &scene);
            M3dsAudioPlayParams ui_p = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
            ui_p.volume = 0.5f;
            (void)m3ds_audio_mixer_play(&audio_mixer, clip_ui, &ui_p);
        }

        int8_t cur_dx = 0;
        int8_t cur_dy = 0;
        if (kDown & KEY_LEFT)  cur_dx -= 1;
        if (kDown & KEY_RIGHT) cur_dx += 1;
        if (kDown & KEY_UP)    cur_dy -= 1;
        if (kDown & KEY_DOWN)  cur_dy += 1;
        if (cur_dx != 0 || cur_dy != 0) {
            m3ds_touch_ui_move_cursor(&ui, cur_dx, cur_dy);
        }

        touchPosition touch;
        hidTouchRead(&touch);
        M3dsTouchInput touch_in = {(kHeld & KEY_TOUCH) != 0u, touch.px, touch.py};
        M3dsTouchAction touch_act = m3ds_touch_ui_handle_touch(&ui, &touch_in);
        if (touch_act.kind != M3DS_TOUCH_ACTION_NONE) {
            switch (touch_act.kind) {
                case M3DS_TOUCH_ACTION_PLACE_BLOCK:
                case M3DS_TOUCH_ACTION_REMOVE_BLOCK:
                    (void)m3ds_touch_ui_apply_to_factory(&ui, &factory, &scene);
                    break;
                case M3DS_TOUCH_ACTION_TOGGLE_PAUSE:
                    scene.paused = !scene.paused;
                    break;
                case M3DS_TOUCH_ACTION_TOGGLE_MUTE:
                    m3ds_audio_mixer_set_muted(
                        &audio_mixer, !m3ds_audio_mixer_is_muted(&audio_mixer));
                    break;
                case M3DS_TOUCH_ACTION_RESET_CAMERA:
                    m3ds_scene_reset_camera(&scene);
                    break;
                default:
                    break;
            }
            M3dsAudioPlayParams ui_p = m3ds_audio_default_play_params(M3DS_AUDIO_BUS_SFX);
            ui_p.volume = 0.5f;
            (void)m3ds_audio_mixer_play(&audio_mixer, clip_ui, &ui_p);
        }

        float pan_dx = 0.0f;
        float pan_dy = 0.0f;
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

        m3ds_touch_ui_sync_scene_cursor(&ui, &factory, &scene);
        M3dsTouchUiRenderContext ui_ctx = {
            factory.core_copper,
            scene.tick,
            scene.paused,
            m3ds_audio_mixer_is_muted(&audio_mixer),
            m3ds_touch_ui_can_apply(&ui, &factory),
            hw_profile.is_new_3ds ? "N3DS" : "O3DS",
        };
        M3dsDrawStats bottom_stats = {0};

        C3D_FrameBegin(C3D_FRAME_SYNCDRAW);
        m3ds_scene_render(&scene, &backend, &stats);
        m3ds_touch_ui_render(&ui, &ui_ctx, &bottom_backend, &bottom_stats);
        C3D_FrameEnd(0);

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
