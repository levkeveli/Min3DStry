import arc.Core;
import arc.files.Fi;
import arc.graphics.Blending;
import arc.graphics.Colors;
import arc.util.Align;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.core.UI;
import mindustry.game.MapObjectives.ShapeTextMarker;
import mindustry.gen.WorldLabel;
import mindustry.gen.Iconc;
import mindustry.io.JsonIO;
import mindustry.io.MapIO;
import mindustry.type.MapLocales;
import port.render.UpstreamLabelText;

public final class UpstreamObjectiveMarkersReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private record Label(String text, float x, float y, int flags, float size, int align, int lineAlign) {}
    private final List<Label> labels = new ArrayList<>();
    private boolean verifyIcons;
    private int quads;

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) throw new IllegalArgumentException("Expected onset.msav, sprites.aatls, bundle base and optional icon table");
        var file = new Fi(args[0]);
        Core.bundle = UpstreamInterfaceLocale.load(new Fi(args[2]), 1, "en");
        UpstreamWorldLoadProbe.initialize(file.parent());
        Core.settings.put("locale", "en");
        var map = MapIO.createMap(file, false);
        Vars.state.rules = map.rules();
        Vars.state.mapLocales = JsonIO.read(MapLocales.class, map.tags.get("locales", "{}"));
        UpstreamTerrainDrawProbe.loadAtlas(new Fi(args[1]), () -> {});
        if (args.length == 4) {
            UpstreamLabelText.loadIcons(new Fi(args[3]));
            verifyMarkup();
        }
        var target = new UpstreamObjectiveMarkersReference();
        target.verifyIcons = args.length == 4;
        UpstreamTerrainDrawProbe.liveViewport(target);

        target.verify(0, 2320, 868, "Tap to mine");
        Vars.state.rules.objectives.get(0).done();
        target.verify(1, 2312, 957.8f, "on the vent.");
        for (int i = 1; i <= 3; i++) Vars.state.rules.objectives.get(i).done();
        target.verify(4, 2280, 864, "ducts");
        System.out.println("UPSTREAM_WORLD_MARKER_REFERENCE onset=mine,research,ducts worldText=ok shapes=ok");
    }

    private static void verifyMarkup() {
        var label = UpstreamLabelText.parse(UI.formatIcons("[accent][[x[]\n:beryllium: [#ff0000]red[]"));
        if (!label.plain().equals("[x\n red")
                || label.characters().get(0).color() != Integer.reverseBytes(Colors.get("accent").rgba8888())
                || label.characters().get(5).color() != 0xff0000ff) {
            throw new AssertionError("Stock markup, newline or coloring differs: " + label);
        }
    }

    private void verify(int index, float x, float y, String words) {
        labels.clear();
        quads = 0;
        var rules = Vars.state.rules;
        var marker = rules.objectives.get(index).markers[0];
        UpstreamObjectiveMarkers.draw(rules, 2.5f, (text, tx, ty, flags, size, align, lines) -> {
            labels.add(new Label(text.plain(), tx, ty, flags, size, align, lines));
            if (verifyIcons && index < 2) {
                var art = Core.atlas.find(index == 0 ? "item-beryllium-ui" : "block-turbine-condenser-ui");
                if (!art.found() || text.characters().stream().noneMatch(glyph -> glyph.art() == art)
                        || (index == 1 && text.characters().stream().noneMatch(glyph -> glyph.codepoint() == Iconc.tree))) {
                    throw new AssertionError("Missing original Onset marker glyphs: " + text);
                }
            }
        });
        int expectedQuads = marker instanceof ShapeTextMarker shape ? 2 * shape.sides : 0;
        if (quads != expectedQuads || labels.size() != 1) {
            throw new AssertionError("Incorrect shapes or active labels: " + quads + " / " + labels);
        }
        var label = labels.get(0);
        if (label.x != x || label.y != y || label.size != 1f
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
