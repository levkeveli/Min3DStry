import arc.Core;
import arc.files.Fi;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.mock.MockGraphics;
import arc.util.Align;
import arc.util.Time;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.game.MapObjectives.LineMarker;
import mindustry.game.MapObjectives.PointMarker;
import mindustry.game.MapObjectives.ShapeMarker;
import mindustry.game.MapObjectives.ShapeTextMarker;
import mindustry.game.MapObjectives.TextMarker;
import mindustry.game.Rules;
import mindustry.gen.WorldLabel;
import mindustry.io.JsonIO;
import mindustry.io.MapIO;
import mindustry.type.MapLocales;

public final class UpstreamObjectiveMarkersReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private record Label(String text, float x, float y, int flags, float size, int align, int lineAlign) {}
    private record Vertex(float x, float y, int color) {}
    private final List<Label> labels = new ArrayList<>();
    private final List<Vertex> vertices = new ArrayList<>();
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

        target.verify(0, 2320, 868, "Tap to mine");
        Vars.state.rules.objectives.get(0).done();
        target.verify(1, 2312, 957.8f, "on the vent.");
        for (int i = 1; i <= 3; i++) Vars.state.rules.objectives.get(i).done();
        target.verify(4, 2280, 864, "ducts");
        System.out.println("UPSTREAM_WORLD_MARKER_REFERENCE onset=mine,research,ducts worldText=ok shapes=ok");
        target.verifyLogicGeometry();
    }

    private void verifyLogicGeometry() {
        Vars.state.rules = new Rules();
        Core.graphics = new MockGraphics();
        var markers = Vars.state.markers;
        var point = new PointMarker(84, 132);
        point.autoscale = true;
        var shape = new ShapeMarker(112, 140);
        shape.fill = true;
        var line = new LineMarker(144, 148, 188, 162);
        line.color1.set(Color.sky);
        line.color2.set(Color.orange);
        var text = new TextMarker("Logic marker", 160f, 192f);
        var hidden = new TextMarker("Hidden", 176f, 192f);
        hidden.world = -1;
        markers.add(1, point);
        markers.add(2, shape);
        markers.add(3, line);
        markers.add(4, text);
        markers.add(5, hidden);

        Time.globalTime = 50f;
        vertices.clear();
        point.draw(4f / 2.5f);
        shape.draw(1f);
        line.draw(1f);
        Draw.reset();
        var original = List.copyOf(vertices);
        vertices.clear();
        labels.clear();
        UpstreamObjectiveMarkers.draw(Vars.state.rules, 2.5f,
            (value, x, y, flags, size, align, lines) -> labels.add(new Label(value, x, y, flags, size, align, lines)));
        if (original.isEmpty() || !vertices.equals(original) || labels.size() != 1
                || !labels.get(0).text.equals(text.text) || labels.get(0).x != text.pos.x || labels.get(0).y != text.pos.y) {
            throw new AssertionError("Stock marker geometry, colors, or world visibility changed");
        }
        System.out.println("UPSTREAM_LOGIC_MARKER_REFERENCE point=ok shape=ok line=ok text=ok hidden=ok vertices=" + vertices.size());
    }

    private void verify(int index, float x, float y, String words) {
        labels.clear();
        quads = 0;
        var rules = Vars.state.rules;
        var marker = rules.objectives.get(index).markers[0];
        UpstreamObjectiveMarkers.draw(rules, 2.5f,
            (text, tx, ty, flags, size, align, lines) -> labels.add(new Label(text, tx, ty, flags, size, align, lines)));
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
        for (int i = offset; i < offset + 24; i += 6) {
            this.vertices.add(new Vertex(vertices[i], vertices[i + 1], Float.floatToRawIntBits(vertices[i + 2])));
        }
    }

    @Override
    public void end() {}
}
