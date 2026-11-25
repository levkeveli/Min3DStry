import arc.Core;
import arc.Settings;
import arc.assets.AssetManager;
import arc.files.Fi;
import arc.graphics.Color;
import arc.graphics.Gl;
import arc.graphics.Mesh;
import arc.graphics.g3d.VertexBatch3D;
import arc.graphics.g3d.PlaneBatch3D;
import arc.mock.MockGL20;
import arc.struct.FloatSeq;
import arc.util.ArcNativesLoader;
import arc.util.Time;
import arc.math.geom.Mat3D;
import arc.math.geom.Vec3;
import arc.mock.MockApplication;
import arc.mock.MockFiles;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.content.Planets;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.game.Saves;
import mindustry.game.Team;
import mindustry.game.Universe;
import mindustry.graphics.Pal;
import mindustry.graphics.g3d.MeshBuilder;
import mindustry.graphics.g3d.PlanetMesh;
import mindustry.graphics.g3d.PlanetRenderer;
import mindustry.net.Net;
import mindustry.type.Sector;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

/** Checks the ARM11 globe camera against stock v160.8 world-space ray picking. */
public final class UpstreamCampaignGlobeReference {
    public static void main(String[] args) throws Exception {
        Core.app = new MockApplication();
        Core.files = new MockFiles();
        Core.settings = new Settings();
        ArcNativesLoader.load();
        Core.gl = Core.gl20 = new MockGL20() {
            @Override
            public int glCreateProgram() {
                return 1;
            }

            @Override
            public int glCreateShader(int type) {
                return type;
            }

            @Override
            public String glGetShaderInfoLog(int shader) {
                return "";
            }

            @Override
            public void glGetShaderiv(int shader, int property, IntBuffer value) {
                value.put(0, property == Gl.compileStatus ? 1 : 0);
            }

            @Override
            public void glGetProgramiv(int program, int property, IntBuffer value) {
                value.put(0, property == Gl.linkStatus ? 1 : 0);
            }
        };
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
        checkGrid(Planets.serpulo.getStartSector());
        checkGrid(Planets.erekir.getStartSector());
        Sector serpuloStart = Planets.serpulo.getStartSector();
        Sector erekirStart = Planets.erekir.getStartSector();
        checkArc(serpuloStart, serpuloStart.near().first(), Pal.accent, new Color(Pal.accent).a(0f),
            1f, 80f, 0.006f);
        checkArc(erekirStart, erekirStart.near().first(), Team.crux.color, new Color(Team.crux.color).a(0.5f),
            0.3f, 110f, 0.006f);
        checkArc(serpuloStart, numbered, Team.crux.color, Color.clear, 0.24f, 110f, 0.005f);
        checkTrade(serpuloStart, serpuloStart.near().first(), Color.gray);
        checkTrade(serpuloStart, numbered, Pal.place);
        System.out.println("UPSTREAM_CAMPAIGN_ARC_REFERENCE launch=true shield=true invasion=true trade=true");
        checkMarkers();
        if (args.length == 1) checkCampaignSurface(args[0]);
        System.out.println("UPSTREAM_CAMPAIGN_GLOBE_REFERENCE serpulo=" + Planets.serpulo.startSector
            + " rotated=" + numbered.id + " erekir=" + Planets.erekir.startSector + " zoom=true");
    }

    private static void checkMarkers() {
        var markers = new UpstreamCampaignMarkers();
        var starter = Planets.serpulo.getStartSector();
        var globe = new UpstreamCampaignGlobe(starter);
        globe.update(200, 120);
        starter.info.contentIcon = Items.copper;
        try {
            markers.update(globe, starter, 42f);
            if (markers.ids.size < 2 || markers.ids.peek() != Items.copper.id
                || markers.ids.get(markers.ids.size - 2) != Items.copper.getContentType().ordinal()
                || markers.planes.size != markers.ids.size / 2 * 13) {
                throw new AssertionError("The hovered sector did not use the saved stock content icon");
            }
        } finally {
            starter.info.contentIcon = null;
        }

        var stockOrigin = new Vec3();
        var stockUp = new Vec3();
        var stockRight = new Vec3();
        var stock = new PlaneBatch3D(8) {
            @Override
            public void setPlane(Vec3 at, Vec3 vertical, Vec3 horizontal) {
                stockOrigin.set(at);
                stockUp.set(vertical);
                stockRight.set(horizontal);
            }
        };
        try {
            for (Sector sector : Planets.serpulo.sectors) {
                markers.plane(sector);
                sector.planet.setPlane(sector, stock);
                if (markers.origin.dst(stockOrigin) > 0.00001f || markers.up.dst(stockUp) > 0.00001f
                    || markers.right.dst(stockRight) > 0.00001f) {
                    throw new AssertionError("Marker plane differs from original at sector " + sector.id
                        + " stockOrigin=" + markers.origin.dst(stockOrigin) + " stockUp=" + markers.up.dst(stockUp)
                        + " stockRight=" + markers.right.dst(stockRight));
                }
            }
        } finally {
            stock.dispose();
        }
        System.out.println("UPSTREAM_CAMPAIGN_MARKER_REFERENCE sector=170 content=copper planes="
            + Planets.serpulo.sectors.size);
    }

    private static void checkCampaignSurface(String path) throws Exception {
        var planet = Planets.serpulo;
        var file = new Fi(path);
        if (UpstreamCampaignSurface.open(planet, file) != null) {
            throw new AssertionError("Fresh campaign did not match the packaged stock surface");
        }
        Vars.content.loadColors();
        Core.assets = new AssetManager();
        var sector = planet.getStartSector();
        sector.save = new Saves().new SaveSlot(new Fi("sector-serpulo-170.msav"));
        sector.info.hasCore = true;
        sector.info.lightCoverage = 50000;
        var surface = UpstreamCampaignSurface.open(planet, file);
        if (surface == null) throw new AssertionError("The original local base did not trigger a surface change");
        int[] faces = UpstreamCampaignSurface.packedFaces(surface);
        while (faces.length == 0) {
            surface.update(192);
            faces = UpstreamCampaignSurface.packedFaces(surface);
        }

        var meshField = PlanetMesh.class.getDeclaredField("mesh");
        meshField.setAccessible(true);
        var rebuilt = planet.meshLoader.get();
        try {
            var mesh = (Mesh)meshField.get(rebuilt);
            var vertices = mesh.getVerticesBuffer();
            int vertex = 0;
            int lit = 0, changed = 0;
            var baseline = ByteBuffer.wrap(Files.readAllBytes(Path.of(path).resolveSibling("serpulo.m3pm")))
                .order(ByteOrder.LITTLE_ENDIAN);
            for (int offset = 0; offset < faces.length; offset += 3) {
                for (int i = 0; i < faces[offset]; i++, vertex++) {
                    if (Float.floatToRawIntBits(vertices.get(vertex * 8 + 6)) != faces[offset + 1]
                        || Float.floatToRawIntBits(vertices.get(vertex * 8 + 7)) != faces[offset + 2]) {
                        throw new AssertionError("The Java ARM11 palette diverged from stock at vertex " + vertex);
                    }
                    if (faces[offset + 2] != 0) lit++;
                    int initial = 20 + (vertex * 8 + 6) * Float.BYTES;
                    if (baseline.getInt(initial) != faces[offset + 1]
                        || baseline.getInt(initial + Float.BYTES) != faces[offset + 2]) changed++;
                }
            }
            if (vertex != mesh.getNumVertices() || lit == 0 || changed == 0) {
                throw new AssertionError("The updated stock surface is incomplete");
            }
            System.out.println("UPSTREAM_CAMPAIGN_SURFACE_REFERENCE planet=serpulo vertices=" + vertex + " lit=" + lit
                + " changed=" + changed);
        } finally {
            rebuilt.dispose();
        }
    }

    private static void checkArc(Sector from, Sector to, Color head, Color tail,
                                 float height, float period, float stroke) {
        var port = new UpstreamCampaignArcs();
        var original = new RecordingBatch();
        var vertices = new FloatSeq();
        float oldTime = Time.globalTime;
        Time.globalTime = 123f;
        try {
            port.ribbon(from.planet, from.tile.v, to.tile.v, head, tail, height, period, stroke, Time.globalTime, vertices);
            from.planet.drawArcLine(original, from.tile.v, to.tile.v, head, tail, height, period, 25, stroke);
        } finally {
            Time.globalTime = oldTime;
            original.dispose();
        }
        int stride = UpstreamCampaignSectorOverlay.FLOATS_PER_VERTEX;
        int[] trianglePair = {0, 1, 2, 2, 1, 3};
        if (vertices.size != 25 * trianglePair.length * stride || original.vertices.size != 52 * stride
            || original.primitive != Gl.triangleStrip) {
            throw new AssertionError("Stock projection arc has an unexpected vertex layout");
        }
        for (int segment = 0; segment < 25; segment++) {
            for (int i = 0; i < trianglePair.length; i++) {
                for (int field = 0; field < stride; field++) {
                    float expected = original.vertices.get((segment * 2 + trianglePair[i]) * stride + field);
                    float actual = vertices.get((segment * trianglePair.length + i) * stride + field);
                    if (Math.abs(expected - actual) > 0.000002f) {
                        throw new AssertionError("Stock arc mismatch at segment " + segment + ", component " + field
                            + " (" + actual + " vs " + expected + ")");
                    }
                }
            }
        }
    }

    private static void checkTrade(Sector from, Sector to, Color head) {
        var globe = new UpstreamCampaignGlobe(from);
        globe.update(200, 120);
        var port = new UpstreamCampaignArcs();
        var original = new RecordingBatch();
        var vertices = new FloatSeq();
        float oldTime = Time.globalTime;
        Time.globalTime = 153f;
        try {
            port.trade(from.planet, from.tile.v, to.tile.v, head, Time.globalTime, globe.projection, vertices);
            from.planet.drawArc(original, from.tile.v, to.tile.v, head, Pal.accent, 0.4f, 90f, 25);
        } finally {
            Time.globalTime = oldTime;
            original.dispose();
        }
        int stride = UpstreamCampaignSectorOverlay.FLOATS_PER_VERTEX;
        if (vertices.size != 25 * 6 * stride || original.vertices.size != 26 * stride
            || original.primitive != Gl.lineStrip) {
            throw new AssertionError("Stock trade route has an unexpected vertex layout");
        }
        for (int segment = 0; segment < 25; segment++) {
            checkLineEndpoint(vertices, original.vertices, (segment * 6) * stride,
                (segment * 6 + 1) * stride, segment * stride);
            checkLineEndpoint(vertices, original.vertices, (segment * 6 + 2) * stride,
                (segment * 6 + 5) * stride, (segment + 1) * stride);
        }
    }

    private static void checkLineEndpoint(FloatSeq vertices, FloatSeq original, int a, int b, int expected) {
        for (int field = 0; field < 7; field++) {
            float first = vertices.get(a + field), second = vertices.get(b + field);
            if (Math.abs((first + second) / 2f - original.get(expected + field)) > 0.000002f
                || (field >= 3 && first != second)) {
                throw new AssertionError("Trade route center or animated color does not match the stock line");
            }
        }
    }

    private static void checkGrid(Sector start) {
        checkGridEdges(start);
        var globe = new UpstreamCampaignGlobe(start);
        var overlay = new UpstreamCampaignSectorOverlay();
        var grid = new UpstreamCampaignGrid();
        Vec3 midpoint = new Vec3(), first = new Vec3(), second = new Vec3();
        int maximum = 0, samples = 0;
        for (int zoom = 0; zoom < 3; zoom++) {
            globe.zoom(zoom == 0 ? 0.55f : 1.55f);
            for (int step = 0; step < 180; step++) {
                int cursorX = step % 75 < 50 ? 200 : 160;
                int cursorY = step % 75 >= 25 ? 120 : 80;
                Sector hovered = globe.update(cursorX, cursorY);
                overlay.update(globe.planet(), null, hovered, null, step);
                var vertices = overlay.vertices;
                int offset = vertices.size;
                int edgeCount = grid.append(globe.planet(), globe.projection, globe.gridCursor, vertices);
                maximum = Math.max(maximum, vertices.size / UpstreamCampaignSectorOverlay.FLOATS_PER_VERTEX);
                if (hovered != null && step > 10 && edgeCount == 0) {
                    throw new AssertionError("Grid vanished beneath the cursor");
                }
                if (edgeCount > 0) {
                    first.set(vertices.get(offset), vertices.get(offset + 1), vertices.get(offset + 2));
                    second.set(vertices.get(offset + 7), vertices.get(offset + 8), vertices.get(offset + 9));
                    midpoint.set(first).lerp(second, 0.5f);
                    float fade = Math.max(0f, 1f - midpoint.dst(globe.gridCursor));
                    var color = PlanetRenderer.outlineColor;
                    if (Math.abs(vertices.get(offset + 3) - color.r * fade) > 0.000002f
                        || Math.abs(vertices.get(offset + 6) - color.a * fade) > 0.000002f) {
                        throw new AssertionError("The grid RGB or alpha differs from the stock shader");
                    }
                    Mat3D.prj(first, globe.projection);
                    Mat3D.prj(second, globe.projection);
                    float dx = (first.x - second.x) * 200f, dy = (first.y - second.y) * 120f;
                    if (Math.abs(dx * dx + dy * dy - 1f) > 0.0001f
                        || Math.abs(first.z - second.z) > 0.000002f) {
                        throw new AssertionError("The grid line did not preserve a one-pixel width and stock depth");
                    }
                    samples++;
                }
                globe.rotate(127, step % 90 < 45 ? 64 : -64);
            }
        }
        if (samples == 0 || maximum >= 65536) {
            throw new AssertionError("Invalid grid batch size for " + start.planet.name + ": " + maximum);
        }
        System.out.println("UPSTREAM_CAMPAIGN_GRID_REFERENCE planet=" + start.planet.name
            + " samples=" + samples + " maxVertices=" + maximum);
    }

    private static void checkGridEdges(Sector sector) {
        var planet = sector.planet;
        var radius = PlanetRenderer.outlineRad * planet.radius;
        var color = PlanetRenderer.outlineColor;
        var mesh = MeshBuilder.buildPlanetGrid(planet.grid, color, radius);
        var segments = new HashMap<String, Integer>();
        try {
            var buffer = mesh.getVerticesBuffer();
            for (int index = 0; index < buffer.limit(); index += 8) {
                var a = new Vec3(buffer.get(index), buffer.get(index + 1), buffer.get(index + 2));
                var b = new Vec3(buffer.get(index + 4), buffer.get(index + 5), buffer.get(index + 6));
                if (Float.floatToRawIntBits(buffer.get(index + 3)) != Float.floatToRawIntBits(color.toFloatBits())
                    || Float.floatToRawIntBits(buffer.get(index + 7)) != Float.floatToRawIntBits(color.toFloatBits())) {
                    throw new AssertionError("The stock grid color changed");
                }
                segments.merge(edgeKey(a, b), 1, Integer::sum);
            }
        } finally {
            mesh.dispose();
        }
        var start = new Vec3();
        var end = new Vec3();
        for (var edge : planet.grid.edges) {
            var key = edgeKey(start.set(edge.corners[0].v).scl(radius), end.set(edge.corners[1].v).scl(radius));
            if (!Integer.valueOf(2).equals(segments.remove(key))) {
                throw new AssertionError("The port grid does not match the stock mesh edge " + edge.id);
            }
        }
        if (!segments.isEmpty()) throw new AssertionError("The stock mesh has unmatched grid edges");
    }

    private static String edgeKey(Vec3 first, Vec3 second) {
        String a = first.toString(), b = second.toString();
        return a.compareTo(b) < 0 ? a + b : b + a;
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
        overlay.outline(sector, Pal.accent, 0.04f, 0.001f, false);
        sector.planet.drawSelection(original, sector, Pal.accent, 0.04f, 0.001f);
        compare(overlay, original, "selected outline");

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

        overlay.update(sector.planet, null, sector, null, 42f);
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
        private final Color tint = new Color();
        int primitive;

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

        @Override
        public void color(Color color) {
            tint.set(color);
        }

        @Override
        public void vertex(Vec3 vertex) {
            record(vertex.x, vertex.y, vertex.z, tint);
        }

        @Override
        public void flush(int mode) {
            primitive = mode;
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
