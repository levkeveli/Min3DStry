package port.concurrent;

import org.teavm.extension.Autoregistered;
import org.teavm.extension.spi.substitution.SubstitutionPolicy;
import org.teavm.extension.spi.substitution.SubstitutionSink;

@Autoregistered
public final class ConcurrentSubstitutions implements SubstitutionPolicy {
    static final String RUNTIME_PACKAGE = "java.util.concurrent";

    @Override
    public void contribute(SubstitutionSink sink) {
        sink.substitutePackage(RUNTIME_PACKAGE, "port.concurrent");
    }
}
