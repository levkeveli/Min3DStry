import arc.Core;
import arc.files.Fi;
import arc.graphics.Blending;
import arc.graphics.Color;
import arc.graphics.Colors;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.TextureRegion;
import arc.mock.MockGraphics;
import arc.util.Align;
import arc.util.Time;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.core.UI;
import mindustry.game.MapObjectives.LineMarker;
import mindustry.game.MapObjectives.PointMarker;
import mindustry.game.MapObjectives.QuadMarker;
import mindustry.game.MapObjectives.ShapeMarker;
import mindustry.game.MapObjectives.ShapeTextMarker;
import mindustry.game.MapObjectives.TextMarker;
import mindustry.game.MapObjectives.TextureMarker;
import mindustry.game.Rules;
import mindustry.gen.CreateMarkerCallPacket;
import mindustry.gen.WorldLabel;
import mindustry.gen.UpdateMarkerTextureCallPacket;
import mindustry.gen.UpdateMarkerCallPacket;
import mindustry.logic.LMarkerControl;
import mindustry.gen.Iconc;
import mindustry.io.JsonIO;
import mindustry.io.MapIO;
import mindustry.type.MapLocales;
import port.render.UpstreamLabelText;

public final class UpstreamObjectiveMarkersReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private record Label(String text, float x, float y, int flags, float size, int align, int lineAlign) {}
    private record Vertex(float x, float y, int color) {}
    private record Sprite(int page, int left, int top, int width, int height,
                          float x, float y, float originX, float originY, float drawWidth, float drawHeight,
                          float rotation, int color, int mixColor, Blending blending) {}
    private final List<Label> labels = new ArrayList<>();
    private final List<Vertex> vertices = new ArrayList<>();
    private final List<Sprite> sprites = new ArrayList<>();
    private float[] texturedQuad;
    private int texturedPage;
    private boolean verifyIcons;
    private int quads;

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) throw new IllegalArgumentException("Expected onset.msav, sprites.aatls, bundle base and optional icon table");
        var file = new Fi(args[0]);
        var bundle = new Fi(args[2]);
        verifyLocaleFallback(bundle, "ja");
        verifyLocaleFallback(bundle, "zh_TW");
        Core.bundle = UpstreamInterfaceLocale.loadForStockClient(bundle, 1, "en");
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
        target.verifyLogicGeometry();
        target.verifyTextureMarker();
        target.verifyQuadMarker();
    }

    private void verifyQuadMarker() {
        var markers = Vars.state.markers;
        markers.clear();
        var marker = new QuadMarker();
        marker.texture.value = "block-router-ui";
        marker.vertices = new float[]{
            88f, 104f, Color.whiteFloatBits, 0f, 0f, Color.clearFloatBits,
            90f, 152f, Color.sky.toFloatBits(), 0f, 1f, Color.clearFloatBits,
            143f, 160f, Color.orange.toFloatBits(), 1f, 1f, Color.clearFloatBits,
            137f, 110f, Color.whiteFloatBits, 1f, 0f, Color.clearFloatBits
        };
        var create = new CreateMarkerCallPacket();
        create.id = 7;
        create.marker = marker;
        UpstreamSnapshotDispatchReference.applyMarkerPacket(create);
        var decoded = (QuadMarker) markers.get(create.id);

        // A control packet can arrive between creation and the first frame.
        var update = new UpdateMarkerCallPacket();
        update.id = create.id;
        update.control = LMarkerControl.uvi;
        update.p1 = 2;
        update.p2 = 0.75;
        update.p3 = 0.875;
        UpstreamSnapshotDispatchReference.applyMarkerPacket(update);
        verifyQuadSubmission(decoded, Core.atlas.find("block-router-ui"));

        update.control = LMarkerControl.posi;
        update.p2 = 19;
        update.p3 = 21;
        UpstreamSnapshotDispatchReference.applyMarkerPacket(update);
        var texture = new UpdateMarkerTextureCallPacket();
        texture.id = create.id;
        texture.texture = Items.copper;
        UpstreamSnapshotDispatchReference.applyMarkerPacket(texture);
        if (decoded.vertices[12] != 19 * Vars.tilesize || decoded.vertices[13] != 21 * Vars.tilesize) {
            throw new AssertionError("Quad control did not preserve stock corner position");
        }
        verifyQuadSubmission(decoded, Items.copper.fullIcon);
        System.out.println("UPSTREAM_QUAD_MARKER_REFERENCE create=ok uvBeforeDraw=ok corners=ok tint=ok retarget=ok");
    }

    private void verifyQuadSubmission(QuadMarker marker, TextureRegion region) {
        texturedQuad = null;
        UpstreamObjectiveMarkers.draw(Vars.state.rules, 2.5f, (text, x, y, flags, size, align, lines) -> {});
        float u = region.u + (region.u2 - region.u) * 0.75f;
        float v = region.v + (region.v2 - region.v) * 0.125f;
        if (texturedPage != UpstreamTerrainDrawProbe.pageIndex(region) || !Arrays.equals(texturedQuad, marker.vertices)
                || Math.abs(marker.vertices[15] - u) > 0.00001f || Math.abs(marker.vertices[16] - v) > 0.00001f
                || marker.vertices[8] != Color.sky.toFloatBits() || marker.vertices[14] != Color.orange.toFloatBits()) {
            throw new AssertionError("Stock quad sprite changed: " + Arrays.toString(texturedQuad));
        }
    }

    private void verifyTextureMarker() {
        var markers = Vars.state.markers;
        markers.clear();
        var original = new TextureMarker("block-router-ui", 120f, 144f);
        original.autoscale = true;
        original.color.set(Color.sky);
        original.rotation = 42f;
        var create = new CreateMarkerCallPacket();
        create.id = 6;
        create.marker = original;
        UpstreamSnapshotDispatchReference.applyMarkerPacket(create);
        if (!(markers.get(create.id) instanceof TextureMarker marker) || !marker.autoscale
                || !marker.color.equals(original.color) || marker.rotation != original.rotation) {
            throw new AssertionError("Stock create-marker did not preserve texture presentation");
        }
        verifySprite(marker, Core.atlas.find("block-router-ui"), 2f);

        var update = new UpdateMarkerTextureCallPacket();
        update.id = create.id;
        update.texture = Items.copper;
        UpstreamSnapshotDispatchReference.applyMarkerPacket(update);
        verifySprite(marker, Items.copper.fullIcon, 2f);

        update.texture = "min3dstry-missing-marker-region";
        UpstreamSnapshotDispatchReference.applyMarkerPacket(update);
        verifySprite(marker, Core.atlas.find("error"), 2f);
        System.out.println("UPSTREAM_TEXTURE_MARKER_REFERENCE named=ok content=ok error=ok rotation=ok tint=ok autoscale=ok");
    }

    private void verifySprite(TextureMarker marker, TextureRegion region, float scale) {
        if (region == null || region.texture == null) throw new AssertionError("Missing marker texture in reference atlas");
        sprites.clear();
        UpstreamObjectiveMarkers.draw(Vars.state.rules, 4f / scale, (text, x, y, flags, size, align, lines) -> {});
        float width = region.width * region.scl() * Draw.xscl * scale;
        float height = region.height * region.scl() * Draw.yscl * scale;
        var expected = new Sprite(UpstreamTerrainDrawProbe.pageIndex(region), region.getX(), region.getY(),
            region.width, region.height, marker.pos.x - width / 2f, marker.pos.y - height / 2f,
            width / 2f, height / 2f, width, height, marker.rotation,
            Color.floatToIntColor(marker.color.toFloatBits()), 0, Blending.normal);
        if (!sprites.equals(List.of(expected))) throw new AssertionError("Marker sprite changed: " + sprites);
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
            (value, x, y, flags, size, align, lines) -> labels.add(new Label(value.plain(), x, y, flags, size, align, lines)));
        if (original.isEmpty() || !vertices.equals(original) || labels.size() != 1
                || !labels.get(0).text.equals(text.text) || labels.get(0).x != text.pos.x || labels.get(0).y != text.pos.y) {
            throw new AssertionError("Stock marker geometry, colors, or world visibility changed");
        }
        System.out.println("UPSTREAM_LOGIC_MARKER_REFERENCE point=ok shape=ok line=ok text=ok hidden=ok vertices=" + vertices.size());
    }

    private static void verifyLocaleFallback(Fi path, String code) {
        var stock = UpstreamInterfaceLocale.load(path, 1, code);
        var compact = UpstreamInterfaceLocale.loadForStockClient(path, 1, code);
        if (!code.equals(compact.getLocale().toString())) throw new AssertionError("Incorrect locale: " + code);
        int stockEntries = 0, compactEntries = 0;
        for (var source = stock; source != null; source = source.getParent()) {
            stockEntries += source.getProperties().size;
            for (String key : source.getKeys()) {
                if (!compact.has(key) || !stock.get(key).equals(compact.get(key))) {
                    throw new AssertionError("Changed stock fallback for " + code + " " + key);
                }
            }
        }
        for (var source = compact; source != null; source = source.getParent()) {
            compactEntries += source.getProperties().size;
        }
        if (compactEntries >= stockEntries) throw new AssertionError("No shadowed entries removed for " + code);
        System.out.println("UPSTREAM_LOCALE_COMPACT locale=" + code + " entries=" + compactEntries
            + " removed=" + (stockEntries - compactEntries));
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
        sprites.add(new Sprite(page, left, top, width, height, x, y, originX, originY,
            drawWidth, drawHeight, rotation, color, mixColor, blending));
    }

    @Override
    public void drawSolidQuad(float[] vertices, int offset, Blending blending) {
        quads++;
        for (int i = offset; i < offset + 24; i += 6) {
            this.vertices.add(new Vertex(vertices[i], vertices[i + 1], Float.floatToRawIntBits(vertices[i + 2])));
        }
    }

    @Override
    public void drawTexturedQuad(int page, int width, int height, float[] vertices, int offset, Blending blending) {
        if (texturedQuad != null || width <= 0 || height <= 0 || blending != Blending.normal) {
            throw new AssertionError("Unexpected textured quad submission");
        }
        texturedPage = page;
        texturedQuad = Arrays.copyOfRange(vertices, offset, offset + 24);
    }

    @Override
    public void end() {}
}
