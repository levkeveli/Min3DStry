package port.audio;

import arc.Core;
import arc.audio.Sound;
import mindustry.gen.Sounds;

import java.util.HashSet;

public final class Arm11AudioReference {
    public static void verifyStockRegistry() {
        var assets = Core.assets;
        Arm11Audio.bindStockSounds();
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
        System.out.println("UPSTREAM_LIVE_SOUND_REGISTRY ids=" + paths.size() + " originals=117,185,198");
    }
}
