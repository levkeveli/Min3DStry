package port.audio;

import arc.Core;
import arc.Settings;
import arc.assets.AssetManager;
import arc.audio.Music;
import arc.audio.AudioBus;
import arc.audio.Sound;
import arc.graphics.Camera;
import arc.mock.MockAudio;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.noise.Noise;
import mindustry.Vars;
import mindustry.audio.MusicContainer;
import mindustry.audio.SoundControl;
import mindustry.core.GameState;
import mindustry.core.NetClient;
import mindustry.content.Blocks;
import mindustry.content.Weathers;
import mindustry.game.Rules;
import mindustry.game.Team;
import mindustry.gen.Musics;
import mindustry.gen.Groups;
import mindustry.gen.SetRuleCallPacket;
import mindustry.gen.SetRulesCallPacket;
import mindustry.gen.Sounds;
import mindustry.type.Weather;

import java.util.HashSet;

public final class Arm11AudioReference {
    public static void verifyStockRegistry() {
        var assets = Core.assets;
        var stock = Arm11Audio.bindStockAudio(true);
        if (Core.assets != assets) throw new AssertionError("Stock registry loader replaced shared assets");

        var paths = new HashSet<String>();
        for (int id = 0; id < 205; id++) {
            Sound sound = Sounds.getSound(id);
            if (Sounds.getSoundId(sound) != id || sound.file == null || !sound.valid()
                    || !paths.add(sound.file.path())) {
                throw new AssertionError("Stock sound did not round-trip at ID " + id);
            }
        }
        if (Sounds.getSound(117) != Sounds.shootAlpha || Sounds.getSound(185) != Sounds.uiButton
                || Sounds.getSound(198) != Sounds.waveSpawn) {
            throw new AssertionError("Generated sound fields and packet IDs disagree");
        }
        String[] tracks = {"boss1", "boss2", "editor", "fine", "game1", "game2", "game3", "game4",
                           "game5", "game6", "game7", "game8", "game9", "land", "launch", "menu"};
        Core.assets = stock;
        try {
            for (int i = 0; i < tracks.length; i++) {
                String path = "music/" + tracks[i] + ".ogg";
                if (!(SoundControl.findMusic(tracks[i]) instanceof Arm11Music track)
                        || track.track != i || !track.file.path().equals(path) || stock.get(path, Music.class) != track) {
                    throw new AssertionError("Original soundtrack path or order differs at " + path);
                }
            }
            if (Musics.game1 != SoundControl.findMusic("game1") || Musics.menu != SoundControl.findMusic("menu")) {
                throw new AssertionError("Generated Music fields and stock rule lookup disagree");
            }
            verifyStockRulePlayback(stock);
            verifyStockBuildingAmbience();
            verifyStockWeatherAmbience();
        } finally {
            Core.assets = assets;
            stock.dispose();
        }
        System.out.println("UPSTREAM_LIVE_SOUND_REGISTRY ids=" + paths.size() + " originals=117,185,198");
        System.out.println("UPSTREAM_LIVE_MUSIC_REGISTRY tracks=" + tracks.length + " game1=4 menu=15");
    }

    private static void verifyStockRulePlayback(AssetManager stock) {
        var state = Vars.state;
        var settings = Core.settings;
        var music = new RecordingMusic();
        stock.addAsset("music/game1.ogg", Music.class, music);
        try {
            Core.settings = new Settings();
            Core.settings.defaults("musicvol", 100);
            Vars.state = new GameState();
            Vars.state.set(GameState.State.playing);
            var rules = new Rules();
            rules.disableMusic = true;
            rules.alwaysPlayMusic = true;
            rules.musicVolume = 0.375f;
            rules.ambientMusic = Seq.with(new MusicContainer("game1"));
            rules.darkMusic = Seq.with(new MusicContainer("game1"));
            var allRules = new SetRulesCallPacket();
            allRules.rules = rules;
            allRules.handleClient();

            var controller = new SoundControl() {
                { reload(); }
            };
            controller.stop();
            controller.update();
            if (music.isPlaying()) throw new AssertionError("Stock disabled-music rules played a track");

            var enable = new SetRuleCallPacket();
            enable.rule = "disableMusic";
            enable.jsonData = "false";
            enable.handleClient();
            controller.update();
            if (controller.getCurrent() != music || !music.isPlaying() || music.isLooping()
                    || music.getVolume() != 0.375f) {
                throw new AssertionError("Stock SoundControl differed after the rule packets");
            }
            controller.stop();
            if (music.isPlaying()) throw new AssertionError("Stock controller did not stop its track");
            System.out.println("UPSTREAM_LIVE_MUSIC_RULE_REFERENCE id=4 volume=0.375 stop=ok");
        } finally {
            Vars.state = state;
            Core.settings = settings;
            stock.addAsset("music/game1.ogg", Music.class, Musics.game1);
        }
    }

    private static final class RecordingMusic extends Music {
        private boolean playing;
        private boolean loop;
        private float gain;

        @Override public void play() { playing = true; }
        @Override public void stop() { playing = false; }
        @Override public boolean isPlaying() { return playing; }
        @Override public boolean isLooping() { return loop; }
        @Override public void setLooping(boolean looping) { loop = looping; }
        @Override public void setVolume(float volume) { gain = volume; }
        @Override public float getVolume() { return gain; }
    }

    private static void verifyStockBuildingAmbience() {
        var audio = Core.audio;
        var camera = Core.camera;
        var settings = Core.settings;
        var state = Vars.state;
        var oldSound = Blocks.mechanicalDrill.ambientSound;
        float delta = Time.delta;
        var tile = Vars.world.tile(76, 29);
        if (tile.build != null) throw new AssertionError("Expected an unoccupied drill fixture tile");
        var oldBlock = tile.block();
        var loop = new RecordingLoop();
        try {
            Core.audio = new MockAudio() {
                @Override public boolean initialized() { return true; }
                @Override public boolean isPlaying(int voice) { return voice == 1 && loop.playing; }
            };
            Core.camera = new Camera();
            Core.camera.width = Core.camera.height = 128f;
            Core.settings = new Settings();
            Core.settings.defaults("musicvol", 0, "ambientvol", 100);
            Vars.state = new GameState();
            Vars.state.set(GameState.State.playing);
            Time.delta = 1f;
            Blocks.mechanicalDrill.ambientSound = loop;
            tile.setBlock(Blocks.mechanicalDrill, Team.sharded);
            var drill = tile.build;
            Core.camera.position.set(drill.x + 32f, drill.y);
            var controller = new SoundControl();
            var buildings = Seq.with(drill);

            drill.efficiency = 0f;
            controller.sampleLiveBuildingAmbience(buildings, 0);
            for (int tick = 0; tick < 30; tick++) controller.update();
            if (loop.starts != 0) throw new AssertionError("An idle stock drill played its ambience");

            drill.efficiency = 1f;
            controller.sampleLiveBuildingAmbience(buildings, 49_000_000L);
            for (int tick = 0; tick < 30; tick++) controller.update();
            if (loop.starts != 0) throw new AssertionError("The sampler ran before the stock 20 Hz interval");

            controller.sampleLiveBuildingAmbience(buildings, 67_000_000L);
            for (int tick = 0; tick < 30; tick++) controller.update();
            if (loop.starts != 1 || !loop.playing) throw new AssertionError("The stock drill loop did not start");

            tile.setBlock(Blocks.air);
            controller.sampleLiveBuildingAmbience(buildings, 99_000_000L);
            for (int tick = 0; tick < 120; tick++) controller.update();
            if (!loop.playing) throw new AssertionError("The sampler advanced before the original deadline");
            controller.sampleLiveBuildingAmbience(buildings, 100_000_000L);
            for (int tick = 0; tick < 120; tick++) controller.update();
            if (loop.playing || loop.stops != 1) throw new AssertionError("Removed drill loop did not fade out and stop");
            System.out.println("UPSTREAM_BUILDING_AMBIENCE_REFERENCE drill=ok cadence=20 pan=-0.5 removal=ok");
        } finally {
            tile.setBlock(oldBlock);
            Blocks.mechanicalDrill.ambientSound = oldSound;
            Core.audio = audio;
            Core.camera = camera;
            Core.settings = settings;
            Vars.state = state;
            Time.delta = delta;
        }
    }

    private static final class RecordingLoop extends Sound {
        int starts;
        int stops;
        boolean playing;

        @Override public boolean valid() { return true; }

        @Override
        protected int playSource(AudioBus bus, float volume, float pitch, float pan, boolean looping) {
            if (!looping || volume <= 0f || pitch != 1f || pan != -0.5f) {
                throw new AssertionError("Stock drill loop parameters changed");
            }
            starts++;
            playing = true;
            return 1;
        }

        @Override public void stop() { stops++; playing = false; }
    }

    private static void verifyStockWeatherAmbience() {
        var audio = Core.audio;
        var camera = Core.camera;
        var settings = Core.settings;
        var state = Vars.state;
        var netClient = Vars.netClient;
        float delta = Time.delta, time = Time.time;
        if (!Groups.weather.isEmpty()) throw new AssertionError("Expected clear weather in the fixture");
        try {
            Core.camera = new Camera();
            Core.camera.width = Core.camera.height = 128f;
            Core.settings = new Settings();
            Core.settings.defaults("musicvol", 0, "ambientvol", 100);
            Vars.state = new GameState();
            Vars.state.set(GameState.State.playing);
            Vars.netClient = NetClient.forSnapshotDispatch();
            Time.delta = 1f;
            Time.time = 128f;
            verifyWeather(Weathers.rain, 105);
            verifyWeather(Weathers.sandstorm, 199);
            verifyWeather(Weathers.sporestorm, 199);
            verifyWeather(Weathers.snow, 202);
            System.out.println("UPSTREAM_WEATHER_AMBIENCE_REFERENCE rain=105 wind=199 snow=202 expiry=ok");
        } finally {
            Groups.weather.clear();
            Core.audio = audio;
            Core.camera = camera;
            Core.settings = settings;
            Vars.state = state;
            Vars.netClient = netClient;
            Time.delta = delta;
            Time.time = time;
        }
    }

    private static void verifyWeather(Weather weather, int stockId) {
        Sound stockSound = weather.sound;
        if (Sounds.getSoundId(stockSound) != stockId) {
            throw new AssertionError("Unexpected stock sound for " + weather.name);
        }
        var loop = new RecordingWeatherLoop();
        Core.audio = new MockAudio() {
            @Override public boolean initialized() { return true; }
            @Override public boolean isPlaying(int voice) { return voice == 1 && loop.playing; }
            @Override public void set(int voice, float pan, float gain) {
                if (voice == 1) loop.mix(pan, gain);
            }
        };
        var control = new SoundControl() {
            @Override protected boolean acceptsWorldLoops() { return true; }
        };
        try {
            weather.sound = loop;
            var event = weather.create(1f, 600f);
            event.windVector.setZero();
            event.opacity(0.5f);
            float expected = 0f;
            for (int tick = 0; tick < 45; tick++) {
                Groups.weather.update();
                weather.updateSound(event, control);
                control.update();
                float fluctuation = (float)Math.abs(Noise.rawNoise(Time.time / weather.soundVolOscScl))
                    * weather.soundVolOscMag;
                float stockGain = Math.max((weather.soundVol + fluctuation) * event.opacity(), weather.soundVolMin);
                expected += (stockGain - expected) * 0.11f;
                if (expected > 0.01f && (!loop.playing || Math.abs(expected - loop.gain) > 0.0001f)) {
                    throw new AssertionError("Stock weather gain differs for " + weather.name);
                }
            }
            event.life(0f);
            Groups.weather.update();
            for (int tick = 0; tick < 120; tick++) control.update();
            if (event.isAdded() || loop.starts != 1 || loop.playing || loop.stops != 1) {
                throw new AssertionError("Stock weather loop failed to expire for " + weather.name);
            }
        } finally {
            weather.remove();
            weather.sound = stockSound;
        }
    }

    private static final class RecordingWeatherLoop extends Sound {
        int starts, stops;
        boolean playing;
        float gain;

        @Override public boolean valid() { return true; }

        @Override
        protected int playSource(AudioBus bus, float volume, float pitch, float pan, boolean looping) {
            if (!looping || pitch != 1f) throw new AssertionError("Expected original weather loop and pitch");
            mix(pan, volume);
            starts++;
            playing = true;
            return 1;
        }

        void mix(float pan, float volume) {
            if (Math.abs(pan) > 0.0001f) throw new AssertionError("Weather loop drifted from the camera center");
            gain = volume;
        }

        @Override public void stop() { stops++; playing = false; }
    }
}
