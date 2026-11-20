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
            "mindustry.game.Rules$TeamRules",
            "mindustry.game.SpawnGroup",
            "mindustry.type.MapLocales",
            "mindustry.type.Weather$WeatherEntry",
            "arc.math.geom.Vec2"
        }) {
            selectClass(type).reflectableMethods(withParameterCount(0).and(IntrospectMethod::isConstructor))
                .reflectableFields(field -> INSTANCE.test(field) && !TRANSIENT.test(field));
        }
        // Older maps name filters by their fully qualified upstream class name.
        selectClasses(inPackage("mindustry.maps.filters").and(extending("mindustry.maps.filters.GenerateFilter")))
            .foundByName()
            .reflectableMethods(withParameterCount(0).and(IntrospectMethod::isConstructor))
            .reflectableFields(field -> INSTANCE.test(field) && !TRANSIENT.test(field));
    }
}
