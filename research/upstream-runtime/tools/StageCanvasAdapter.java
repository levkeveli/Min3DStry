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
import org.objectweb.asm.tree.TypeInsnNode;

/** Keep the original Canvas pixels, invalidation and draw order. Supply the platform texture. */
final class StageCanvasAdapter {
    static void stage(Path jar, Path output) throws Exception {
        String path = "mindustry/world/blocks/logic/CanvasBlock$CanvasBuild.class";
        var type = new ClassNode();
        try (var archive = new ZipFile(jar.toFile()); var bytes = archive.getInputStream(archive.getEntry(path))) {
            new ClassReader(bytes).accept(type, 0);
        }
        String texture = "arc/graphics/Texture";
        String adapter = "port/render/UpstreamCanvasTexture";
        var update = type.methods.stream()
            .filter(method -> method.name.equals("updateTexture") && method.desc.equals("()V"))
            .findFirst().orElseThrow();
        int guards = 0, allocations = 0, constructors = 0;
        for (var instruction : update.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC
                    && field.owner.equals("mindustry/Vars") && field.name.equals("headless") && field.desc.equals("Z")) {
                update.instructions.set(field, new InsnNode(Opcodes.ICONST_0));
                guards++;
            } else if (instruction instanceof TypeInsnNode allocation && allocation.getOpcode() == Opcodes.NEW
                    && allocation.desc.equals(texture)) {
                allocation.desc = adapter;
                allocations++;
            } else if (instruction instanceof MethodInsnNode constructor && constructor.getOpcode() == Opcodes.INVOKESPECIAL
                    && constructor.owner.equals(texture) && constructor.name.equals("<init>")
                    && constructor.desc.equals("(Larc/graphics/Pixmap;)V")) {
                constructor.owner = adapter;
                constructors++;
            }
        }
        if (guards != 1 || allocations != 1 || constructors != 1) {
            throw new IllegalStateException("Unexpected stock Canvas texture initialization");
        }

        var draw = type.methods.stream()
            .filter(method -> method.name.equals("draw") && method.desc.equals("()V"))
            .findFirst().orElseThrow();
        int displays = 0;
        for (var instruction : draw.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode flag && flag.getOpcode() == Opcodes.GETFIELD
                    && flag.owner.equals("mindustry/core/Renderer") && flag.name.equals("drawDisplays")
                    && flag.desc.equals("Z")) {
                draw.instructions.insertBefore(flag, new InsnNode(Opcodes.POP));
                draw.instructions.set(flag, new InsnNode(Opcodes.ICONST_1));
                displays++;
            }
        }
        if (displays != 1) throw new IllegalStateException("Unexpected stock Canvas display toggle");
        var writer = new ClassWriter(0);
        type.accept(writer);
        Files.createDirectories(output.resolve(path).getParent());
        Files.write(output.resolve(path), writer.toByteArray());
    }
}
