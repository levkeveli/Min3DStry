import arc.Core;
import arc.math.Mathf;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.type.ItemSeq;
import mindustry.type.Sector;
import mindustry.world.blocks.storage.CoreBlock;

/** The stock LaunchLoadoutDialog's schematic lookup, resource limits, and costs. */
record UpstreamCampaignLoadout(CoreBlock core, Schematic schematic, ItemSeq resources, ItemSeq cost,
                               boolean affordable, boolean maximumCargo) {
    record Choices(CoreBlock core, Seq<Schematic> options, int selected) {}

    static Choices choices(Sector from, Sector to) {
        if (Vars.schematics == null) {
            Vars.schematics = new Schematics();
            Vars.schematics.load();
        }
        CoreBlock core = to.allowLaunchSchematics() && from.info.bestCoreType instanceof CoreBlock best
            ? best : (CoreBlock) from.planet.defaultCore;
        Schematic preferred = Vars.universe.getLoadout(core);
        if (preferred == null) preferred = Vars.schematics.getDefaultLoadout((CoreBlock) Blocks.coreShard);

        var options = new Seq<Schematic>();
        if (to.allowLaunchSchematics()) {
            for (var entry : Vars.schematics.getLoadouts()) {
                if (entry.key.size > core.size) continue;
                for (var candidate : entry.value) {
                    if (!candidate.tiles.contains(tile -> !tile.block.supportsEnv(from.planet.defaultEnv)
                            || !tile.block.isOnPlanet(from.planet))) options.add(candidate);
                }
            }
        }
        if (options.isEmpty()) options.add(preferred);
        int selected = options.indexOf(preferred, true);
        return new Choices(core, options, Math.max(0, selected));
    }

    static UpstreamCampaignLoadout prepare(Sector from, Sector to) {
        var choices = choices(from, to);
        return prepare(from, to, choices, choices.selected(), Core.settings.getBool("maxresources", true));
    }

    static UpstreamCampaignLoadout prepare(Sector from, Sector to, Choices choices, int selected, boolean maximumCargo) {
        Schematic schematic = choices.options().get(selected);
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
        } else if (maximumCargo) {
            for (var item : Vars.content.items()) {
                resources.set(item, Mathf.clamp(stock.get(item) - materials.get(item), 0, capacity));
            }
        }

        ItemSeq cost = materials.copy();
        cost.add(resources);
        return new UpstreamCampaignLoadout(choices.core(), schematic, resources, cost, stock.has(cost), maximumCargo);
    }
}
