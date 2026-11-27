import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.content.UnitTypes;
import mindustry.game.Team;
import mindustry.gen.Player;

/**
 * Deterministic camera zoom and zoomed-touch mapping check for the live client.
 * Mirrors UpstreamBuildControlsReference.verifyCameraZoom without needing a
 * captured WorldStream fixture.
 */
public final class UpstreamCameraZoomReference {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException(
                "Expected a Glacier .msav path; run with -Pglacier=<path> or --args=<path>");
        }
        UpstreamWorldLoadProbe.load(new String[]{args[0]}, () -> Vars.indexer = new BlockIndexer());
        var player = UnitTypes.gamma.spawn(Team.sharded, 600f, 200f, 0f);
        Vars.player = Player.create();
        Vars.player.team(Team.sharded);
        Vars.player.unit(player);
        var controls = new UpstreamLiveBuildControls();

        controls.update(player, UpstreamLiveBuildControls.ZOOM_IN_PRESSED, -1);
        if (controls.consumeZoom() != 1) throw new AssertionError("C-Stick up must zoom in");
        controls.update(player, UpstreamLiveBuildControls.ZOOM_OUT_PRESSED, -1);
        if (controls.consumeZoom() != -1) throw new AssertionError("C-Stick down must zoom out");
        if (controls.consumeZoom() != 0) throw new AssertionError("Zoom input must be consumed once");

        if (UpstreamTerrainDrawProbe.clampZoom(9f) != 2f
                || UpstreamTerrainDrawProbe.clampZoom(0.1f) != 0.5f
                || UpstreamTerrainDrawProbe.zoomWidth(2f) != 8
                || UpstreamTerrainDrawProbe.zoomHeight(2f) != 6
                || UpstreamTerrainDrawProbe.zoomWidth(0.5f) != 32
                || UpstreamTerrainDrawProbe.zoomHeight(0.5f) != 24) {
            throw new AssertionError("Camera zoom clamp or span differs");
        }

        controls.viewport(73, 20, 8, 6);
        controls.update(player, 0, (112 << 16) | 160);
        if (controls.cursorX() != 77 || controls.cursorY() != 23) {
            throw new AssertionError("Zoomed touch grid did not map to the expected tile: "
                + controls.cursorX() + "," + controls.cursorY());
        }

        System.out.println("UPSTREAM_CAMERA_ZOOM_REFERENCE in=ok clamp=ok span=8x6..32x24 grid=77,23");
    }
}
