import java.io.IOException;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild;

/** Stock crafter recipes beyond the single production probe fixture. */
public final class UpstreamCrafterRecipesProbe {
    public static void main(String[] args) throws IOException {
        var app = UpstreamSimulationWorld.play(args);
        var team = Vars.state.rules.defaultTeam;
        var core = team.core();
        int baseX = core.tile.x + 4;
        int baseY = core.tile.y + 4;

        var press = (GenericCrafterBuild)place(Blocks.graphitePress, baseX, baseY, team);
        int pressStart = press.items.get(Items.graphite);
        for (int tick = 0; tick < 400; tick++) {
            press.items.set(Items.coal, press.block.itemCapacity);
            step(app);
        }
        int graphite = press.items.get(Items.graphite) - pressStart;
        if (graphite <= 0) throw new AssertionError("Stock Graphite Press produced no graphite");

        var smelter = (GenericCrafterBuild)place(Blocks.siliconSmelter, baseX + 4, baseY, team);
        place(Blocks.powerSource, baseX + 3, baseY, team);
        int smeltStart = smelter.items.get(Items.silicon);
        for (int tick = 0; tick < 400; tick++) {
            smelter.items.set(Items.coal, smelter.block.itemCapacity);
            smelter.items.set(Items.sand, smelter.block.itemCapacity);
            step(app);
        }
        int silicon = smelter.items.get(Items.silicon) - smeltStart;
        if (silicon <= 0) throw new AssertionError("Stock Silicon Smelter produced no silicon");

        System.out.println("UPSTREAM_CRAFTER_RECIPES graphitePress=" + graphite
            + " siliconSmelter=" + silicon + " craftTime=stock");
    }

    private static mindustry.gen.Building place(mindustry.world.Block block, int x, int y, mindustry.game.Team team) {
        Vars.world.tile(x, y).setBlock(block, team, 0);
        return Vars.world.tile(x, y).build;
    }

    private static void step(UpstreamWorldLoadProbe.ProbeApplication app) {
        Vars.logic.update();
        app.runPosted();
    }
}
