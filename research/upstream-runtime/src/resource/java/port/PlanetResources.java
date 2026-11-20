package port;

import java.util.Collection;
import org.teavm.extension.Autoregistered;
import org.teavm.extension.spi.resources.ResourcesPolicy;

@Autoregistered
public final class PlanetResources implements ResourcesPolicy {
    @Override
    public String[] supplyResources(Collection<? extends String> availableClassNames) {
        return new String[]{"planets/serpulo.json"};
    }
}
