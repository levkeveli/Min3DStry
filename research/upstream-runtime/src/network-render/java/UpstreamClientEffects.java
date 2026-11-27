import arc.Core;
import arc.graphics.Camera;
import arc.graphics.g2d.Draw;
import mindustry.Vars;
import mindustry.gen.Groups;
import port.render.UpstreamEffectGate;

/**
 * Advances and draws the original stock EffectState group. Server-called
 * {@code EffectCallPacket}s and locally spawned {@code Fx} entries land in the
 * same group, so bullet impacts, lightning arcs and status visual effects use
 * the pinned v160.8 renderers instead of a port-specific substitute.
 */
final class UpstreamClientEffects {
    private UpstreamClientEffects() {
    }

    static void load() {
        // The graphical client has no desktop Renderer; opt the stock Effect gate in.
        UpstreamEffectGate.enable();
        // Effect.create culls against Core.camera; keep a valid camera before the first frame.
        if (Core.camera == null) {
            Core.camera = new Camera();
            Core.camera.width = 128f;
            Core.camera.height = 96f;
            Core.camera.position.set(0f, 0f);
            Core.camera.update();
        }
    }

    /**
     * Mirrors the viewport into Arc's camera so the stock Effect.create culling
     * keeps effects that are visible and drops the rest.
     */
    static void follow(UpstreamTerrainDrawProbe.Viewport viewport) {
        if (Core.camera == null) Core.camera = new Camera();
        float size = Vars.tilesize;
        Core.camera.width = viewport.width() * size;
        Core.camera.height = viewport.height() * size;
        Core.camera.position.set((viewport.left + viewport.width() / 2f) * size,
            (viewport.bottom + viewport.height() / 2f) * size);
        Core.camera.update();
    }

    static void update() {
        if (Vars.state.isEditor()) return;
        // EffectState.update advances time and removes the entry at its stock lifetime.
        Groups.effect.update();
    }

    static int draw(UpstreamTerrainDrawProbe.Viewport viewport) {
        float size = Vars.tilesize;
        float left = (viewport.left - 0.5f) * size;
        float bottom = (viewport.bottom - 0.5f) * size;
        float right = left + viewport.width() * size;
        float top = bottom + viewport.height() * size;
        int drawn = 0;
        for (var effect : Groups.effect) {
            float radius = effect.clipSize() / 2f;
            if (effect.x() + radius < left || effect.x() - radius > right
                    || effect.y() + radius < bottom || effect.y() - radius > top) continue;
            effect.draw();
            drawn++;
        }
        Draw.reset();
        return drawn;
    }
}
