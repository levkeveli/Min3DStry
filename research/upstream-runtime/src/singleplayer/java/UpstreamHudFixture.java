import arc.files.Fi;
import mindustry.Vars;
import mindustry.type.Item;

/** Seeds the local core from an automation fixture for HUD verification. */
final class UpstreamHudFixture {
    private UpstreamHudFixture() {
    }

    static void apply() {
        Fi file = new Fi("/3ds/min3dstry/hud-items.txt");
        if (!file.exists()) return;
        var core = Vars.player == null || Vars.player.team() == null ? null : Vars.player.team().core();
        if (core == null) return;
        int applied = 0;
        for (String line : file.readString("UTF-8").split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int split = line.indexOf('=');
            if (split <= 0) continue;
            Item item = Vars.content.item(line.substring(0, split).trim());
            if (item == null) continue;
            int amount;
            try {
                amount = Integer.parseInt(line.substring(split + 1).trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
            core.items.set(item, Math.max(0, amount));
            applied++;
        }
        System.out.println("UPSTREAM_HUD_ITEMS applied=" + applied);
    }
}
