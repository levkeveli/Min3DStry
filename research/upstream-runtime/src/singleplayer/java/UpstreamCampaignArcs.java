import arc.graphics.Color;
import arc.math.geom.Bezier;
import arc.math.geom.Vec3;
import arc.struct.FloatSeq;
import mindustry.game.Team;
import mindustry.graphics.Pal;
import mindustry.graphics.g3d.PlanetRenderer;
import mindustry.type.Planet;
import mindustry.type.Sector;

/** Stock look-mode launch, shield and invasion curves for the campaign globe. */
final class UpstreamCampaignArcs {
    private static final int SAMPLES = 25;

    private final Vec3 start = new Vec3(), control = new Vec3(), end = new Vec3();
    private final Vec3 point = new Vec3(), scratch = new Vec3(), normal = new Vec3();
    private final Vec3 previous = new Vec3();
    private final Color color = new Color(), previousColor = new Color();
    private final Color launchTail = new Color(Pal.accent).a(0f);
    private final Color shieldTail = new Color(Team.crux.color).a(0.5f);

    int append(Planet planet, Sector active, Sector hovered, float ticks, FloatSeq vertices) {
        var generator = planet.generator;
        if (generator == null) return 0;
        int arcs = 0;
        if (hovered != null && !hovered.hasBase() && UpstreamCampaignSectorOverlay.canSelect(hovered)) {
            Sector last = active != null && active.planet == planet ? active : null;
            Sector origin = generator.findLaunchCandidate(hovered, last);
            if (origin == null && last != null && !last.isAttacked()) origin = last;
            if (origin != null && origin != hovered) {
                ribbon(planet, origin.tile.v, hovered.tile.v, Pal.accent, launchTail, 1f, 80f, 0.006f, ticks, vertices);
                arcs++;
            }
        }
        for (Sector sector : planet.sectors) {
            Sector shield = sector.shieldTarget;
            if (shield != null && !sector.isCaptured() && !shield.isCaptured()
                && (generator.allowLanding(sector) || generator.allowLanding(shield))) {
                ribbon(planet, sector.tile.v, shield.tile.v, Team.crux.color, shieldTail,
                    0.3f, 110f, 0.006f, ticks, vertices);
                arcs++;
            }
            if (sector.hasBase() && planet.campaignRules.sectorInvasion) {
                for (Sector enemy : sector.near()) {
                    if (enemy.hasEnemyBase() && (enemy.preset == null || !enemy.preset.requireUnlock)) {
                        ribbon(planet, enemy.tile.v, sector.tile.v, Team.crux.color, Color.clear,
                            0.24f, 110f, 0.005f, ticks, vertices);
                        arcs++;
                    }
                }
            }
        }
        return arcs;
    }

    // Matches Planet.drawArcLine's stock triangle strip. The handheld submits it as triangle pairs.
    void ribbon(Planet planet, Vec3 from, Vec3 to, Color head, Color tail, float height, float period,
            float thickness, float ticks, FloatSeq vertices) {
        float outline = PlanetRenderer.outlineRad * planet.radius;
        start.set(to).setLength(outline);
        end.set(from).setLength(outline);
        float separation = (1f - scratch.set(from).nor().dot(point.set(to).nor())) / 2f;
        control.set(to).add(from).scl(0.5f).setLength(planet.radius * (1f + height) + separation * 1.35f);
        normal.set(start).sub(control).crs(scratch.set(control).sub(end)).nor().scl(thickness);
        previous.set(start);
        previousColor.set(head).lerp(tail, ticks / period % 1f);

        for (int sample = 1; sample <= SAMPLES; sample++) {
            float t = (float)sample / SAMPLES;
            Bezier.quadratic(point, t, start, control, end, scratch);
            color.set(head).lerp(tail, (t + ticks / period) % 1f);
            vertex(vertices, previous, previousColor, 1);
            vertex(vertices, previous, previousColor, -1);
            vertex(vertices, point, color, 1);
            vertex(vertices, point, color, 1);
            vertex(vertices, previous, previousColor, -1);
            vertex(vertices, point, color, -1);
            previous.set(point);
            previousColor.set(color);
        }
    }

    private void vertex(FloatSeq vertices, Vec3 at, Color tint, int side) {
        vertices.add(at.x + normal.x * side, at.y + normal.y * side, at.z + normal.z * side, tint.r);
        vertices.add(tint.g, tint.b, tint.a);
    }
}
