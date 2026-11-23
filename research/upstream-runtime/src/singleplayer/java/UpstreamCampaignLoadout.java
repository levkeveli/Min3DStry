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

    static int cargoCapacity(Sector from, Schematic schematic) {
        return (int) (from.planet.launchCapacityMultiplier * schematic.findCore().itemCapacity);
    }

    static int adjustCargo(int amount, int direction, int capacity, boolean fine) {
        // Match LoadoutDialog's quantity steps, with single-item adjustment on the handheld.
        int step = fine ? 1 : amount < 1000 ? 100 : amount < 2000 ? 200 : amount < 5000 ? 500 : 1000;
        return Mathf.clamp(amount + direction * step, 0, capacity);
    }

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
        return prepare(from, to, choices, choices.selected(), Core.settings.getBool("maxresources", true),
            Vars.universe.getLaunchResources());
    }

    static UpstreamCampaignLoadout prepare(Sector from, Sector to, Choices choices, int selected,
                                          boolean maximumCargo, ItemSeq requested) {
        Schematic schematic = choices.options().get(selected);
        ItemSeq stock = from.items();
        ItemSeq materials = schematic.requirements();
        ItemSeq resources = requested.copy();
        if (from.planet.allowLaunchLoadout) {
            for (var item : Vars.content.items()) {
                if (!item.isOnPlanet(from.planet)) resources.set(item, 0);
            }
        }
        int capacity = cargoCapacity(from, schematic);
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
