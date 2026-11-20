import java.io.IOException;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.gen.Building;
import mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild;

public final class UpstreamProductionTickProbe {
    public static void main(String[] args) throws IOException {
        var app = UpstreamSimulationWorld.play(args);
        var team = Vars.state.rules.defaultTeam;
        var core = team.core();
        int x = core.tile.x - 10;
        int y = core.tile.y;
        var pumpTile = Vars.world.tile(x - 2, y);
        var pipeTile = Vars.world.tile(x - 1, y);
        var crafterTile = Vars.world.tile(x, y);
        var batteryTile = Vars.world.tile(x, y - 1);
        var generatorA = Vars.world.tile(x, y - 2);
        var generatorB = Vars.world.tile(x - 1, y - 1);

        // Add a source tile to the fixture after verifying the original map.
        pumpTile.setFloor(Blocks.water.asFloor());
        pumpTile.setBlock(Blocks.mechanicalPump, team, 0);
        pipeTile.setBlock(Blocks.conduit, team, 0);
        crafterTile.setBlock(Blocks.cultivator, team, 0);
        batteryTile.setBlock(Blocks.battery, team, 0);
        generatorA.setBlock(Blocks.combustionGenerator, team, 0);
        generatorB.setBlock(Blocks.combustionGenerator, team, 0);
        var crafter = (GenericCrafterBuild) crafterTile.build;

        step(app, 60);
        float water = crafter.liquids.get(Liquids.water);
        if (water <= 0 || water > crafter.block.liquidCapacity
                || crafter.power.status != 0f || crafter.items.total() != 0 || crafter.progress != 0f) {
            throw new IllegalStateException("Expected the pump to fill the unfueled cultivator");
        }
        System.out.println("UPSTREAM_PRODUCTION_STARVED ticks=60 waterMilli="
            + Math.round(water * 1000f) + " pods=0");

        fuel(generatorA.build, crafter);
        fuel(generatorB.build, crafter);
        int initialFuel = generatorA.build.items.get(Items.coal);
        int first = 0;
        boolean consumedFuel = false;
        for (int elapsed = 1; elapsed <= 1200; elapsed++) {
            if (elapsed % 120 == 0) {
                fuel(generatorA.build, crafter);
                fuel(generatorB.build, crafter);
            }
            step(app, 1);
            consumedFuel |= generatorA.build.items.get(Items.coal) < initialFuel;
            if (crafter.items.get(Items.sporePod) == 1) {
                first = elapsed;
                break;
            }
        }
        int remainingFuel = generatorA.build.items.get(Items.coal);
        if (first == 0 || crafter.power.status <= 0 || !consumedFuel) {
            throw new IllegalStateException("Expected a spore pod: tick=" + first + " power="
                + crafter.power.status + " progress=" + crafter.progress + " coal="
                + remainingFuel + "/" + initialFuel);
        }

        batteryTile.remove();
        float progress = crafter.progress;
        step(app, 60);
        if (crafter.power.status != 0f || crafter.items.get(Items.sporePod) != 1
                || crafter.progress != progress || crafter.liquids.get(Liquids.water) <= 0) {
            throw new IllegalStateException("Crafting continued across the power network disconnect");
        }
        System.out.println("UPSTREAM_PRODUCTION_DISCONNECT first=" + first + " paused=60 pods=1");

        batteryTile.setBlock(Blocks.battery, team, 0);
        int capacity = crafter.block.itemCapacity;
        int filled = 0;
        for (int elapsed = 1; elapsed <= 5000; elapsed++) {
            if (elapsed % 120 == 1) {
                fuel(generatorA.build, crafter);
                fuel(generatorB.build, crafter);
            }
            step(app, 1);
            if (crafter.items.get(Items.sporePod) == capacity) {
                filled = elapsed;
                break;
            }
        }
        if (filled == 0) throw new IllegalStateException("The reconnected cultivator never filled");
        float fullProgress = crafter.progress;
        step(app, 120);
        if (crafter.items.get(Items.sporePod) != capacity || crafter.progress != fullProgress
                || crafter.shouldConsume()) {
            throw new IllegalStateException("The full cultivator exceeded its production limit");
        }
        System.out.println("UPSTREAM_PRODUCTION_FULL filled=" + filled + " held=120 pods=" + capacity);
    }

    private static void fuel(Building generator, Building source) {
        int room = generator.block.itemCapacity - generator.items.get(Items.coal);
        for (int count = 0; count < room && generator.acceptItem(source, Items.coal); count++) {
            generator.handleItem(source, Items.coal);
        }
    }

    private static void step(UpstreamWorldLoadProbe.ProbeApplication app, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            Vars.logic.update();
            app.runPosted();
        }
    }
}
