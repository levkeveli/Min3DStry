import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Keep upstream marker scheduling and drawing, adapting text and texture lookup to the target. */
final class StageObjectiveLabelAdapter {
    static void stage(Path jar, Path output) throws Exception {
        try (var archive = new ZipFile(jar.toFile())) {
            stageTouchText(archive, output);
            stageTextureLookup(archive, output);
            for (String name : new String[]{"ShapeTextMarker", "TextMarker"}) {
                var path = "mindustry/game/MapObjectives$" + name + ".class";
                var type = new ClassNode();
                try (var bytes = archive.getInputStream(archive.getEntry(path))) {
                    new ClassReader(bytes).accept(type, 0);
                }
                var draw = type.methods.stream()
                    .filter(method -> method.name.equals("draw") && method.desc.equals("(F)V"))
                    .findFirst().orElseThrow();
                int redirects = 0;
                for (var instruction : draw.instructions) {
                    if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.equals("mindustry/gen/WorldLabel") && call.name.equals("drawAt")
                            && call.desc.equals("(Ljava/lang/String;FFFIFII)V")) {
                        call.owner = "port/render/UpstreamWorldLabel";
                        redirects++;
                    }
                }
                if (redirects != 1) throw new IllegalStateException("Unexpected stock drawAt calls in " + path);
                write(type, output.resolve(path));
            }
        }
    }

    private static void stageTextureLookup(ZipFile archive, Path output) throws Exception {
        var path = "mindustry/game/MapObjectives$TextureMarker.class";
        var type = new ClassNode();
        try (var bytes = archive.getInputStream(archive.getEntry(path))) {
            new ClassReader(bytes).accept(type, 0);
        }
        var setTexture = type.methods.stream()
            .filter(method -> method.name.equals("setTexture") && method.desc.equals("(Ljava/lang/Object;)V"))
            .findFirst().orElseThrow();
        int guards = 0, lookups = 0;
        String adapter = "port/render/UpstreamMarkerTexture";
        for (var instruction : setTexture.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode flag && flag.getOpcode() == Opcodes.GETSTATIC
                    && flag.owner.equals("mindustry/Vars") && flag.name.equals("headless") && flag.desc.equals("Z")) {
                setTexture.instructions.insertBefore(flag, new VarInsnNode(Opcodes.ALOAD, 1));
                setTexture.instructions.set(flag, new MethodInsnNode(Opcodes.INVOKESTATIC, adapter,
                    "deferLookup", "(Ljava/lang/Object;)Z", false));
                guards++;
            } else if (instruction instanceof MethodInsnNode lookup && lookup.getOpcode() == Opcodes.INVOKESTATIC
                    && lookup.owner.equals("mindustry/game/MapObjectives") && lookup.name.equals("lookupRegion")
                    && lookup.desc.equals("(Ljava/lang/Object;Larc/graphics/g2d/TextureRegion;)V")) {
                lookup.owner = adapter;
                lookup.name = "lookup";
                lookups++;
            }
        }
        if (guards != 1 || lookups != 1) throw new IllegalStateException("Unexpected stock texture marker lookup");
        write(type, output.resolve(path));
    }

    private static void stageTouchText(ZipFile archive, Path output) throws Exception {
        var path = "mindustry/game/MapObjectives$ObjectiveMarker.class";
        var type = new ClassNode();
        try (var bytes = archive.getInputStream(archive.getEntry(path))) {
            new ClassReader(bytes).accept(type, 0);
        }
        var fetch = type.methods.stream()
            .filter(method -> method.name.equals("fetchText") && method.desc.equals("(Ljava/lang/String;)Ljava/lang/String;"))
            .findFirst().orElseThrow();
        int redirects = 0;
        for (var instruction : fetch.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode mobile && mobile.getOpcode() == Opcodes.GETSTATIC
                    && mobile.owner.equals("mindustry/Vars") && mobile.name.equals("mobile") && mobile.desc.equals("Z")) {
                // Select the upstream touch-control prompt without changing gameplay's platform flag.
                fetch.instructions.set(mobile, new InsnNode(Opcodes.ICONST_1));
                redirects++;
            }
        }
        if (redirects != 1) throw new IllegalStateException("Unexpected stock marker input-variant lookup");
        write(type, output.resolve(path));
    }

    private static void write(ClassNode type, Path path) throws Exception {
        var writer = new ClassWriter(0);
        type.accept(writer);
        Files.createDirectories(path.getParent());
        Files.write(path, writer.toByteArray());
    }
}
