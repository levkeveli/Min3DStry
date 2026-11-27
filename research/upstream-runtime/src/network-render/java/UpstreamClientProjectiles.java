import mindustry.Vars;
import mindustry.gen.Groups;

/** Runs the original client weapon mounts and projectile lifecycle for synced units. */
final class UpstreamClientProjectiles {
    static void load() {
        for (var bullet : Vars.content.bullets()) bullet.load();
    }

    static void update() {
        if (Vars.state.isEditor()) return;
        Groups.updatePooling();
        Groups.bullet.updatePhysics();
        for (var unit : Groups.unit) {
            if (unit.dead()) continue;
            for (var mount : unit.mounts()) mount.weapon.update(unit, mount);
        }
        Groups.bullet.update();
        Groups.bullet.collide();
        UpstreamClientStatus.update();
    }

    static int draw(UpstreamTerrainDrawProbe.Viewport viewport) {
        float size = Vars.tilesize;
        float left = (viewport.left - 0.5f) * size;
        float bottom = (viewport.bottom - 0.5f) * size;
        float right = left + viewport.width() * size;
        float top = bottom + viewport.height() * size;
        int drawn = 0;
        for (var bullet : Groups.bullet) {
            float radius = bullet.clipSize() / 2f;
            if (bullet.x + radius < left || bullet.x - radius > right
                    || bullet.y + radius < bottom || bullet.y - radius > top) continue;
            bullet.draw();
            drawn++;
        }
        return drawn;
    }
}
