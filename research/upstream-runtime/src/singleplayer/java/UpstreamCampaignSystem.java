import arc.files.Fi;
import arc.graphics.g3d.Camera3D;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Mat3D;
import arc.math.geom.Vec3;
import arc.struct.FloatSeq;
import arc.struct.Seq;
import arc.util.Time;
import java.io.DataInputStream;
import java.io.IOException;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.graphics.Pal;
import mindustry.type.Planet;
import mindustry.type.Sector;

/** Draws the stock visible hierarchy in a campaign overview sized for the top screen. */
final class UpstreamCampaignSystem {
    private static final int WIDTH = 400, HEIGHT = 240;

    @FunctionalInterface
    interface BodyDrawer {
        boolean draw(int asset, float[] matrix, float[] lighting);
    }

    final Camera3D camera = new Camera3D();
    final Seq<Planet> bodies = new Seq<>(), selectable = new Seq<>();
    final FloatSeq orbits = new FloatSeq();
    private final Planet star;
    private final Mat3D projection = new Mat3D(), transform = new Mat3D();
    private final Vec3 view = new Vec3(0.15f, 0.85f, 0.5f).nor();
    private final Vec3 light = new Vec3(), eye = new Vec3();
    private final Vec3 selectedPoint = new Vec3();
    private final Vec3 before = new Vec3(), after = new Vec3();
    private final Vec3 beforeOffset = new Vec3(), afterOffset = new Vec3();
    private final UpstreamCampaignStroke stroke = new UpstreamCampaignStroke();
    private final float[] lighting = new float[9];
    private final long openedAt = System.nanoTime();
    private final float initialTicks = Time.globalTime;
    private float[] serpuloClouds, erekirClouds;
    private float zoom = 1f;
    private int selection;

    UpstreamCampaignSystem(Planet current) {
        star = current.solarSystem;
        collect(star);
        selection = Math.max(0, selectable.indexOf(current, true));
        camera.resize(WIDTH, HEIGHT);
        camera.fov = 60f;
        camera.near = Math.max(0.1f, star.totalRadius * 0.2f);
        camera.far = Math.max(150f, star.totalRadius * 5f);
    }

    private void collect(Planet planet) {
        if (!planet.visible()) return;
        bodies.add(planet);
        // The normal campaign look mode in the pinned PlanetDialog.
        if (!planet.removed && (planet.alwaysUnlocked && planet.isLandable()
                || planet.sectors.contains(Sector::hasBase))) {
            selectable.add(planet);
        }
        for (Planet child : planet.children) collect(child);
    }

    void loadClouds(Fi assets) throws IOException {
        serpuloClouds = readCloudSpeeds(assets.child("serpulo.m3pc"));
        erekirClouds = readCloudSpeeds(assets.child("erekir.m3pc"));
    }

    static float[] readCloudSpeeds(Fi asset) throws IOException {
        if (!asset.exists()) throw new IOException("Missing stock cloud manifest: " + asset.name());
        try (var stream = new DataInputStream(asset.read())) {
            if (stream.readInt() != 0x4d335043 || stream.readInt() != 1 || stream.readInt() != 2) {
                throw new IOException("Invalid stock cloud manifest: " + asset.name());
            }
            float[] speeds = {stream.readFloat(), stream.readFloat()};
            if (stream.read() != -1 || !Float.isFinite(speeds[0]) || !Float.isFinite(speeds[1])) {
                throw new IOException("Invalid stock cloud speeds: " + asset.name());
            }
            return speeds;
        }
    }

    Planet selected() { return selectable.get(selection); }

    Vec3 selectedPoint() {
        return camera.project(selectedPoint.set(selected().position), 0, 0, WIDTH, HEIGHT);
    }

    void select(int direction) {
        selection = Math.floorMod(selection + direction, selectable.size);
    }

    void zoom(float ratio) {
        zoom = Mathf.clamp(zoom * ratio, 0.65f, 1.4f);
    }

    void update() {
        Vars.universe.updateGlobal();
        camera.position.set(view).scl(star.totalRadius * 2.6f * zoom).add(star.position);
        camera.up.set(Vec3.Y);
        camera.lookAt(star.position);
        camera.update();
    }

    void pick(float x, float y) {
        var ray = camera.getPickRay(x, HEIGHT - y, 0, 0, WIDTH, HEIGHT);
        float nearest = Float.POSITIVE_INFINITY;
        for (int i = 0; i < selectable.size; i++) {
            Planet planet = selectable.get(i);
            Vec3 hit = planet.intersect(ray, planet.radius);
            if (hit != null && ray.origin.dst2(hit) < nearest) {
                nearest = ray.origin.dst2(hit);
                selection = i;
            }
        }
    }

    boolean drawBodies(BodyDrawer draw) {
        for (Planet planet : bodies) {
            if (!camera.frustum.containsSphere(planet.position, planet.clipRadius)) continue;
            if (!drawMesh(draw, planet, meshIndex(planet), planet.getTransform(transform), planet.getRotation())) {
                return false;
            }
        }
        float ticks = initialTicks + (System.nanoTime() - openedAt) * (60f / 1_000_000_000f);
        for (Planet planet : bodies) {
            if (planet == Planets.serpulo && !drawClouds(draw, planet, 6, serpuloClouds, ticks)) return false;
            if (planet == Planets.erekir && !drawClouds(draw, planet, 8, erekirClouds, ticks)) return false;
        }
        return true;
    }

    private boolean drawClouds(BodyDrawer draw, Planet planet, int first, float[] speeds, float ticks) {
        if (!camera.frustum.containsSphere(planet.position, planet.clipRadius)) return true;
        for (int i = 0; i < speeds.length; i++) {
            float rotation = planet.getRotation() + ticks * speeds[i] / 40f;
            transform.setToTranslation(planet.position).rotate(Vec3.Y, rotation);
            if (!drawMesh(draw, planet, first + i, transform, rotation)) return false;
        }
        return true;
    }

    private boolean drawMesh(BodyDrawer draw, Planet planet, int index, Mat3D model, float rotation) {
        projection.set(camera.combined).mul(model);
        light.set(star.position).sub(planet.position).rotate(Vec3.Y, rotation).nor();
        eye.set(camera.position).sub(planet.position).rotate(Vec3.Y, rotation);
        lighting[0] = light.x;
        lighting[1] = light.y;
        lighting[2] = light.z;
        lighting[3] = star.lightColor.r;
        lighting[4] = star.lightColor.g;
        lighting[5] = star.lightColor.b;
        lighting[6] = eye.x;
        lighting[7] = eye.y;
        lighting[8] = eye.z;
        return draw.draw(index, projection.val, lighting);
    }

    void updateOrbits() {
        orbits.clear();
        stroke.project(camera.combined);
        for (Planet planet : bodies) {
            if (planet.parent == null || !planet.drawOrbit) continue;
            Vec3 center = planet.parent.position;
            float radius = planet.orbitRadius;
            int points = (int)(radius * 10);
            before.set(center).add(radius, 0, 0);
            for (int i = 1; i <= points; i++) {
                float angle = (float)i / points * 360f;
                after.set(center).add(Angles.trnsx(angle, radius), 0, Angles.trnsy(angle, radius));
                if (stroke.measure(before, after) >= 0.001f) {
                    stroke.offset(beforeOffset, stroke.w(before));
                    stroke.offset(afterOffset, stroke.w(after));
                    vertex(before, beforeOffset, 1);
                    vertex(before, beforeOffset, -1);
                    vertex(after, afterOffset, 1);
                    vertex(after, afterOffset, 1);
                    vertex(before, beforeOffset, -1);
                    vertex(after, afterOffset, -1);
                }
                before.set(after);
            }
        }
    }

    private void vertex(Vec3 point, Vec3 offset, int side) {
        orbits.add(point.x + offset.x * side, point.y + offset.y * side, point.z + offset.z * side, Pal.gray.r);
        orbits.add(Pal.gray.g, Pal.gray.b, 1f);
    }

    static int meshIndex(Planet planet) {
        return switch (planet.name) {
            case "serpulo" -> 0;
            case "erekir" -> 1;
            case "sun" -> 2;
            case "gier" -> 3;
            case "notva" -> 4;
            case "verilus" -> 5;
            default -> throw new IllegalArgumentException("Missing stock campaign mesh for " + planet.name);
        };
    }
}
