import arc.Core;
import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.content.TechTree;
import mindustry.content.TechTree.TechNode;
import mindustry.game.EventType.ResearchEvent;
import mindustry.game.EventType.SectorCaptureEvent;
import mindustry.game.EventType.UnlockEvent;
import mindustry.type.Item;
import mindustry.type.ItemSeq;
import mindustry.type.Planet;
import mindustry.type.Sector;

/**
 * ARM11 adaptation of the pinned v160.8 ResearchDialog research rules.
 * Upstream: core/src/mindustry/ui/dialogs/ResearchDialog.java at 19a0f40.
 */
final class UpstreamCampaignResearch {
    private static boolean autoUnlocksInstalled;

    private final TechNode root;
    private final ObjectMap<Sector, ItemSeq> inventory = new ObjectMap<>();
    private final ItemSeq total = new ItemSeq();

    UpstreamCampaignResearch(TechNode root) {
        this.root = root;
        Seq<Planet> planets = new Seq<>(false, 4);
        for (Planet planet : Vars.content.planets()) {
            if (planet.techTree == root || root.planet == planet) planets.add(planet);
        }
        if (planets.isEmpty()) planets.add(Planets.serpulo);

        for (Planet planet : planets) {
            for (Sector sector : planet.sectors) {
                if (!sector.hasBase() || sector.isFrozen()) continue;
                ItemSeq items = sector.items();
                inventory.put(sector, items);
                items.each((item, amount) -> total.add(item, Math.max(amount, 0)));
            }
        }
    }

    static void installAutoUnlocks() {
        if (!autoUnlocksInstalled) {
            autoUnlocksInstalled = true;
            Events.on(UnlockEvent.class, event -> checkAutoUnlocks());
            Events.on(SectorCaptureEvent.class, event -> Core.app.post(UpstreamCampaignResearch::checkAutoUnlocks));
        }
        checkAutoUnlocks();
    }

    // Offline portion of v160.8 Control.checkAutoUnlocks.
    private static void checkAutoUnlocks() {
        if (Vars.net.client()) return;

        for (TechNode node : TechTree.all) {
            if (!node.content.unlocked() && (node.parent == null || node.parent.content.unlocked())
                    && node.requirements.length == 0 && !node.objectives.contains(objective -> !objective.complete())) {
                node.content.unlock();
            }
        }
    }

    boolean selectable(TechNode node) {
        if (node != root && node.rootNode != root) return false;
        for (TechNode parent = node.parent; parent != null; parent = parent.parent) {
            if (!parent.content.unlockedHost()) return false;
        }
        return node.content.unlockedHost() || !node.objectives.contains(objective -> !objective.complete());
    }

    int available(Item item) {
        return total.get(item);
    }

    boolean spend(TechNode node) {
        if (Vars.net.client() || node.content.unlockedHost() || !selectable(node)) return false;

        boolean complete = true;
        boolean spent = false;
        for (int i = 0; i < node.requirements.length; i++) {
            var request = node.requirements[i];
            var finished = node.finishedRequirements[i];
            int used = Math.max(Math.min(request.amount - finished.amount, total.get(request.item)), 0);
            remove(request.item, used);
            finished.amount += used;
            spent |= used > 0;
            complete &= finished.amount >= request.amount;
        }
        if (complete) {
            node.content.unlock();
            for (TechNode parent = node.parent; parent != null; parent = parent.parent) {
                parent.content.unlock();
            }
            Events.fire(new ResearchEvent(node.content));
        }
        node.save();
        return spent || complete;
    }

    private void remove(Item item, int amount) {
        if (amount == 0) return;

        Sector current = Vars.state.isCampaign() ? Vars.state.rules.sector : null;
        ItemSeq currentItems = current == null ? null : inventory.get(current);
        // Stock research draws proportionally from other sectors before touching the live core.
        double percentage = (double) amount / Math.max(total.get(item)
            - (currentItems == null ? 0 : currentItems.get(item)), 1);
        int remaining = amount;
        for (var entry : inventory) {
            if (remaining == 0) break;
            if (entry.key == current) continue;

            int available = entry.value.get(item);
            if (available <= 0) continue;

            int used = Math.min(remaining, Math.min((int) Math.ceil(percentage * available), available));
            entry.key.removeItem(item, used);
            entry.value.remove(item, used);
            remaining -= used;
        }
        if (remaining > 0 && currentItems != null) {
            int used = Math.min(currentItems.get(item), remaining);
            current.removeItem(item, used);
            currentItems.remove(item, used);
        }
        total.remove(item, amount);
    }
}
