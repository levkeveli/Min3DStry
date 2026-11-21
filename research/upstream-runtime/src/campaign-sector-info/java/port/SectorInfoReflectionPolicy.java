package port;

import java.util.Collection;
import java.util.List;
import org.teavm.extension.introspect.IntrospectClass;
import org.teavm.extension.introspect.IntrospectMember;
import org.teavm.extension.spi.reflection.ReflectionPolicy;

/** Exposes upstream SectorInfo fields to Arc's JSON struct reader on TeaVM. */
public final class SectorInfoReflectionPolicy implements ReflectionPolicy {
    @Override
    public Collection<IntrospectMember> classAccessibleMembers(IntrospectClass<?> type){
        if(type.name().equals("mindustry.game.SectorInfo")){
            return List.copyOf(type.declaredFields());
        }
        return List.of();
    }
}
