import arc.Core;
import arc.Settings;
import arc.graphics.Blending;
import arc.graphics.Camera;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.SpriteBatch;
import arc.math.Mathf;
import arc.mock.MockGraphics;
import arc.util.Time;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Weathers;
import mindustry.core.NetClient;
import mindustry.gen.Groups;
import mindustry.gen.WeatherState;
import mindustry.graphics.Layer;
import mindustry.world.blocks.environment.Floor;
import port.render.WeatherNoiseTexture;

final class UpstreamWeatherLayersReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private final List<String> commands = new ArrayList<>();

    static void verify() {
        var camera = Core.camera;
        var settings = Core.settings;
        var graphics = Core.graphics;
        var batch = Core.batch;
        var client = Vars.netClient;
        float time = Time.time, delta = Time.delta;
        var layers = new UpstreamWeatherLayers();
        var target = new UpstreamWeatherLayersReference();
        if (!Groups.weather.isEmpty()) throw new AssertionError("Expected clear weather in the fixture");
        try {
            Core.camera = new Camera();
            Core.camera.position.set(648f, 208f);
            Core.camera.width = 128f;
            Core.camera.height = 96f;
            Core.graphics = new MockGraphics() {
                @Override public int getWidth() { return 400; }
                @Override public int getHeight() { return 240; }
                @Override public float getDensity() { return 1f; }
            };
            Core.settings = new Settings();
            Core.settings.put("showweather", true);
            Vars.netClient = NetClient.forSnapshotDispatch();
            UpstreamTerrainDrawProbe.liveViewport(target);
            layers.load();
            Time.delta = 1f;
            float alpha = 0f;
            for (int tick = 0; tick < 60; tick++) {
                layers.advance();
                alpha = Mathf.lerpDelta(alpha, 1f, 0.08f);
            }
            var stock = List.of(Weathers.rain, Weathers.snow, Weathers.sandstorm, Weathers.sporestorm,
                                Weathers.fog, Weathers.suspendParticles);
            for (var weather : stock) {
                var state = weather.create(1f, 1200f);
                state.opacity(0.8f);
                state.windVector().set(0.8f, 0.2f);
                try {
                    Time.time = 207f;
                    target.compare(layers, state, alpha, true);
                    int under = target.commands.size();
                    if (weather == Weathers.rain) target.verifyWaterSplashes(layers, state, alpha);
                    target.compare(layers, state, alpha, false);
                    int over = target.commands.size();
                    List<String> first = List.copyOf(target.commands);
                    Time.time = 268f;
                    target.compare(layers, state, alpha, false);
                    if (over == 0 || first.equals(target.commands) || (weather == Weathers.rain && under == 0)) {
                        throw new AssertionError("Incomplete stock weather geometry for " + weather.name);
                    }
                    System.out.println("UPSTREAM_WEATHER_LAYER_REFERENCE name=" + weather.name
                        + " under=" + under + " over=" + over + " animation=ok");
                    Core.settings.put("showweather", false);
                    target.commands.clear();
                    layers.drawUnder();
                    layers.drawOver();
                    if (!target.commands.isEmpty()) throw new AssertionError("Disabled weather still drew");
                    Core.settings.put("showweather", true);
                } finally {
                    state.remove();
                }
            }
        } finally {
            Core.camera = camera;
            Core.settings = settings;
            Core.graphics = graphics;
            Core.batch = batch;
            Vars.netClient = client;
            Time.time = time;
            Time.delta = delta;
        }
    }

    private void verifyWaterSplashes(UpstreamWeatherLayers layers, WeatherState rain, float alpha) {
        int left = 72, bottom = 18, columns = 19, rows = 17;
        var floors = new Floor[rows][columns];
        try {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < columns; x++) {
                    var tile = Vars.world.tile(left + x, bottom + y);
                    floors[y][x] = tile.floor();
                    tile.setFloor(Blocks.water.asFloor());
                }
            }
            compare(layers, rain, alpha, true);
            long splashes = commands.stream().filter(command -> command.startsWith("sprite ")).count();
            if (splashes == 0) throw new AssertionError("Original rain did not use the stock water splash atlas");
            System.out.println("UPSTREAM_WEATHER_WATER_SPLASHES sprites=" + splashes);
        } finally {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < columns; x++) {
                    if (floors[y][x] != null) Vars.world.tile(left + x, bottom + y).setFloor(floors[y][x]);
                }
            }
        }
    }

    private void compare(UpstreamWeatherLayers layers, WeatherState state, float alpha, boolean under) {
        commands.clear();
        Draw.z(under ? Layer.debris : Layer.weather);
        Draw.alpha(alpha * state.opacity() * state.weather().opacityMultiplier);
        if (under) state.weather().drawUnder(state);
        else state.weather().drawOver(state);
        Draw.reset();
        List<String> expected = List.copyOf(commands);
        commands.clear();
        if (under) layers.drawUnder();
        else layers.drawOver();
        if (!expected.equals(commands)) {
            throw new AssertionError("Stock weather draw submission differed for " + state.weather().name);
        }
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void end() {}

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mix, Blending blending) {
        commands.add("sprite " + page + "," + left + "," + top + "," + width + "," + height + ","
            + x + "," + y + "," + originX + "," + originY + "," + drawWidth + "," + drawHeight + ","
            + rotation + "," + color + "," + mix + "," + Draw.z());
    }

    @Override
    public void drawSolidQuad(float[] vertices, int offset, Blending blending) {
        commands.add("line " + Arrays.toString(Arrays.copyOfRange(vertices, offset, offset + SpriteBatch.SPRITE_SIZE))
            + "," + Draw.z());
    }

    @Override
    public void drawWeatherNoise(WeatherNoiseTexture texture, float x, float y, float width, float height,
                                 float u, float v, float u2, float v2, int tint, int mix, Blending blending) {
        if (width != Core.camera.width || height != -Core.camera.height || u == u2 || v == v2) {
            throw new AssertionError("Weather noise did not preserve the stock camera quad");
        }
        commands.add("noise " + texture.index + "," + x + "," + y + "," + width + "," + height + ","
            + u + "," + v + "," + u2 + "," + v2 + "," + tint + "," + mix + "," + Draw.z());
    }
}
