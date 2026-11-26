import arc.graphics.g3d.Camera3D;
import arc.math.Mathf;
import arc.math.geom.Mat3D;
import arc.math.geom.Vec3;
import mindustry.content.Planets;
import mindustry.graphics.g3d.PlanetRenderer;
import mindustry.type.Planet;
import mindustry.type.Sector;

/** The same Arc projection and stock sector picking for the New 3DS campaign globe. */
final class UpstreamCampaignGlobe {
    final Camera3D camera = new Camera3D();
    final Mat3D projection = new Mat3D();
    final Vec3 gridCursor = new Vec3();
    final float[] lighting = new float[9];
    private final Mat3D transform = new Mat3D();
    private final Vec3 orbit = new Vec3();
    private final Vec3 scratch = new Vec3();
    private final Vec3 right = new Vec3();
    private final Vec3 light = new Vec3();
    private final Vec3 eye = new Vec3();
    private float distance = 2.8f;
    private Planet planet;

    UpstreamCampaignGlobe(Sector initial) {
        camera.resize(400, 240);
        camera.near = 0.1f;
        camera.far = 30f;
        focus(initial);
    }

    Planet planet() {
        return planet;
    }

    int meshIndex() {
        return planet == Planets.erekir ? 1 : 0;
    }

    void switchPlanet() {
        focus((planet == Planets.serpulo ? Planets.erekir : Planets.serpulo).getStartSector());
    }

    void rotate(int horizontal, int vertical) {
        if (Math.abs(horizontal) > 20) orbit.rotate(Vec3.Y, horizontal * 2f / 127f);
        if (Math.abs(vertical) > 20) {
            right.set(orbit).crs(Vec3.Y).nor();
            scratch.set(orbit).rotate(right, vertical * 2f / 127f);
            if (Math.abs(scratch.y) < 0.99f) orbit.set(scratch);
        }
    }

    void zoom(float change) {
        distance = Mathf.clamp(distance * change, 1.8f, 4.2f);
    }

    void focus(Sector sector) {
        planet = sector.planet;
        planet.lookAt(sector, orbit).nor();
    }

    Sector update(float cursorX, float cursorY) {
        camera.position.set(orbit).scl(distance * planet.radius).add(planet.position);
        camera.up.set(Math.abs(orbit.y) > 0.99f ? Vec3.Z : Vec3.Y);
        camera.lookAt(planet.position);
        camera.update();
        projection.set(camera.combined).mul(planet.getTransform(transform));

        // HexMesh supplies local light direction to planet.vert. The eye direction
        // can also be evaluated locally because the planet transform is rigid.
        float rotation = planet.getRotation();
        light.set(planet.solarSystem.position).sub(planet.position).rotate(Vec3.Y, rotation).nor();
        eye.set(camera.position).sub(planet.position).rotate(Vec3.Y, rotation);
        var ambient = planet.solarSystem.lightColor;
        lighting[0] = light.x;
        lighting[1] = light.y;
        lighting[2] = light.z;
        lighting[3] = ambient.r;
        lighting[4] = ambient.g;
        lighting[5] = ambient.b;
        lighting[6] = eye.x;
        lighting[7] = eye.y;
        lighting[8] = eye.z;
        var ray = camera.getPickRay(cursorX, 240 - cursorY, 0, 0, 400, 240);
        float gridRadius = PlanetRenderer.outlineRad * planet.radius;
        Vec3 hit = planet.intersect(ray, gridRadius);
        if (hit == null) {
            gridCursor.lerp(Vec3.Zero, 0.2f);
        } else {
            gridCursor.lerp(scratch.set(hit).sub(planet.position).rotate(Vec3.Y, rotation), 0.2f);
        }
        return planet.getSector(ray, gridRadius);
    }
}
