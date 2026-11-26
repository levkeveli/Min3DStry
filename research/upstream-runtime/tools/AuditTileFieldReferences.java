import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Reject precompiled callers of fields removed by the stock tile patch. */
public final class AuditTileFieldReferences {
    private static final Set<String> TILE_TYPES = Set.of(
        "mindustry/world/Tile", "mindustry/world/CachedTile", "mindustry/editor/EditorTile");
    private static final Set<String> REMOVED_FIELDS = Set.of("block", "floor", "overlay", "changing", "extraData");

    private AuditTileFieldReferences() {}

    public static void main(String[] args) throws IOException {
        try (var jar = new ZipFile(args[0])) {
            var entries = jar.stream().filter(entry -> entry.getName().endsWith(".class")).toList();
            var parents = new HashMap<String, String>();
            for (var entry : entries) {
                try (var input = jar.getInputStream(entry)) {
                    var reader = new ClassReader(input);
                    parents.put(reader.getClassName(), reader.getSuperName());
                }
            }

            var failures = new ArrayList<String>();
            for (var entry : entries) {
                try (var input = jar.getInputStream(entry)) {
                    var reader = new ClassReader(input);
                    reader.accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                         String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override
                                public void visitFieldInsn(int opcode, String owner, String field, String type) {
                                    if (REMOVED_FIELDS.contains(field) && isTile(owner, parents)) {
                                        failures.add(reader.getClassName() + "." + name + " reads or writes " + owner + "." + field);
                                    }
                                }
                            };
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException("Unstaged stock Tile field callers:\n" + String.join("\n", failures));
            }
            System.out.println("UPSTREAM_TILE_FIELD_AUDIT precompiled_classes=" + entries.size() + " old_field_calls=0");
        }
    }

    private static boolean isTile(String owner, Map<String, String> parents) {
        for (var type = owner; type != null; type = parents.get(type)) {
            if (TILE_TYPES.contains(type)) return true;
        }
        return false;
    }
}
