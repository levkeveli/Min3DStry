package mindustry.graphics.g3d;

import arc.Core;
import arc.Settings;
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
import mindustry.type.Planet;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Exports the original v160.8 campaign globe meshes from the official desktop JAR. */
public final class UpstreamCampaignPlanetMeshes {
    public static void main(String[] args) throws IOException {
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

        for (Planet planet : List.of(Planets.serpulo, Planets.erekir)) {
            var stock = (PlanetMesh)planet.meshLoader.get();
            try {
                write(stock.mesh, output.resolve(planet.name + ".m3pm"));
            } finally {
                stock.dispose();
            }
        }
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
