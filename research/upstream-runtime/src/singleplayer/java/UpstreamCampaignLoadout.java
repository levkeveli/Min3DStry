import arc.Core;
import arc.math.Mathf;
import mindustry.Vars;
import mindustry.content.Loadouts;
import mindustry.game.Schematic;
import mindustry.type.ItemSeq;
import mindustry.type.Sector;
import mindustry.world.blocks.storage.CoreBlock;

/** The stock LaunchLoadoutDialog's default schematic and resource calculation. */
record UpstreamCampaignLoadout(CoreBlock core, Schematic schematic, ItemSeq resources, ItemSeq cost, boolean affordable) {
    static UpstreamCampaignLoadout prepare(Sector from, Sector to) {
        CoreBlock core = to.allowLaunchSchematics() && from.info.bestCoreType instanceof CoreBlock best
            ? best : (CoreBlock) from.planet.defaultCore;
        Schematic schematic = Loadouts.basicShard;
        for (Schematic candidate : new Schematic[]{
                Loadouts.basicShard, Loadouts.basicFoundation, Loadouts.basicNucleus, Loadouts.basicBastion}) {
            if (candidate.findCore() == core) {
                schematic = candidate;
                break;
            }
        }

        ItemSeq stock = from.items();
        ItemSeq materials = schematic.requirements();
        ItemSeq resources = Vars.universe.getLaunchResources();
        if (from.planet.allowLaunchLoadout) {
            for (var item : Vars.content.items()) {
                if (!item.isOnPlanet(from.planet)) resources.set(item, 0);
            }
        }
        int capacity = (int) (from.planet.launchCapacityMultiplier * schematic.findCore().itemCapacity);
        resources.min(capacity);
        if (!to.allowLaunchLoadout()) {
            resources.clear();
            if (to.preset != null) {
                for (var stack : to.preset.generator.map.rules().loadout) {
                    if (stack.item.isOnPlanet(from.planet)) resources.add(stack);
                }
            }
        } else if (Core.settings.getBool("maxresources", true)) {
            for (var item : Vars.content.items()) {
                resources.set(item, Mathf.clamp(stock.get(item) - materials.get(item), 0, capacity));
            }
        }

        ItemSeq cost = materials.copy();
        cost.add(resources);
        return new UpstreamCampaignLoadout(core, schematic, resources, cost, stock.has(cost));
    }
}
