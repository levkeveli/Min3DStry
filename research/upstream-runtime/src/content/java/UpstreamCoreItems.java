import java.util.ArrayList;
import java.util.List;
import arc.struct.ObjectSet;
import java.util.function.Function;
import mindustry.Vars;
import arc.Core;
import arc.util.Strings;
import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/** Collects the local core's non-zero items in content order for the HUD. */
public final class UpstreamCoreItems {
    public record Entry(Item item, int amount) {
        public String formatted() {
            return formatAmount(amount);
        }
    }

    private UpstreamCoreItems() {
    }

    public static List<Entry> collect(List<Item> items, Function<Item, Integer> amount) {
        var rows = new ArrayList<Entry>();
        for (Item item : items) {
            int count = amount.apply(item);
            if (count > 0) rows.add(new Entry(item, count));
        }
        return rows;
    }

    /**
     * Mirrors pinned v160.8 UI.formatAmount thresholds and bundle suffixes.
     * The stock formatter prefixes the suffix with [lightgray]; this label path
     * omits color markup because the 3DS row renderer applies one color.
     */
    static String formatAmount(int amount) {
        long value = Math.max(0, amount);
        if (value >= 1_000_000_000L) {
            return Strings.fixed(value / 1_000_000_000f, 1) + Core.bundle.get("unit.billions");
        }
        if (value >= 1_000_000L) {
            return Strings.fixed(value / 1_000_000f, 1) + Core.bundle.get("unit.millions");
        }
        if (value >= 10_000L) {
            return (value / 1_000L) + Core.bundle.get("unit.thousands");
        }
        if (value >= 1_000L) {
            return Strings.fixed(value / 1_000f, 1) + Core.bundle.get("unit.thousands");
        }
        return Long.toString(value);
    }

    public static List<Entry> live() {
        var core = core();
        return core == null ? List.of() : collect(Vars.content.items().list(), item -> core.items.get(item));
    }

    /** Stock used-items behavior: once seen, a depleted item stays visible at zero. */
    public static final class Tracker {
        private final ObjectSet<Item> used = new ObjectSet<>();

        public List<Entry> collect(List<Item> items, Function<Item, Integer> amount) {
            var rows = new ArrayList<Entry>();
            for (Item item : items) {
                int count = amount.apply(item);
                if (count > 0) used.add(item);
                if (used.contains(item)) rows.add(new Entry(item, count));
            }
            return rows;
        }

        public List<Entry> live() {
            var core = core();
            return core == null ? List.of() : collect(Vars.content.items().list(), item -> core.items.get(item));
        }

        public void reset() {
            used.clear();
        }
    }

    private static CoreBuild core() {
        var player = Vars.player;
        return player == null || player.team() == null ? null : player.team().core();
    }
}
