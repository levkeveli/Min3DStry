import arc.Core;
import arc.graphics.g2d.Draw;
import arc.math.Mathf;
import mindustry.content.Weathers;
import mindustry.gen.Groups;
import mindustry.graphics.Layer;

/** Submits the same underlay and overlay as the upstream WeatherState draw method. */
public final class UpstreamWeatherLayers {
    private float alpha;

    void load() {
        Weathers.rain.load();
        Weathers.snow.load();
        Weathers.sandstorm.load();
        Weathers.sporestorm.load();
        Weathers.fog.load();
        Weathers.suspendParticles.load();
    }

    void reset() {
        alpha = 0f;
    }

    void advance() {
        alpha = Mathf.lerpDelta(alpha, 1f, 0.08f);
    }

    void drawUnder() {
        draw(true);
    }

    void drawOver() {
        draw(false);
    }

    private void draw(boolean under) {
        if (alpha <= 0.0001f || !Core.settings.getBool("showweather", true)) return;
        float previousDepth = Draw.z();
        Draw.z(under ? Layer.debris : Layer.weather);
        try {
            for (var state : Groups.weather) {
                var weather = state.weather();
                Draw.alpha(alpha * state.opacity() * weather.opacityMultiplier);
                try {
                    if (under) weather.drawUnder(state);
                    else weather.drawOver(state);
                } finally {
                    Draw.reset();
                }
            }
        } finally {
            Draw.z(previousDepth);
        }
    }
}
