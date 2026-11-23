import arc.Core;
import mindustry.content.StatusEffects;
import mindustry.game.Rules;
import mindustry.game.SpawnGroup;

/** v160.8 HudFragment guardian-wave warning scan. */
public final class UpstreamGuardianWarning {
    private UpstreamGuardianWarning() {
    }

    public static String find(Rules rules, int wave) {
        if (rules == null || Core.bundle == null) return null;
        int winWave = rules.winWave > 0 ? rules.winWave : Integer.MAX_VALUE;
        for (int i = wave - 1; i <= Math.min(wave + 10, winWave - 2); i++) {
            for (SpawnGroup group : rules.spawns) {
                if (group.effect == StatusEffects.boss && group.getSpawned(i) > 0) {
                    int diff = (i + 2) - wave;
                    if (diff == 1 || diff == 2 || diff == 5 || diff == 10) {
                        return Core.bundle.format(
                            "wave.guardianwarn" + (diff == 1 ? ".one" : ""), diff);
                    }
                }
            }
        }
        return null;
    }
}
