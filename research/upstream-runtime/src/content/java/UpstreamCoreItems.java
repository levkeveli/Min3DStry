import java.util.ArrayList;
import java.util.List;
import arc.struct.ObjectSet;
import java.util.function.Function;
import mindustry.Vars;
import mindustry.type.Item;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/** Collects the local core's non-zero items in content order for the HUD. */
public final class UpstreamCoreItems {
    public record Entry(Item item, int amount) {
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
