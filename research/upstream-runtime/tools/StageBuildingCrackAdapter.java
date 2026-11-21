import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
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
        var writer = new ClassWriter(0);
        building.accept(writer);
        var output = Path.of(args[1]).resolve(BUILDING);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
        StageNavalWakeAdapter.stage(Path.of(args[0]), Path.of(args[1]));
        StageObjectiveLabelAdapter.stage(Path.of(args[0]), Path.of(args[1]));
    }
}
