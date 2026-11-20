package port;

import org.teavm.extension.Autoregistered;
import org.teavm.extension.introspect.IntrospectMethod;
import org.teavm.extension.spi.reflection.SimpleReflectionPolicy;

@Autoregistered
public final class WorldReflectionPolicy extends SimpleReflectionPolicy {
    @Override
    protected void setup() {
        // SaveVersion reads these metadata types through upstream Arc Json.
        for (String type : new String[] {
            "mindustry.game.GameStats",
            "mindustry.game.Rules",
            "mindustry.game.Rules$TeamRule",
            "mindustry.type.Weather$WeatherEntry",
            "arc.math.geom.Vec2"
        }) {
            selectClass(type).reflectableMethods(withParameterCount(0).and(IntrospectMethod::isConstructor))
                .reflectableFields(field -> INSTANCE.test(field) && !TRANSIENT.test(field));
        }
        // These classes read their fields through the JsonSerializable interface.
        for (String type : new String[] {
            "mindustry.game.Rules$TeamRules",
            "mindustry.game.SpawnGroup",
            "mindustry.type.MapLocales",
            "mindustry.world.blocks.Attributes"
        }) {
            selectClass(type).reflectableMethods(withParameterCount(0).and(IntrospectMethod::isConstructor));
        }
        // Older maps name filters by their fully qualified upstream class name.
        selectClasses(inPackage("mindustry.maps.filters").and(extending("mindustry.maps.filters.GenerateFilter")))
            .foundByName()
            .reflectableMethods(withParameterCount(0).and(IntrospectMethod::isConstructor))
            .reflectableFields(field -> INSTANCE.test(field) && !TRANSIENT.test(field));
    }
}
