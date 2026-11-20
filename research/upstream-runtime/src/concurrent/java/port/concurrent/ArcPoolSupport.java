package port.concurrent;

import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.ExitInstruction;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

public final class ArcPoolSupport implements TeaVMPlugin {
    @Override
    public void install(TeaVMHost host) {
        host.add((type, context) -> {
            if (!type.getName().equals("arc.util.Threads")) {
                return;
            }
            for (var method : type.getMethods()) {
                String name = method.getName();
                if (method.parameterCount() != 2 ||
                        (!name.equals("boundedExecutor") && !name.equals("unboundedExecutor"))) {
                    continue;
                }
                var body = new Program();
                body.createVariable();
                var threadName = body.createVariable();
                var workerCount = body.createVariable();
                var result = body.createVariable();
                var block = body.createBasicBlock();

                var call = new InvokeInstruction();
                String factory = name.equals("boundedExecutor") ? "bounded" : "unbounded";
                call.setMethod(new MethodReference("java.util.concurrent.ArcPools", factory,
                    ValueType.object("java.lang.String"), ValueType.INTEGER,
                    ValueType.object("java.util.concurrent.ExecutorService")));
                call.setType(InvocationType.SPECIAL);
                call.setArguments(threadName, workerCount);
                call.setReceiver(result);
                block.add(call);
                var exit = new ExitInstruction();
                exit.setValueToReturn(result);
                block.add(exit);
                method.setProgram(body);
            }
        });
    }
}
