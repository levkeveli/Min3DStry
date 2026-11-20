package port;

import org.teavm.dependency.AbstractDependencyListener;
import org.teavm.dependency.DependencyAgent;
import org.teavm.model.ElementModifier;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.instructions.ConstructInstruction;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.RaiseInstruction;
import org.teavm.model.instructions.StringConstantInstruction;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

public final class WorldProbeSupport implements TeaVMPlugin {
    @Override
    public void install(TeaVMHost host) {
        // The C optimizer emits a direct call in DataPatcher after trimming this method.
        String textureRegion = "arc.graphics.g2d.TextureRegion";
        var found = new MethodReference(textureRegion, "found", ValueType.BOOLEAN);
        host.add(new AbstractDependencyListener() {
            @Override
            public void started(DependencyAgent agent) {
                var dependency = agent.linkMethod(found);
                dependency.getVariable(0).propagate(agent.getType(ValueType.object(textureRegion)));
                dependency.use();
            }
        });
        host.add((type, context) -> {
            if (!type.getName().equals("arc.audio.Soloud") && !type.getName().equals("arc.util.Buffers")) {
                return;
            }
            for (var method : type.getMethods()) {
                if (!method.getModifiers().remove(ElementModifier.NATIVE)) {
                    continue;
                }
                // C reachability includes rendering and audio jobs from the shared
                // executor. Fail explicitly if a headless world invokes those APIs.
                var body = new Program();
                for (int i = 0; i <= method.parameterCount(); i++) {
                    body.createVariable();
                }
                var message = body.createVariable();
                var error = body.createVariable();
                var block = body.createBasicBlock();

                var detail = new StringConstantInstruction();
                detail.setReceiver(message);
                detail.setConstant("Native call in headless world: " + method.getReference());
                block.add(detail);
                var allocate = new ConstructInstruction();
                allocate.setType(AssertionError.class.getName());
                allocate.setReceiver(error);
                block.add(allocate);
                var init = new InvokeInstruction();
                init.setMethod(new MethodReference(AssertionError.class, "<init>", Object.class, void.class));
                init.setType(InvocationType.SPECIAL);
                init.setInstance(error);
                init.setArguments(message);
                block.add(init);
                var fail = new RaiseInstruction();
                fail.setException(error);
                block.add(fail);
                method.setProgram(body);
            }
        });
    }
}
