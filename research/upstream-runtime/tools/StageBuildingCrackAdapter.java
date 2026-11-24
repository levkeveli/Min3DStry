import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** Redirect only the crack texture lookup in the pinned generated Building class. */
public final class StageBuildingCrackAdapter {
    private static final String BUILDING = "mindustry/gen/Building.class";
    private static final String REGIONS = "[[Larc/graphics/g2d/TextureRegion;";

    public static void main(String[] args) throws Exception {
        var building = new ClassNode();
        try (var jar = new ZipFile(args[0]); var bytes = jar.getInputStream(jar.getEntry(BUILDING))) {
            new ClassReader(bytes).accept(building, 0);
        }
        var draw = building.methods.stream()
            .filter(method -> method.name.equals("drawCracks") && method.desc.equals("()V"))
            .findFirst().orElseThrow();
        int redirects = 0;
        for (var instruction : draw.instructions.toArray()) {
            if (!(instruction instanceof FieldInsnNode renderer)
                    || renderer.getOpcode() != Opcodes.GETSTATIC || !renderer.owner.equals("mindustry/Vars")
                    || !renderer.name.equals("renderer") || !renderer.desc.equals("Lmindustry/core/Renderer;")) {
                continue;
            }
            if (!(renderer.getNext() instanceof FieldInsnNode blocks)
                    || blocks.getOpcode() != Opcodes.GETFIELD || !blocks.owner.equals("mindustry/core/Renderer")
                    || !blocks.name.equals("blocks") || !blocks.desc.equals("Lmindustry/graphics/BlockRenderer;")
                    || !(blocks.getNext() instanceof FieldInsnNode cracks)
                    || cracks.getOpcode() != Opcodes.GETFIELD || !cracks.owner.equals("mindustry/graphics/BlockRenderer")
                    || !cracks.name.equals("cracks") || !cracks.desc.equals(REGIONS)) {
                throw new IllegalStateException("The pinned Building.drawCracks field lookup changed");
            }
            draw.instructions.set(renderer, new MethodInsnNode(Opcodes.INVOKESTATIC,
                "port/render/UpstreamCrackAtlas", "regions", "()" + REGIONS, false));
            draw.instructions.remove(blocks);
            draw.instructions.remove(cracks);
            redirects++;
        }
        if (redirects != 1) throw new IllegalStateException("Expected one pinned crack texture lookup, got " + redirects);
        // The three fields and static call have the same net stack type at the next instruction.
        sizeProximityStorage(building);
        var writer = new ClassWriter(0);
        building.accept(writer);
        var output = Path.of(args[1]).resolve(BUILDING);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
        StageNavalWakeAdapter.stage(Path.of(args[0]), Path.of(args[1]));
        StageObjectiveLabelAdapter.stage(Path.of(args[0]), Path.of(args[1]));
        StageCanvasAdapter.stage(Path.of(args[0]), Path.of(args[1]));
    }

    private static void sizeProximityStorage(ClassNode building) {
        int matches = 0;
        for (var constructor : building.methods) {
            if (!constructor.name.equals("<init>")) continue;
            for (var instruction : constructor.instructions.toArray()) {
                if (!(instruction instanceof FieldInsnNode field)
                        || field.getOpcode() != Opcodes.PUTFIELD
                        || !field.owner.equals(building.name)
                        || !field.name.equals("proximity")
                        || !field.desc.equals("Larc/struct/Seq;")) continue;
                if (!(previousOpcode(field) instanceof MethodInsnNode allocation)
                        || allocation.getOpcode() != Opcodes.INVOKESPECIAL
                        || !allocation.owner.equals("arc/struct/Seq")
                        || !allocation.name.equals("<init>")
                        || !allocation.desc.equals("(ZILjava/lang/Class;)V")
                        || !(previousOpcode(previousOpcode(allocation)) instanceof IntInsnNode capacity)
                        || capacity.getOpcode() != Opcodes.BIPUSH || capacity.operand != 6) {
                    throw new IllegalStateException("The pinned Building proximity allocation changed");
                }
                // Arc grows empty lists to eight entries on their first insertion.
                // Four slots cover most stock buildings without that growth.
                constructor.instructions.set(capacity, new InsnNode(Opcodes.ICONST_4));
                matches++;
            }
        }
        if (matches != 1) throw new IllegalStateException("Expected one pinned proximity allocation, got " + matches);
    }

    private static AbstractInsnNode previousOpcode(AbstractInsnNode instruction) {
        var previous = instruction.getPrevious();
        while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
        return previous;
    }
}
