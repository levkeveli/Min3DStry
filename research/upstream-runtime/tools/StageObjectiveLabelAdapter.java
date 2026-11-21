import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Keep upstream marker scheduling, shapes and localization. Swap only the world text backend. */
final class StageObjectiveLabelAdapter {
    static void stage(Path jar, Path output) throws Exception {
        try (var archive = new ZipFile(jar.toFile())) {
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
                var writer = new ClassWriter(0);
                type.accept(writer);
                var staged = output.resolve(path);
                Files.createDirectories(staged.getParent());
                Files.write(staged, writer.toByteArray());
            }
        }
    }
}
