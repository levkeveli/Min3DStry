package port.audio;

import arc.Core;
import arc.Settings;
import arc.assets.AssetManager;
import arc.audio.Music;
import arc.audio.Sound;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.audio.MusicContainer;
import mindustry.audio.SoundControl;
import mindustry.core.GameState;
import mindustry.game.Rules;
import mindustry.gen.Musics;
import mindustry.gen.SetRuleCallPacket;
import mindustry.gen.SetRulesCallPacket;
import mindustry.gen.Sounds;

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

            var controller = new SoundControl();
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
}
