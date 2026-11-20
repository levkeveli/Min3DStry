#include <3ds.h>
#include <citro2d.h>
#include <stdio.h>
#include <string.h>

#include "citro2d_backend.h"
#include "platform/hardware_profile.h"
#include "render/scene_2d.h"
#include "sim/parity_scenario.h"

static void m3ds_emit_debug_line(const char *line) {
    if (!line) {
        return;
    }
    size_t len = strlen(line);
    if (len > 0u) {
        (void)svcOutputDebugString(line, (s32)len);
    }
}

int main(int argc, char *argv[]) {
    (void)argc;
    (void)argv;

    gfxInitDefault();
    C3D_Init(C3D_DEFAULT_CMDBUF_SIZE);
    C2D_Init(C2D_DEFAULT_MAX_OBJECTS);
    C2D_Prepare();
    consoleInit(GFX_BOTTOM, NULL);

    M3dsHardwareProfile hw_profile;
    char hw_summary[96] = "Hardware: unknown";
    if (m3ds_hardware_probe(&hw_profile)) {
        m3ds_hardware_profile_format(&hw_profile, hw_summary, sizeof(hw_summary));
    }
    char dbg_line[192];
    snprintf(dbg_line, sizeof(dbg_line), "[MIN3DSTRY] HW: %s\n", hw_summary);
    m3ds_emit_debug_line(dbg_line);

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
        m3ds_emit_debug_line(dbg_line);
    }

    C3D_RenderTarget *top = C2D_CreateScreenTarget(GFX_TOP, GFX_LEFT);

    M3dsBoundedScene scene;
    m3ds_scene_init(&scene, M3DS_TOP_SCREEN_WIDTH, M3DS_TOP_SCREEN_HEIGHT);

    M3dsCitro2dContext c2d_ctx;
    M3dsRenderBackend backend;
    m3ds_citro2d_backend_init(&backend, &c2d_ctx, top);

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

        m3ds_scene_step(&scene, 1u);

        C3D_FrameBegin(C3D_FRAME_SYNCDRAW);
        m3ds_scene_render(&scene, &backend, &stats);
        C3D_FrameEnd(0);

        printf("\x1b[1;1HMin3DStry - Citro2D Prototype\x1b[K");
        printf("\x1b[2;1HTick: %lu (%s)\x1b[K",
               (unsigned long)scene.tick, scene.paused ? "PAUSED" : "RUN");
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
            m3ds_emit_debug_line(dbg_line);
            emitted_frame_diag = true;
        }
    }

    C2D_Fini();
    C3D_Fini();
    gfxExit();
    return 0;
}
