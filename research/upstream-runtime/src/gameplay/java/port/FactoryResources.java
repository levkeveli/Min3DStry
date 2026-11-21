package port;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.teavm.extension.Autoregistered;
import org.teavm.extension.spi.resources.ResourcesPolicy;

@Autoregistered
public final class FactoryResources implements ResourcesPolicy {
    @Override
    public String[] supplyResources(Collection<? extends String> availableClassNames) {
        var resources = new ArrayList<>(List.of("logicids.dat", "basepartnames"));
        try (var stream = FactoryResources.class.getResourceAsStream("/basepartnames")) {
            if (stream == null) {
                throw new IllegalStateException("Missing pinned basepartnames resource");
            }
            var names = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            for (String name : names.split("\\n")) {
                resources.add("baseparts/" + name);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read pinned basepartnames resource", error);
        }
        return resources.toArray(String[]::new);
    }
}
