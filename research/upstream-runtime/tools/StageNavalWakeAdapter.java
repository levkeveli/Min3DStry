import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Expose the pinned WaterMoveComp wake update without advancing the unit simulation. */
final class StageNavalWakeAdapter {
    private static final String TYPE = "mindustry/gen/UnitWaterMove";
    private static final String CLASS_FILE = TYPE + ".class";

    static void stage(Path referenceJar, Path destination) throws Exception {
        var boat = new ClassNode();
        try (var jar = new ZipFile(referenceJar.toFile()); var bytes = jar.getInputStream(jar.getEntry(CLASS_FILE))) {
            new ClassReader(bytes).accept(boat, 0);
        }
        var update = boat.methods.stream().filter(m -> m.name.equals("update") && m.desc.equals("()V"))
            .findFirst().orElseThrow();
        MethodInsnNode flying = null;
        boolean left = false, right = false, trailUpdate = false;
        for (var node : update.instructions.toArray()) {
            if (node instanceof MethodInsnNode call && call.owner.equals(TYPE)
                    && call.name.equals("isFlying") && call.desc.equals("()Z")) {
                flying = call;
                left = right = trailUpdate = false;
            } else if (node instanceof FieldInsnNode field && field.owner.equals(TYPE)) {
                left |= field.name.equals("tleft");
                right |= field.name.equals("tright");
            } else if (node instanceof MethodInsnNode call && call.owner.equals("mindustry/graphics/Trail")
                    && call.name.equals("update") && call.desc.equals("(FFF)V")) {
                trailUpdate = true;
            }
        }
        if (!left || !right || !trailUpdate || flying == null
                || !(flying.getPrevious() instanceof VarInsnNode receiver)
                || receiver.getOpcode() != Opcodes.ALOAD || receiver.var != 0) {
            throw new IllegalStateException("Expected the pinned WaterMoveComp wake loop at the end of UnitWaterMove.update");
        }

        var wake = new MethodNode(Opcodes.ACC_PUBLIC, "advanceWake", "()V", null, null);
        var labels = new IdentityHashMap<LabelNode, LabelNode>();
        for (AbstractInsnNode node = receiver; node != null; node = node.getNext()) {
            if (node instanceof LabelNode label) labels.put(label, new LabelNode());
        }
        for (AbstractInsnNode node = receiver; node != null; node = node.getNext()) {
            if (!(node instanceof FrameNode || node instanceof LineNumberNode)) {
                wake.instructions.add(node.clone(labels));
            }
        }
        boat.methods.add(wake);

        try (var loader = new URLClassLoader(new URL[]{referenceJar.toUri().toURL()})) {
            var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                @Override protected ClassLoader getClassLoader() { return loader; }
            };
            boat.accept(writer);
            var output = destination.resolve(CLASS_FILE);
            Files.createDirectories(output.getParent());
            Files.write(output, writer.toByteArray());
        }
    }
}
