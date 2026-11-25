import arc.Core;
import arc.Settings;
import arc.math.geom.Mat3D;
import arc.math.geom.Vec3;
import arc.mock.MockApplication;
import arc.mock.MockFiles;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.game.Universe;
import mindustry.net.Net;
import mindustry.type.Sector;

/** Checks the ARM11 globe camera against stock v160.8 world-space ray picking. */
public final class UpstreamCampaignGlobeReference {
    public static void main(String[] args) {
        Core.app = new MockApplication();
        Core.files = new MockFiles();
        Core.settings = new Settings();
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
        System.out.println("UPSTREAM_CAMPAIGN_GLOBE_REFERENCE serpulo=" + Planets.serpulo.startSector
            + " rotated=" + numbered.id + " erekir=" + Planets.erekir.startSector + " zoom=true");
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
