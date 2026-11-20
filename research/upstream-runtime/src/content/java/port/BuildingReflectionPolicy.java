package port;

import org.teavm.extension.Autoregistered;
import org.teavm.extension.introspect.IntrospectMethod;
import org.teavm.extension.spi.reflection.SimpleReflectionPolicy;

@Autoregistered
public final class BuildingReflectionPolicy extends SimpleReflectionPolicy {
    @Override
    protected void setup() {
        // Upstream Block.initBuilding looks up the constructor that takes the enclosing block.
        selectClasses(inPackage("mindustry.world.blocks", true).and(extending("mindustry.gen.Building")))
            .reflectableMethods(withParameterCount(1).and(IntrospectMethod::isConstructor));
        // JsonIO declares the item types of these collections during static initialization.
        selectClass("mindustry.game.Rules").reflectableFields(namedEither("spawns", "loadout"));
    }
}
