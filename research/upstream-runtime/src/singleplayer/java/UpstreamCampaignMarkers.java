import arc.graphics.Color;
import arc.math.Mathf;
import arc.math.geom.Vec3;
import arc.struct.FloatSeq;
import arc.struct.IntSeq;
import mindustry.game.Team;
import mindustry.gen.Iconc;
import mindustry.graphics.Pal;
import mindustry.graphics.g3d.PlanetRenderer;
import mindustry.type.Planet;
import mindustry.type.Sector;

/** Projects original sector marker textures onto the same planes used by PlanetDialog. */
final class UpstreamCampaignMarkers {
    static final int GLYPH = -1, PRESET = -2;
    final IntSeq ids = new IntSeq();
    final FloatSeq planes = new FloatSeq();
    final Vec3 origin = new Vec3(), up = new Vec3(), right = new Vec3();
    private final Vec3 eye = new Vec3();
    private final Color tint = new Color();

    void update(UpstreamCampaignGlobe globe, Sector hovered, float ticks) {
        ids.clear();
        planes.clear();
        Planet planet = globe.planet();
        float[] light = globe.lighting;
        eye.set(light[6], light[7], light[8]);
        for (Sector sector : planet.sectors) {
            if (sector == hovered) continue;
            if (sector.isAttacked()) {
                glyph(sector, "warning", Team.sharded.color);
            } else if (sector.preset != null
                && sector.preset.requireUnlock && sector.preset.locked() && sector.preset.techNode != null
                && (sector.preset.techNode.parent == null || sector.preset.techNode.parent.content.unlocked())) {
                glyph(sector, "lock", Color.white);
            } else {
                preferred(sector, Color.white);
            }
        }

        if (hovered != null) {
            boolean attacked = hovered.isAttacked();
            tint.set(attacked ? Pal.remove : Color.white).lerp(Pal.accent, Mathf.absin(ticks, 5f, 1f));
            if (hovered.locked() && !UpstreamCampaignSectorOverlay.canSelect(hovered)
                && hovered.planet.generator != null) {
                if (hovered.preset != null || hovered.planet.allowLaunchToNumbered) glyph(hovered, "lock", tint);
            } else if (attacked) {
                glyph(hovered, "warning", tint);
            } else {
                preferred(hovered, tint);
            }
        }
    }

    private void preferred(Sector sector, Color color) {
        if (sector.info.contentIcon != null) {
            var content = sector.info.contentIcon;
            emit(sector, content.getContentType().ordinal(), content.id, color);
        } else if (sector.info.icon != null) {
            glyph(sector, sector.info.icon, color);
        } else if (sector.preset != null && sector.preset.requireUnlock && sector.preset.unlocked()) {
            emit(sector, PRESET, sector.preset.id, color);
        }
    }

    private void glyph(Sector sector, String name, Color color) {
        emit(sector, GLYPH, Iconc.codes.get(name, 0xf8d4), color);
    }

    private void emit(Sector sector, int kind, int id, Color color) {
        // The stock projection faces away from the planet. Avoid loading hidden textures.
        if (sector.tile.v.dot(eye) <= (PlanetRenderer.outlineRad + 0.01f) * sector.planet.radius) return;
        plane(sector);
        ids.add(kind, id);
        planes.add(origin.x, origin.y, origin.z);
        planes.add(right.x, right.y, right.z);
        planes.add(up.x, up.y, up.z);
        planes.add(color.r, color.g, color.b, color.a);
    }

    void plane(Sector sector) {
        Vec3 radial = sector.tile.v;
        Planet planet = sector.planet;
        float rotation = -planet.getRotation();
        origin.set(radial).setLength((PlanetRenderer.outlineRad + 0.01f) * planet.radius)
            .rotate(Vec3.Y, rotation).add(planet.position);
        sector.plane.project(up.set(radial).add(Vec3.Y)).sub(radial, planet.radius).rotate(Vec3.Y, rotation).nor();
        right.set(up).rotate(Vec3.Y, -rotation).add(radial).rotate(radial, 90f)
            .sub(radial).rotate(Vec3.Y, rotation).nor();
    }
}
