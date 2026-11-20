package port.concurrent;

import org.teavm.extension.Autoregistered;
import org.teavm.extension.spi.substitution.SubstitutionPolicy;
import org.teavm.extension.spi.substitution.SubstitutionSink;

@Autoregistered
public final class ConcurrentSubstitutions implements SubstitutionPolicy {
    @Override
    public void contribute(SubstitutionSink sink) {
        sink.substitutePackage("java.util.concurrent", "port.concurrent");
    }
}
