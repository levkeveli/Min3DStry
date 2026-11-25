import arc.graphics.Color;
import arc.math.Mathf;
import arc.math.geom.Vec3;
import arc.struct.FloatSeq;
import mindustry.game.Team;
import mindustry.graphics.g3d.PlanetRenderer;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.type.SectorPreset;

/** PlanetDialog's look-mode sector surfaces, emitted for the handheld GPU batch. */
final class UpstreamCampaignSectorOverlay {
    static final int FLOATS_PER_VERTEX = 7;
    final FloatSeq vertices = new FloatSeq();
    private final Color shade = new Color();
    private final Vec3 center = new Vec3();
    private final Vec3 first = new Vec3(), next = new Vec3();
    private final Vec3 insetFirst = new Vec3(), insetNext = new Vec3();
    private final Vec3 barFirst = new Vec3(), barNext = new Vec3();
    private final Vec3 innerFirst = new Vec3(), innerNext = new Vec3();
    int shadowed, outlined, marked;

    void update(Planet planet, Sector current, Sector hovered, float ticks) {
        vertices.clear();
        shadowed = outlined = marked = 0;
        for (Sector sector : planet.sectors) {
            if (!canSelect(sector) && !sector.unlocked()) {
                fill(sector, PlanetRenderer.shadowColor, -0.001f);
                shadowed++;
                continue;
            }
            if (sector.hasBase()) {
                shade.set(Team.sharded.color).lerp(Team.crux.color, sector.hasEnemyBase() ? 0.5f : 0f);
            } else if (sector.preset != null && sector.preset.requireUnlock) {
                shade.set(Color.gray);
                if (sector.preset.unlocked()) {
                    shade.set(Team.derelict.color).lerp(Color.white, Mathf.absin(ticks, 10f, 1f));
                }
            } else if (sector.hasEnemyBase()) {
                shade.set(Team.crux.color);
            } else {
                continue;
            }
            boolean showHidden = sector.preset != null && sector.preset.showHidden && !sector.isCaptured();
            outline(sector, shade.mul(0.8f).a(1f), 0.026f, -0.001f, showHidden);
            outlined++;
            if (showHidden) marked++;
        }
        if (current != null && current.planet == planet && current.isBeingPlayed()) {
            fill(current, PlanetRenderer.hoverColor, -0.001f);
        }
        if (hovered != null) {
            fill(hovered, PlanetRenderer.hoverColor, -0.003f);
            borders(hovered, ticks);
        }
    }

    private static boolean canSelect(Sector sector) {
        if (sector.planet.generator == null || sector.isShielded()) return false;
        if (sector.hasBase() || sector.id == sector.planet.startSector) return true;
        SectorPreset preset = sector.preset;
        if (preset == null || !preset.requireUnlock) return sector.planet.generator.allowLanding(sector);
        var node = preset.techNode;
        if (preset.unlocked() || node == null || node.parent == null) return true;
        var prerequisite = node.parent.content;
        return prerequisite.unlocked()
            && (!(prerequisite instanceof SectorPreset parent) || parent.sector.hasBase());
    }

    // Same coordinates as Planet.fill, without mutating the shared PlanetGrid.
    void fill(Sector sector, Color color, float offset) {
        float radius = PlanetRenderer.outlineRad * sector.planet.radius + offset;
        var corners = sector.tile.corners;
        center.set(sector.tile.v).setLength(radius);
        for (int i = 0; i < corners.length; i++) {
            first.set(corners[i].v).setLength(radius);
            next.set(corners[(i + 1) % corners.length].v).setLength(radius);
            triangle(first, next, center, color);
        }
    }

    // Planet.drawSelection, followed by the inset bars from PlanetRenderer.drawSpecialSelection.
    void outline(Sector sector, Color color, float stroke, float offset, boolean special) {
        float radius = (PlanetRenderer.outlineRad + offset) * sector.planet.radius;
        var corners = sector.tile.corners;
        center.set(sector.tile.v).scl(radius);
        for (int i = 0; i < corners.length; i++) {
            edge(sector, i, radius);
            inset(insetFirst, first, stroke);
            inset(insetNext, next, stroke);
            quad(first, next, insetNext, insetFirst, color);
        }
        if (!special) return;
        for (int i = 0; i < corners.length; i += 2) {
            edge(sector, i, radius);
            inset(insetFirst, first, stroke);
            inset(insetNext, next, stroke);
            barFirst.set(insetFirst).lerp(insetNext, 0.1f);
            barNext.set(insetFirst).lerp(insetNext, 0.9f);
            inset(innerFirst, barFirst, stroke);
            inset(innerNext, barNext, stroke);
            quad(barFirst, barNext, innerNext, innerFirst, color);
        }
    }

    void borders(Sector sector, float ticks) {
        Color base = PlanetRenderer.borderColor;
        Color color = shade.set(base).a(base.a + 0.3f + Mathf.absin(ticks, 5f, 0.3f));
        float radius = sector.planet.radius;
        float top = PlanetRenderer.outlineRad * radius + 0.001f;
        var corners = sector.tile.corners;
        for (int i = 0; i < corners.length; i++) {
            Vec3 corner = corners[i].v, following = corners[(i + 1) % corners.length].v;
            first.set(corner).setLength(top);
            next.set(following).setLength(top);
            innerFirst.set(corner).setLength(radius);
            innerNext.set(following).setLength(radius);
            // Planet.drawBorders emits both windings for its culled GL batch.
            triangle(first, next, innerFirst, color);
            triangle(first, innerFirst, next, color);
            triangle(next, innerNext, innerFirst, color);
            triangle(next, innerFirst, innerNext, color);
        }
    }

    private void edge(Sector sector, int index, float radius) {
        var corners = sector.tile.corners;
        first.set(corners[index].v).scl(radius);
        next.set(corners[(index + 1) % corners.length].v).scl(radius);
    }

    private void inset(Vec3 out, Vec3 point, float width) {
        out.set(point).sub(center).setLength(point.dst(center) - width).add(center);
    }

    private void quad(Vec3 a, Vec3 b, Vec3 c, Vec3 d, Color color) {
        triangle(a, b, c, color);
        triangle(a, c, d, color);
    }

    private void triangle(Vec3 a, Vec3 b, Vec3 c, Color color) {
        vertex(a, color);
        vertex(b, color);
        vertex(c, color);
    }

    private void vertex(Vec3 point, Color color) {
        vertices.add(point.x, point.y, point.z, color.r);
        vertices.add(color.g, color.b, color.a);
    }
}
