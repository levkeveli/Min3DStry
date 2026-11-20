package port;

import org.teavm.dependency.AbstractDependencyListener;
import org.teavm.dependency.DependencyAgent;
import org.teavm.model.MethodReference;
import org.teavm.model.ValueType;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

public final class ResourceVirtualMethods implements TeaVMPlugin {
    @Override
    public void install(TeaVMHost host) {
        // TeaVM 0.16.0 emits resource keys in C after dependency analysis.
        // The resource table still needs virtual String.hashCode dispatch.
        MethodReference hashCode = new MethodReference(String.class, "hashCode", int.class);
        host.addVirtualMethods(hashCode::equals);
        host.add(new AbstractDependencyListener() {
            @Override
            public void started(DependencyAgent agent) {
                var dependency = agent.linkMethod(hashCode);
                dependency.getVariable(0).propagate(agent.getType(ValueType.object("java.lang.String")));
                dependency.use();
            }
        });
    }
}
