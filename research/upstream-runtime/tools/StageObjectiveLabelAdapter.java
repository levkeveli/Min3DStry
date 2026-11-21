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

/** Keep upstream marker scheduling, shapes and localization. Swap only the world text backend. */
final class StageObjectiveLabelAdapter {
    static void stage(Path jar, Path output) throws Exception {
        try (var archive = new ZipFile(jar.toFile())) {
            stageTouchText(archive, output);
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
