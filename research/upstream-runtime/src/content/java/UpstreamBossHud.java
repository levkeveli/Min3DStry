import arc.Core;
import mindustry.Vars;
import mindustry.gen.Unit;

/** Stock HudFragment guardian/boss summary for the live HUD. */
public final class UpstreamBossHud {
    /** HudFragment caps the displayed boss emoji at six and then appends a plus. */
    public static final int maxBosses = 6;

    public record Summary(int count, float health, float maxHealth, String markup) {
        public float fraction() {
            if (maxHealth <= 0f) return 0f;
            return Math.max(0f, Math.min(1f, health / maxHealth));
        }
    }

    private UpstreamBossHud() {
    }

    /** Returns the stock guardian bar data, or null when no boss is active. */
    public static Summary collect() {
        var state = Vars.state;
        if (state == null || state.teams == null || !state.rules.waves) return null;
        var bosses = state.teams.bosses;
        if (bosses == null || bosses.isEmpty()) return null;
        float max = 0f, value = 0f;
        var text = new StringBuilder();
        int count = 0;
        for (Unit boss : bosses) {
            if (boss == null) continue;
            max += boss.maxHealth;
            value += boss.health;
            if (count < maxBosses) text.append(boss.type.emoji());
            count++;
        }
        if (count == 0) return null;
        if (count > maxBosses) text.append("[accent]+[]");
        text.append(' ').append(Core.bundle.get("guardian"));
        return new Summary(count, value, max, text.toString());
    }
}
