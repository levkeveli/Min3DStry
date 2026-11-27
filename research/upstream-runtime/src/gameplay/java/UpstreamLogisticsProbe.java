import java.io.IOException;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.world.blocks.distribution.OverflowGate;
import mindustry.world.blocks.distribution.Sorter.SorterBuild;

/**
 * Pinned upstream sorter and storage behaviour on Glacier.
 *
 * The factory probe covers conveyor/junction transport and drill output; this
 * probe covers the sorter filter contract and the stock storage capacities.
 */
public final class UpstreamLogisticsProbe {
    public static void main(String[] args) throws IOException {
        var app = UpstreamSimulationWorld.play(args);

        var team = Vars.state.rules.defaultTeam;
        var core = team.core();
        int x = core.tile.x - core.block.size / 2 - 1;
        int y = core.tile.y;

        // Clear the sorter's side neighbours so a filtered item has nowhere to go.
        Vars.world.tile(x, y - 1).setBlock(Blocks.air);
        Vars.world.tile(x, y + 1).setBlock(Blocks.air);

        var sourceTile = Vars.world.tile(x - 1, y);
        var sorterTile = Vars.world.tile(x, y);
        sourceTile.setBlock(Blocks.conveyor, team, 0);
        sorterTile.setBlock(Blocks.sorter, team, 0);
        var sorter = (SorterBuild)sorterTile.build;
        sorter.configure(Items.copper);

        if (!sorter.acceptItem(sourceTile.build, Items.copper)) {
            throw new AssertionError("Stock sorter rejected its configured item");
        }
        if (sorter.acceptItem(sourceTile.build, Items.lead)) {
            throw new AssertionError("Stock sorter accepted a filtered item with no side target");
        }

        if (Blocks.container.itemCapacity != 300 || Blocks.vault.itemCapacity != 1000) {
            throw new AssertionError("Stock storage capacities changed: container="
                + Blocks.container.itemCapacity + " vault=" + Blocks.vault.itemCapacity);
        }
        if (((OverflowGate)Blocks.overflowGate).invert || !((OverflowGate)Blocks.underflowGate).invert) {
            throw new AssertionError("Stock overflow/underflow gate polarity changed");
        }

        int before = core.items.get(Items.copper);
        sorter.handleItem(sourceTile.build, Items.copper);
        int arrival = -1;
        for (int tick = 1; tick <= 60; tick++) {
            Vars.logic.update();
            app.runPosted();
            if (arrival == -1 && core.items.get(Items.copper) > before) arrival = tick;
        }
        if (arrival == -1) {
            throw new AssertionError("Stock sorter did not deliver the configured item to the core");
        }

        System.out.println("UPSTREAM_LOGISTICS sorter=copper:accepted,lead:rejected arrival=" + arrival
            + " container=" + Blocks.container.itemCapacity + " vault=" + Blocks.vault.itemCapacity
            + " overflow=normal underflow=inverted");
    }
}
