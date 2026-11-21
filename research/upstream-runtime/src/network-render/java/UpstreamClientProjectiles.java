import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.entities.EntityCollisions;
import mindustry.gen.Groups;
import mindustry.gen.Unit;

/** Runs the stock client weapon and bullet passes for the local Gamma. */
final class UpstreamClientProjectiles {
    static void initialize() {
        Vars.collisions = new EntityCollisions();
    }

    static void load() {
        for (var weapon : UnitTypes.gamma.weapons) weapon.bullet.load();
    }

    static void update(Unit controlled) {
        if (Vars.state.isEditor()) return;
        Groups.updatePooling();
        if (!Groups.bullet.isEmpty()) Groups.bullet.updatePhysics();
        if (controlled != null && controlled.type() == UnitTypes.gamma) {
            for (var mount : controlled.mounts()) mount.weapon.update(controlled, mount);
        }
        if (!Groups.bullet.isEmpty()) {
            Vars.state.teams.updateTeamStats();
            Groups.bullet.update();
            Groups.bullet.collide();
        }
    }

    static int draw(UpstreamTerrainDrawProbe.Viewport viewport) {
        float size = Vars.tilesize;
        float left = (viewport.left - 0.5f) * size, bottom = (viewport.bottom - 0.5f) * size;
        float right = left + UpstreamTerrainDrawProbe.VIEW_WIDTH * size;
        float top = bottom + UpstreamTerrainDrawProbe.VIEW_HEIGHT * size;
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
