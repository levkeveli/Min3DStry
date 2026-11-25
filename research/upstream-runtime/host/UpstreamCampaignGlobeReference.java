import arc.Core;
import arc.Settings;
import arc.graphics.Color;
import arc.graphics.g3d.VertexBatch3D;
import arc.mock.MockGL20;
import arc.struct.FloatSeq;
import arc.util.ArcNativesLoader;
import arc.util.Time;
import arc.math.geom.Mat3D;
import arc.math.geom.Vec3;
import arc.mock.MockApplication;
import arc.mock.MockFiles;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.game.Universe;
import mindustry.graphics.g3d.PlanetRenderer;
import mindustry.net.Net;
import mindustry.type.Sector;

/** Checks the ARM11 globe camera against stock v160.8 world-space ray picking. */
public final class UpstreamCampaignGlobeReference {
    public static void main(String[] args) {
        Core.app = new MockApplication();
        Core.files = new MockFiles();
        Core.settings = new Settings();
        ArcNativesLoader.load();
        Core.gl = Core.gl20 = new MockGL20();
        Vars.headless = true;
        Vars.state = new GameState();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        Vars.net = new Net(null);
        Vars.universe = new Universe();
        Vars.universe.updateGlobal();

        var globe = new UpstreamCampaignGlobe(Planets.serpulo.getStartSector());
        checkCenter(globe, Planets.serpulo.getStartSector());
        for (int step = 0; step < 20; step++) globe.rotate(127, 0);
        var numbered = globe.update(200, 120);
        if (numbered == null || numbered == Planets.serpulo.getStartSector()) {
            throw new AssertionError("Globe rotation did not move to another stock Serpulo sector");
        }
        float before = globe.camera.position.dst(Planets.serpulo.position);
        globe.zoom(0.9f);
        globe.update(200, 120);
        if (globe.camera.position.dst(Planets.serpulo.position) >= before) {
            throw new AssertionError("Globe zoom did not move the Arc camera");
        }
        globe.switchPlanet();
        checkCenter(globe, Planets.erekir.getStartSector());
        if (globe.update(0, 0) != null) throw new AssertionError("Off-globe picking intersected Erekir");
        checkSectorGeometry(Planets.serpulo.getStartSector());
        checkSectorGeometry(Planets.erekir.getStartSector());
        System.out.println("UPSTREAM_CAMPAIGN_GLOBE_REFERENCE serpulo=" + Planets.serpulo.startSector
            + " rotated=" + numbered.id + " erekir=" + Planets.erekir.startSector + " zoom=true");
    }

    private static void checkSectorGeometry(Sector sector) {
        var overlay = new UpstreamCampaignSectorOverlay();
        var original = new RecordingBatch();
        var color = PlanetRenderer.hoverColor;
        overlay.fill(sector, color, -0.003f);
        sector.planet.fill(original, sector, color, -0.003f);
        compare(overlay, original, "fill");

        overlay.vertices.clear();
        original.vertices.clear();
        overlay.outline(sector, color, 0.026f, -0.001f, false);
        sector.planet.drawSelection(original, sector, color, 0.026f, -0.001f);
        compare(overlay, original, "outline");

        overlay.vertices.clear();
        original.vertices.clear();
        float oldTime = Time.globalTime;
        Time.globalTime = 42f;
        try {
            overlay.borders(sector, Time.globalTime);
            sector.planet.drawBorders(original, sector, PlanetRenderer.borderColor, 1f);
        } finally {
            Time.globalTime = oldTime;
        }
        compare(overlay, original, "border");
        original.dispose();

        overlay.update(sector.planet, null, sector, 42f);
        if (overlay.outlined == 0 || overlay.vertices.size == 0
            || overlay.vertices.size > 20000 * UpstreamCampaignSectorOverlay.FLOATS_PER_VERTEX) {
            throw new AssertionError("Unexpected stock " + sector.planet.name + " overlay counts");
        }
        System.out.println("UPSTREAM_CAMPAIGN_SECTOR_OVERLAY_REFERENCE planet=" + sector.planet.name
            + " vertices=" + (overlay.vertices.size / UpstreamCampaignSectorOverlay.FLOATS_PER_VERTEX)
            + " shadowed=" + overlay.shadowed + " outlined=" + overlay.outlined + " marked=" + overlay.marked);
    }

    private static void compare(UpstreamCampaignSectorOverlay overlay, RecordingBatch original, String shape) {
        if (overlay.vertices.size != original.vertices.size) {
            throw new AssertionError("Stock " + shape + " vertex count changed");
        }
        for (int i = 0; i < overlay.vertices.size; i++) {
            float actual = overlay.vertices.get(i);
            float expected = original.vertices.get(i);
            if (Math.abs(actual - expected) > 0.000002f) {
                throw new AssertionError("Stock " + shape + " component " + i + " differs: " + actual + " vs " + expected);
            }
        }
    }

    private static final class RecordingBatch extends VertexBatch3D {
        final FloatSeq vertices = new FloatSeq();

        RecordingBatch() {
            super(1, false, true, 0, null);
        }

        @Override
        public void tri(float ax, float ay, float az, float bx, float by, float bz,
                        float cx, float cy, float cz, Color color) {
            record(ax, ay, az, color);
            record(bx, by, bz, color);
            record(cx, cy, cz, color);
        }

        private void record(float x, float y, float z, Color color) {
            vertices.add(x, y, z, color.r);
            vertices.add(color.g, color.b, color.a);
        }
    }

    private static void checkCenter(UpstreamCampaignGlobe globe, Sector expected) {
        if (globe.update(200, 120) != expected) {
            throw new AssertionError("Stock ray did not hit " + expected.planet.name + ":" + expected.id);
        }
        var center = new Vec3(expected.tile.v).scl(expected.planet.radius);
        Mat3D.prj(center, globe.projection);
        if (Math.abs(center.x) > 0.001f || Math.abs(center.y) > 0.001f) {
            throw new AssertionError("Stock mesh transform and the picker disagree for " + expected.id
                + " (" + center + ")");
        }
    }
}
