import arc.graphics.Color;
import arc.math.geom.Bezier;
import arc.math.geom.Mat3D;
import arc.math.geom.Vec3;
import arc.struct.FloatSeq;
import mindustry.game.Team;
import mindustry.graphics.Pal;
import mindustry.graphics.g3d.PlanetRenderer;
import mindustry.type.Planet;
import mindustry.type.Sector;

/** Stock look-mode launch, shield, invasion and export curves for the campaign globe. */
final class UpstreamCampaignArcs {
    private static final int SAMPLES = 25;

    private final Vec3 start = new Vec3(), control = new Vec3(), end = new Vec3();
    private final Vec3 point = new Vec3(), scratch = new Vec3(), normal = new Vec3();
    private final Vec3 previous = new Vec3();
    private final Vec3 previousOffset = new Vec3(), pointOffset = new Vec3();
    private final UpstreamCampaignStroke stroke = new UpstreamCampaignStroke();
    private final Color color = new Color(), previousColor = new Color();
    private final Color launchTail = new Color(Pal.accent).a(0f);
    private final Color shieldTail = new Color(Team.crux.color).a(0.5f);

    int append(Planet planet, Sector active, Sector hovered, Sector selected,
            float ticks, Mat3D projection, FloatSeq vertices) {
        var generator = planet.generator;
        if (generator == null) return 0;
        int arcs = 0;
        Sector target = hovered == null ? selected : hovered;
        if (target != null && !target.hasBase() && UpstreamCampaignSectorOverlay.canSelect(target)) {
            Sector last = active != null && active.planet == planet ? active : null;
            Sector origin = generator.findLaunchCandidate(target, last);
            if (origin == null && last != null && !last.isAttacked()) origin = last;
            if (origin != null && origin != target) {
                ribbon(planet, origin.tile.v, target.tile.v, Pal.accent, launchTail, 1f, 80f, 0.006f, ticks, vertices);
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
            if (selected != null && selected != sector && selected.hasBase() && sector.hasBase()) {
                if (sector.info.destination == selected && sector.info.anyExports()) {
                    trade(planet, sector.tile.v, selected.tile.v, Color.gray, ticks, projection, vertices);
                    arcs++;
                }
                if (selected.info.destination == sector && selected.info.anyExports()) {
                    trade(planet, selected.tile.v, sector.tile.v, Pal.place, ticks, projection, vertices);
                    arcs++;
                }
            }
        }
        return arcs;
    }

    // Matches Planet.drawArcLine's stock triangle strip. The handheld submits it as triangle pairs.
    void ribbon(Planet planet, Vec3 from, Vec3 to, Color head, Color tail, float height, float period,
            float thickness, float ticks, FloatSeq vertices) {
        prepareCurve(planet, from, to, height);
        normal.set(start).sub(control).crs(scratch.set(control).sub(end)).nor().scl(thickness);
        previous.set(start);
        previousColor.set(head).lerp(tail, ticks / period % 1f);

        for (int sample = 1; sample <= SAMPLES; sample++) {
            float t = (float)sample / SAMPLES;
            Bezier.quadratic(point, t, start, control, end, scratch);
            color.set(head).lerp(tail, (t + ticks / period) % 1f);
            quad(vertices, normal, normal);
            previous.set(point);
            previousColor.set(color);
        }
    }

    // Planet.drawArc uses a one-pixel GL line strip with the same quadratic control points.
    void trade(Planet planet, Vec3 from, Vec3 to, Color head, float ticks, Mat3D projection, FloatSeq vertices) {
        prepareCurve(planet, from, to, 0.4f);
        stroke.project(projection);
        previous.set(start);
        previousColor.set(head).lerp(Pal.accent, ticks / 90f % 1f);
        for (int sample = 1; sample <= SAMPLES; sample++) {
            float t = (float)sample / SAMPLES;
            Bezier.quadratic(point, t, start, control, end, scratch);
            color.set(head).lerp(Pal.accent, (t + ticks / 90f) % 1f);
            if (stroke.measure(previous, point) >= 0.001f) {
                stroke.offset(previousOffset, stroke.w(previous));
                stroke.offset(pointOffset, stroke.w(point));
                quad(vertices, previousOffset, pointOffset);
            }
            previous.set(point);
            previousColor.set(color);
        }
    }

    private void prepareCurve(Planet planet, Vec3 from, Vec3 to, float height) {
        float outline = PlanetRenderer.outlineRad * planet.radius;
        start.set(to).setLength(outline);
        end.set(from).setLength(outline);
        float separation = (1f - scratch.set(from).nor().dot(point.set(to).nor())) / 2f;
        control.set(to).add(from).scl(0.5f).setLength(planet.radius * (1f + height) + separation * 1.35f);
    }

    private void quad(FloatSeq vertices, Vec3 before, Vec3 after) {
        vertex(vertices, previous, previousColor, before, 1);
        vertex(vertices, previous, previousColor, before, -1);
        vertex(vertices, point, color, after, 1);
        vertex(vertices, point, color, after, 1);
        vertex(vertices, previous, previousColor, before, -1);
        vertex(vertices, point, color, after, -1);
    }

    private static void vertex(FloatSeq vertices, Vec3 at, Color tint, Vec3 normal, int side) {
        vertices.add(at.x + normal.x * side, at.y + normal.y * side, at.z + normal.z * side, tint.r);
        vertices.add(tint.g, tint.b, tint.a);
    }
}
