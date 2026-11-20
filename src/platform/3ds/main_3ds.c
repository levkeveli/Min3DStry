#include <3ds.h>
#include <citro2d.h>
#include <malloc.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include "citro2d_backend.h"
#include "platform/hardware_profile.h"
#include "render/factory_demo.h"
#include "render/scene_2d.h"
#include "sim/parity_scenario.h"

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

    M3dsDrawStats stats = {0};
    bool emitted_frame_diag = false;

    while (aptMainLoop()) {
        hidScanInput();
        u32 kDown = hidKeysDown();
        u32 kHeld = hidKeysHeld();

        if (kDown & KEY_START) {
            break;
        }
        if (kDown & KEY_A) {
            scene.paused = !scene.paused;
        }
        if (kDown & KEY_Y) {
            m3ds_scene_reset_camera(&scene);
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
        printf("\x1b[11;1HD-Pad/CPad: Pan  A: Pause\x1b[K");
        printf("\x1b[12;1HY: Reset Cam     START: Exit\x1b[K");
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
