import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Keep upstream marker scheduling and drawing, adapting text and texture lookup to the target. */
final class StageObjectiveLabelAdapter {
    static void stage(Path jar, Path output) throws Exception {
        try (var archive = new ZipFile(jar.toFile())) {
            stageTouchText(archive, output);
            stageTextureLookup(archive, output, "TextureMarker", false);
            stageTextureLookup(archive, output, "QuadMarker", true);
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

    private static final String markerTextureAdapter = "port/render/UpstreamMarkerTexture";
    private static final String textureHolder = "mindustry/game/MapObjectives$TextureHolder";

    private static void stageTextureLookup(ZipFile archive, Path output, String name, boolean quad) throws Exception {
        var path = "mindustry/game/MapObjectives$" + name + ".class";
        var type = new ClassNode();
        try (var bytes = archive.getInputStream(archive.getEntry(path))) {
            new ClassReader(bytes).accept(type, 0);
        }
        var setTexture = type.methods.stream()
            .filter(method -> method.name.equals("setTexture") && method.desc.equals("(Ljava/lang/Object;)V"))
            .findFirst().orElseThrow();
        int guards = 0, lookups = 0;
        for (var instruction : setTexture.instructions.toArray()) {
            if (readsHeadless(instruction)) {
                setTexture.instructions.insertBefore(instruction, new VarInsnNode(Opcodes.ALOAD, 1));
                replaceHeadless(setTexture, instruction);
                guards++;
            } else if (instruction instanceof MethodInsnNode lookup && lookup.getOpcode() == Opcodes.INVOKESTATIC
                    && lookup.owner.equals("mindustry/game/MapObjectives") && lookup.name.equals("lookupRegion")
                    && lookup.desc.equals("(Ljava/lang/Object;Larc/graphics/g2d/TextureRegion;)V")) {
                lookup.owner = markerTextureAdapter;
                lookup.name = "lookup";
                lookups++;
            }
        }
        if (guards != 1 || lookups != 1) throw new IllegalStateException("Unexpected stock texture lookup in " + path);
        if (quad) stageQuadUv(type);
        write(type, output.resolve(path));
    }

    private static void stageQuadUv(ClassNode type) {
        var setUv = type.methods.stream()
            .filter(method -> method.name.equals("setUv") && method.desc.equals("(IDD)V"))
            .findFirst().orElseThrow();
        int guards = 0, loads = 0;
        for (var instruction : setUv.instructions.toArray()) {
            if (readsHeadless(instruction)) {
                setUv.instructions.insertBefore(instruction, new VarInsnNode(Opcodes.ALOAD, 0));
                setUv.instructions.insertBefore(instruction, new FieldInsnNode(Opcodes.GETFIELD, type.name,
                    "texture", "L" + textureHolder + ";"));
                setUv.instructions.insertBefore(instruction, readTextureValue());
                replaceHeadless(setUv, instruction);
                guards++;
            } else if (instruction instanceof FieldInsnNode holder && holder.getOpcode() == Opcodes.GETFIELD
                    && holder.owner.equals(type.name) && holder.name.equals("texture")
                    && holder.desc.equals("L" + textureHolder + ";")) {
                // UV can arrive before the first frame. Initialize from the original
                // texture value instead of replacing it with its own Java holder.
                setUv.instructions.insert(holder, readTextureValue());
                loads++;
            }
        }
        if (guards != 1 || loads != 1) throw new IllegalStateException("Unexpected stock quad UV lookup");
    }

    private static FieldInsnNode readTextureValue() {
        return new FieldInsnNode(Opcodes.GETFIELD, textureHolder, "value", "Ljava/lang/Object;");
    }

    private static boolean readsHeadless(AbstractInsnNode instruction) {
        return instruction instanceof FieldInsnNode flag && flag.getOpcode() == Opcodes.GETSTATIC
            && flag.owner.equals("mindustry/Vars") && flag.name.equals("headless") && flag.desc.equals("Z");
    }

    private static void replaceHeadless(MethodNode method, AbstractInsnNode flag) {
        method.instructions.set(flag, new MethodInsnNode(Opcodes.INVOKESTATIC, markerTextureAdapter,
            "deferLookup", "(Ljava/lang/Object;)Z", false));
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
