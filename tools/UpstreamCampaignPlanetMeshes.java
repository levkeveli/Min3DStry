import arc.Core;
import arc.Settings;
import arc.graphics.Color;
import arc.graphics.Mesh;
import arc.graphics.VertexAttribute;
import arc.math.geom.Mat3D;
import arc.math.geom.Vec3;
import arc.mock.MockApplication;
import arc.mock.MockFiles;
import arc.mock.MockGL20;
import arc.util.ArcNativesLoader;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.graphics.g3d.GenericMesh;
import mindustry.graphics.g3d.HexSkyMesh;
import mindustry.graphics.g3d.MatMesh;
import mindustry.graphics.g3d.MeshBuilder;
import mindustry.graphics.g3d.MultiMesh;
import mindustry.graphics.g3d.PlanetGrid;
import mindustry.graphics.g3d.PlanetMesh;
import mindustry.type.Planet;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Exports the original v160.8 campaign globe meshes from the official desktop JAR. */
public final class UpstreamCampaignPlanetMeshes {
    private record MeshPart(Mesh mesh, Mat3D transform) {}

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
        exportVisible(Planets.sun, output);

        // PlanetRenderer draws this original shell with depth testing and no depth writes.
        var atmosphere = MeshBuilder.buildHex(Color.white, 2, 1.5f);
        try {
            write(List.of(new MeshPart(atmosphere, null)), output.resolve("atmosphere.m3pm"));
        } finally {
            atmosphere.dispose();
        }
    }

    private static void exportVisible(Planet planet, Path output) throws IOException, ReflectiveOperationException {
        if (!planet.visible()) return;
        var stock = planet.meshLoader.get();
        try {
            var parts = new ArrayList<MeshPart>();
            collectParts(stock, null, parts);
            write(parts, output.resolve(planet.name + ".m3pm"));
            if (planet == Planets.serpulo) {
                writeSurfaceSamples(planet, parts.get(0).mesh(), output.resolve("serpulo.m3ps"));
            }
        } finally {
            stock.dispose();
        }
        exportClouds(planet, output);
        for (Planet child : planet.children) {
            exportVisible(child, output);
        }
    }

    private static void exportClouds(Planet planet, Path output) throws IOException, ReflectiveOperationException {
        var stock = planet.cloudMeshLoader.get();
        if (stock == null) return;
        try {
            var layers = stock instanceof MultiMesh multi
                ? (GenericMesh[])field(MultiMesh.class, "meshes").get(multi) : new GenericMesh[]{stock};
            try (var stream = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(output.resolve(planet.name + ".m3pc"))))) {
                stream.writeBytes("M3PC");
                stream.writeInt(1);
                stream.writeInt(layers.length);
                for (int index = 0; index < layers.length; index++) {
                    if (!(layers[index] instanceof HexSkyMesh cloud)) {
                        throw new IOException("Unexpected stock cloud mesh for " + planet.name);
                    }
                    var mesh = (Mesh)field(PlanetMesh.class, "mesh").get(cloud);
                    write(List.of(new MeshPart(mesh, null)), output.resolve(planet.name + "-cloud-" + index + ".m3pm"));
                    stream.writeFloat(cloud.speed);
                    System.out.println("UPSTREAM_PLANET_CLOUD planet=" + planet.name + " layer=" + index + " speed=" + cloud.speed);
                }
            }
        } finally {
            stock.dispose();
        }
    }

    private static void collectParts(GenericMesh stock, Mat3D parent, List<MeshPart> parts)
            throws ReflectiveOperationException, IOException {
        if (stock instanceof PlanetMesh mesh) {
            parts.add(new MeshPart((Mesh)field(PlanetMesh.class, "mesh").get(mesh), parent));
        } else if (stock instanceof MultiMesh multi) {
            for (var child : (GenericMesh[])field(MultiMesh.class, "meshes").get(multi)) {
                collectParts(child, parent, parts);
            }
        } else if (stock instanceof MatMesh transformed) {
            var matrix = (Mat3D)field(MatMesh.class, "mat").get(transformed);
            var combined = parent == null ? matrix : new Mat3D(parent).mul(matrix);
            collectParts((GenericMesh)field(MatMesh.class, "mesh").get(transformed), combined, parts);
        } else {
            throw new IOException("Unexpected stock campaign mesh " + stock.getClass().getName());
        }
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
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

    private static void write(List<MeshPart> parts, Path file) throws IOException {
        if (parts.isEmpty()) throw new IOException("Missing stock mesh for " + file);
        int stride = parts.get(0).mesh().vertexSize / Float.BYTES;
        int count = 0, indexCount = 0;
        for (MeshPart part : parts) {
            Mesh mesh = part.mesh();
            if (!(stride == 7 || stride == 8) || mesh.vertexSize != stride * Float.BYTES
                    || mesh.attributes.length != (stride == 7 ? 3 : 4)
                    || mesh.attributes[0] != VertexAttribute.position3 || mesh.attributes[1] != VertexAttribute.normal
                    || mesh.attributes[2] != VertexAttribute.color) {
                throw new IOException("Unexpected stock planet vertex format for " + file);
            }
            int vertices = mesh.getVerticesBuffer().limit(), indices = mesh.getIndicesBuffer().limit();
            if (vertices == 0 || vertices % stride != 0 || indices < 3 || indices % 3 != 0) {
                throw new IOException("Expected the indexed stock hex mesh for " + file);
            }
            count += vertices / stride;
            indexCount += indices;
        }
        if (count > 65535) throw new IOException("Stock mesh exceeds 16-bit vertex indices for " + file);

        try (var stream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            stream.writeBytes("M3PM");
            stream.writeInt(Integer.reverseBytes(1));
            stream.writeInt(Integer.reverseBytes(stride));
            stream.writeInt(Integer.reverseBytes(count));
            stream.writeInt(Integer.reverseBytes(indexCount));
            var vertex = new Vec3();
            for (MeshPart part : parts) {
                var vertices = part.mesh().getVerticesBuffer().duplicate().rewind();
                while (vertices.hasRemaining()) {
                    if (part.transform() != null) {
                        vertex.set(vertices.get(), vertices.get(), vertices.get());
                        Mat3D.prj(vertex, part.transform());
                        writeVector(stream, vertex);
                        vertex.set(vertices.get(), vertices.get(), vertices.get());
                        Mat3D.rot(vertex, part.transform());
                        writeVector(stream, vertex);
                    } else {
                        for (int i = 0; i < 6; i++) {
                            stream.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(vertices.get())));
                        }
                    }
                    for (int i = 6; i < stride; i++) {
                        stream.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(vertices.get())));
                    }
                }
            }
            int offset = 0;
            for (MeshPart part : parts) {
                int vertices = part.mesh().getVerticesBuffer().limit() / stride;
                var indices = part.mesh().getIndicesBuffer().duplicate().rewind();
                while (indices.hasRemaining()) {
                    int index = Short.toUnsignedInt(indices.get());
                    if (index >= vertices) {
                        throw new IOException("Stock mesh index exceeds the vertex count for " + file);
                    }
                    stream.writeShort(Short.reverseBytes((short)(index + offset)));
                }
                offset += vertices;
            }
        }
        System.out.println("UPSTREAM_PLANET_MESH " + file.getFileName() + " vertices=" + count
            + " indices=" + indexCount + " stride=" + stride);
    }

    private static void writeVector(DataOutputStream stream, Vec3 vertex) throws IOException {
        stream.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(vertex.x)));
        stream.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(vertex.y)));
        stream.writeInt(Integer.reverseBytes(Float.floatToRawIntBits(vertex.z)));
    }
}
