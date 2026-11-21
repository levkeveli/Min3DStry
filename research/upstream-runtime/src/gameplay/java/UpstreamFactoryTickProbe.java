import java.io.IOException;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

public final class UpstreamFactoryTickProbe {
    public static void main(String[] args) throws IOException {
        var app = UpstreamSimulationWorld.play(args);

        var team = Vars.state.rules.defaultTeam;
        var core = team.core();
        int x = core.tile.x - core.block.size / 2 - 1;
        int y = core.tile.y;
        var conveyor = Vars.world.tile(x, y);
        var source = Vars.world.tile(x - 1, y);
        conveyor.setBlock(Blocks.conveyor, team, 0);
        source.setBlock(Blocks.junction, team, 0);
        if (conveyor.block() != Blocks.conveyor || !conveyor.build.acceptItem(source.build, Items.copper)) {
            throw new IllegalStateException("Upstream conveyor refused the test item");
        }
        if (core.items.get(Items.copper) != 200) {
            throw new IllegalStateException("Glacier's starting core inventory differs");
        }
        conveyor.build.handleItem(source.build, Items.copper);
        int arrival = -1;
        for (int tick = 1; tick <= 240; tick++) {
            Vars.logic.update();
            app.runPosted();
            if (arrival == -1 && core.items.get(Items.copper) == 201) {
                arrival = tick;
            }
        }
        int copper = core.items.get(Items.copper);
        if (arrival != 29 || copper != 201 || Vars.state.updateId != 240 || Vars.state.tick != 240.0
                || Vars.state.wave != 1 || Vars.state.gameOver) {
            throw new IllegalStateException("Factory ticks differ at copper arrival " + arrival + " and count " + copper);
        }
        System.out.println("UPSTREAM_FACTORY updates=240 wave=1 arrival=29 copper=201");
        verifyDrillRoute(app, core, source);
    }

    private static void verifyDrillRoute(UpstreamWorldLoadProbe.ProbeApplication app, CoreBuild core, Tile source) {
        source.setBlock(Blocks.router, core.team, 0);
        Tile drillTile = Vars.world.tile(source.x - 2, source.y);
        drillTile.setBlock(Blocks.mechanicalDrill, core.team, 0);
        for (int dx = 0; dx < drillTile.block().size; dx++) {
            for (int dy = 0; dy < drillTile.block().size; dy++) {
                Tile ore = Vars.world.tile(drillTile.x + dx, drillTile.y + dy);
                if (!ore.floor().hasSurface()) {
                    throw new IllegalStateException("Invalid drill fixture tile " + ore);
                }
                ore.setOverlay(Blocks.oreCopper);
            }
        }
        var drill = (DrillBuild) drillTile.build;
        if (drill.dominantItem != Items.copper || drill.dominantItems != 4) {
            throw new IllegalStateException("Mechanical drill failed to register its four copper tiles");
        }

        int copper = core.items.get(Items.copper);
        var trace = new StringBuilder();
        for (int tick = 241; tick <= 720; tick++) {
            Vars.logic.update();
            app.runPosted();
            int current = core.items.get(Items.copper);
            if (current != copper) {
                if (trace.length() > 0) {
                    trace.append(',');
                }
                trace.append(tick).append('=').append(current);
                copper = current;
            }
        }
        String deliveries = trace.toString();
        if (!deliveries.equals("464=202,626=203") || Vars.state.updateId != 720
                || Vars.state.tick != 720.0 || Vars.state.wave != 1 || Vars.state.gameOver) {
            throw new IllegalStateException("Drill route differs at " + deliveries);
        }
        System.out.println("UPSTREAM_DRILL updates=720 wave=1 copper=203 arrivals=" + deliveries);
    }
}
