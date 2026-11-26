import arc.Core;
import arc.Settings;
import arc.graphics.Color;
import arc.graphics.Mesh;
import arc.graphics.VertexAttribute;
import arc.mock.MockApplication;
import arc.mock.MockFiles;
import arc.mock.MockGL20;
import arc.util.ArcNativesLoader;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.graphics.g3d.PlanetGrid;
import mindustry.graphics.g3d.PlanetMesh;
import mindustry.type.Planet;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exports the original v160.8 campaign globe meshes from the official desktop JAR. */
public final class UpstreamCampaignPlanetMeshes {
    public static void main(String[] args) throws IOException, ReflectiveOperationException {
        if (args.length != 1) throw new IllegalArgumentException("Expected an output directory");
        Path output = Path.of(args[0]);
        Files.createDirectories(output);

        // Keep the original float normals path. The mock GL only supplies buffer handles.
        ArcNativesLoader.load();
        Core.app = new MockApplication();
        Core.files = new MockFiles();
        Core.gl = Core.gl20 = new MockGL20();
        Core.settings = new Settings();
        Vars.headless = true;
        Vars.state = new GameState();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        Vars.content.loadColors();

        // Source-file launch mode uses a different class loader from the desktop JAR.
        var meshField = PlanetMesh.class.getDeclaredField("mesh");
        meshField.setAccessible(true);
        for (Planet planet : List.of(Planets.serpulo, Planets.erekir)) {
            var stock = (PlanetMesh)planet.meshLoader.get();
            try {
                Mesh mesh = (Mesh)meshField.get(stock);
                write(mesh, output.resolve(planet.name + ".m3pm"));
                if (planet == Planets.serpulo) writeSurfaceSamples(planet, mesh, output.resolve("serpulo.m3ps"));
            } finally {
                stock.dispose();
            }
        }
    }

    private static void writeSurfaceSamples(Planet planet, Mesh mesh, Path file) throws IOException {
        int remaining = mesh.getNumVertices();
        var grid = PlanetGrid.create(6);
        int count = 0;
        for (var tile : grid.tiles) {
            if (!planet.generator.skip(tile.v)) {
                remaining -= tile.corners.length;
                count++;
            }
        }
        if (remaining != 0) throw new IOException("Serpulo sample order does not cover the stock mesh");

        try (var stream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            stream.writeBytes("M3PS");
            stream.writeInt(1);
            stream.writeInt(planet.sectors.size);
            stream.writeInt(count);
            for (var sector : planet.sectors) {
                stream.writeInt((sector.hasEnemyBase() ? 1 : 0) | (sector.isCaptured() ? 2 : 0)
                    | (sector.hasBase() ? 4 : 0));
                stream.writeFloat(sector.info.lightCoverage);
            }
            var vertices = mesh.getVerticesBuffer().duplicate().rewind();
            var tint = new Color();
            int vertex = 0;
            for (var tile : grid.tiles) {
                if (planet.generator.skip(tile.v)) continue;
                planet.generator.getColor(tile.v, tint.set(1f, 1f, 1f, 1f));
                int color = Float.floatToRawIntBits(tint.toFloatBits());
                planet.generator.getEmissiveColor(tile.v, tint.set(0f, 0f, 0f, 0f));
                int glow = Float.floatToRawIntBits(tint.toFloatBits());
                for (int i = 0; i < tile.corners.length; i++, vertex++) {
                    if (color != Float.floatToRawIntBits(vertices.get(vertex * 8 + 6))
                        || glow != Float.floatToRawIntBits(vertices.get(vertex * 8 + 7))) {
                        throw new IOException("Serpulo generator samples differ from the stock mesh at " + tile.id);
                    }
                }
                stream.writeByte(tile.corners.length);
                stream.writeFloat(tile.v.x);
                stream.writeFloat(tile.v.y);
                stream.writeFloat(tile.v.z);
            }
        }
        System.out.println("UPSTREAM_PLANET_SURFACE " + file.getFileName() + " samples=" + count
            + " vertices=" + mesh.getNumVertices());
    }

    private static void write(Mesh mesh, Path file) throws IOException {
        int stride = mesh.vertexSize / Float.BYTES;
        if (!(stride == 7 || stride == 8) || mesh.attributes.length != (stride == 7 ? 3 : 4)
                || mesh.attributes[0] != VertexAttribute.position3 || mesh.attributes[1] != VertexAttribute.normal
                || mesh.attributes[2] != VertexAttribute.color) {
            throw new IOException("Unexpected stock planet vertex format for " + file);
        }

        var vertices = mesh.getVerticesBuffer().duplicate().rewind();
        var indices = mesh.getIndicesBuffer().duplicate().rewind();
        int count = vertices.remaining() / stride;
        if (count == 0 || count > 65535 || vertices.remaining() % stride != 0
                || indices.remaining() < 3 || indices.remaining() % 3 != 0) {
            throw new IOException("Expected the indexed stock hex mesh for " + file);
        }

        try (var stream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            stream.writeBytes("M3PM");
            stream.writeInt(Integer.reverseBytes(1));
            stream.writeInt(Integer.reverseBytes(stride));
            stream.writeInt(Integer.reverseBytes(count));
            stream.writeInt(Integer.reverseBytes(indices.remaining()));
            while (vertices.hasRemaining()) {
                stream.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(vertices.get())));
            }
            while (indices.hasRemaining()) {
                short index = indices.get();
                if (Short.toUnsignedInt(index) >= count) {
                    throw new IOException("Stock mesh index exceeds the vertex count for " + file);
                }
                stream.writeShort(Short.reverseBytes(index));
            }
        }
        System.out.println("UPSTREAM_PLANET_MESH " + file.getFileName() + " vertices=" + count
            + " indices=" + indices.limit() + " stride=" + stride);
    }
}
