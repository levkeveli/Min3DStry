import java.io.IOException;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild;
import mindustry.world.blocks.production.Separator;

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

        var separator = place(Blocks.separator, baseX, baseY + 4, team);
        place(Blocks.powerSource, baseX - 1, baseY + 4, team);
        var results = ((Separator)Blocks.separator).results;
        int sepStart = 0;
        for (var result : results) sepStart += separator.items.get(result.item);
        for (int tick = 0; tick < 400; tick++) {
            separator.liquids.set(Liquids.slag, separator.block.liquidCapacity);
            step(app);
        }
        int sepOut = 0;
        for (var result : results) sepOut += separator.items.get(result.item);
        sepOut -= sepStart;
        if (sepOut <= 0) throw new AssertionError("Stock Separator produced no items");

        var kiln = (GenericCrafterBuild)place(Blocks.kiln, baseX + 4, baseY + 4, team);
        place(Blocks.powerSource, baseX + 3, baseY + 4, team);
        int kilnStart = kiln.items.get(Items.metaglass);
        for (int tick = 0; tick < 400; tick++) {
            kiln.items.set(Items.lead, kiln.block.itemCapacity);
            kiln.items.set(Items.sand, kiln.block.itemCapacity);
            step(app);
        }
        int metaglass = kiln.items.get(Items.metaglass) - kilnStart;
        if (metaglass <= 0) throw new AssertionError("Stock Kiln produced no metaglass");

        System.out.println("UPSTREAM_CRAFTER_RECIPES graphitePress=" + graphite
            + " siliconSmelter=" + silicon + " separator=" + sepOut + " kiln=" + metaglass
            + " craftTime=stock");
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
