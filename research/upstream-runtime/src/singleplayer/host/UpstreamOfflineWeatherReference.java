import arc.Core;
import arc.files.Fi;
import arc.graphics.Blending;
import arc.graphics.Camera;
import arc.util.Time;
import mindustry.Vars;
import mindustry.content.Weathers;
import mindustry.gen.Groups;
import port.render.WeatherNoiseTexture;

/** Check the same weather entities through the offline session's original save path. */
public final class UpstreamOfflineWeatherReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private int draws, noiseMask;

    public static void main(String[] args) throws Exception {
        var clear = new Fi(args[0]);
        var weather = new Fi(args[1]);
        var atlas = new Fi(args[2]);
        var saved = new Fi(args[3]);
        var session = UpstreamSinglePlayerSession.create(saved.parent(), "en");
        try {
            if (!session.start(null, clear, null) || !Groups.weather.isEmpty()) {
                throw new AssertionError("Expected a clear stock Ground Zero save");
            }
            UpstreamTerrainDrawProbe.loadAtlas(atlas, null);
            var layers = new UpstreamWeatherLayers();
            layers.load();
            var target = new UpstreamOfflineWeatherReference();
            UpstreamTerrainDrawProbe.liveViewport(target);
            Core.camera = new Camera();
            Core.camera.position.set(session.core().x, session.core().y);
            Core.camera.width = UpstreamTerrainDrawProbe.VIEW_WIDTH * Vars.tilesize;
            Core.camera.height = UpstreamTerrainDrawProbe.VIEW_HEIGHT * Vars.tilesize;
            Time.delta = 1f;
            layers.advance();
            layers.drawUnder();
            layers.drawOver();
            if (target.draws != 0) throw new AssertionError("Clear weather rendered an overlay");

            if (!session.start(null, weather, null)) throw new AssertionError("Stock weather save did not load");
            float rainLife = checkEvents();
            session.stepSimulation(0f, 0f);
            float updatedLife = checkEvents();
            if (updatedLife != rainLife - 1f) throw new AssertionError("Original weather did not advance once");
            session.save(saved);
            if (!session.start(null, saved, null) || checkEvents() != updatedLife) {
                throw new AssertionError("Weather state changed across offline save and reload");
            }
            Time.delta = 1f;
            for (int frame = 0; frame < 60; frame++) layers.advance();
            layers.drawUnder();
            layers.drawOver();
            if (target.draws <= 2 || target.noiseMask != 3) {
                throw new AssertionError("Loaded weather did not render particles and both original noise textures");
            }
            System.out.println("UPSTREAM_OFFLINE_WEATHER_REFERENCE map=Ground_Zero events=3 draws=" + target.draws
                + " noise=2 save=ok");
        } finally {
            session.shutdown();
        }
    }

    private static float checkEvents() {
        var rain = Weathers.rain.instance();
        if (Groups.weather.size() != 3 || rain == null || Weathers.sandstorm.instance() == null
                || Weathers.fog.instance() == null || rain.opacity() < 0.75f) {
            throw new AssertionError("Stock rain, sandstorm and fog state did not survive the save");
        }
        return rain.life();
    }

    @Override public void begin(int left, int bottom, int width, int height) {}
    @Override public void end() {}

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mix, Blending blending) {
        draws++;
    }

    @Override
    public void drawSolidQuad(float[] vertices, int offset, Blending blending) {
        draws++;
    }

    @Override
    public void drawWeatherNoise(WeatherNoiseTexture texture, float x, float y, float width, float height,
                                 float u, float v, float u2, float v2, int tint, int mix, Blending blending) {
        noiseMask |= 1 << texture.index;
        draws++;
    }
}
