package port;

import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

public final class AnonymousClassSupport implements TeaVMPlugin {
    private static final MethodReference QUERY = new MethodReference(Class.class, "isAnonymousClass", boolean.class);
    private static final MethodReference REPLACEMENT = new MethodReference(
        "port.ClassShape", "isAnonymous", ValueType.object("java.lang.Class"), ValueType.BOOLEAN);

    @Override
    public void install(TeaVMHost host) {
        host.add((type, context) -> {
            for (var method : type.getMethods()) {
                var body = method.getProgram();
                if (body == null) continue;
                for (int i = 0; i < body.basicBlockCount(); i++) {
                    for (var insn = body.basicBlockAt(i).getFirstInstruction(); insn != null; insn = insn.getNext()) {
                        if (insn instanceof InvokeInstruction invoke && invoke.getMethod().equals(QUERY)) {
                            var target = invoke.getInstance();
                            invoke.setMethod(REPLACEMENT);
                            invoke.setType(InvocationType.SPECIAL);
                            invoke.setInstance(null);
                            invoke.setArguments(target);
                        }
                    }
                }
            }
        });
    }
}
