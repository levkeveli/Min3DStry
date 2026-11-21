import arc.Core;
import arc.files.Fi;
import arc.graphics.Blending;
import arc.util.Align;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.game.MapObjectives.ShapeTextMarker;
import mindustry.gen.WorldLabel;
import mindustry.io.JsonIO;
import mindustry.io.MapIO;
import mindustry.type.MapLocales;

public final class UpstreamObjectiveMarkersReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private record Label(String text, float x, float y, int flags, float size, int align, int lineAlign) {}
    private final List<Label> labels = new ArrayList<>();
    private int quads;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Expected onset.msav, sprites.aatls and bundle base");
        var file = new Fi(args[0]);
        Core.bundle = UpstreamInterfaceLocale.load(new Fi(args[2]), 1, "en");
        UpstreamWorldLoadProbe.initialize(file.parent());
        Core.settings.put("locale", "en");
        var map = MapIO.createMap(file, false);
        Vars.state.rules = map.rules();
        Vars.state.mapLocales = JsonIO.read(MapLocales.class, map.tags.get("locales", "{}"));
        UpstreamTerrainDrawProbe.loadAtlas(new Fi(args[1]), () -> {});
        var target = new UpstreamObjectiveMarkersReference();
        UpstreamTerrainDrawProbe.liveViewport(target);

        target.verify(0, 2320, 868, "from walls.");
        Vars.state.rules.objectives.get(0).done();
        target.verify(1, 2312, 957.8f, "on the vent.");
        System.out.println("UPSTREAM_WORLD_MARKER_REFERENCE onset=mine,research worldText=ok shapes=ok");
    }

    private void verify(int index, float x, float y, String words) {
        labels.clear();
        quads = 0;
        var rules = Vars.state.rules;
        var marker = (ShapeTextMarker) rules.objectives.get(index).markers[0];
        UpstreamObjectiveMarkers.draw(rules, 2.5f,
            (text, tx, ty, flags, size, align, lines) -> labels.add(new Label(text, tx, ty, flags, size, align, lines)));
        if (quads != 2 * marker.sides || labels.size() != 1) {
            throw new AssertionError("Incorrect shapes or active labels: " + quads + " / " + labels);
        }
        var label = labels.get(0);
        if (label.x != x || label.y != y || label.size != marker.fontSize
                || label.flags != (WorldLabel.flagBackground | WorldLabel.flagOutline)
                || label.align != Align.center || label.lineAlign != Align.center
                || !label.text.contains(words) || label.text.contains("[accent]")) {
            throw new AssertionError("Incorrect Onset label: " + label);
        }
    }

    @Override
    public void begin(int left, int bottom, int width, int height) {}

    @Override
    public void draw(int page, int left, int top, int width, int height,
                     float x, float y, float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mixColor, Blending blending) {
        throw new AssertionError("Unexpected marker sprite");
    }

    @Override
    public void drawSolidQuad(float[] vertices, int offset, Blending blending) {
        quads++;
    }

    @Override
    public void end() {}
}
