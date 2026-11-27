import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.world.blocks.distribution.DirectionalUnloader;
import mindustry.world.blocks.distribution.DirectionalUnloader.DirectionalUnloaderBuild;
import mindustry.world.blocks.distribution.Duct;
import mindustry.world.blocks.distribution.Duct.DuctBuild;
import mindustry.world.blocks.distribution.DuctBridge;
import mindustry.world.blocks.distribution.DuctRouter;
import mindustry.world.blocks.distribution.DuctRouter.DuctRouterBuild;
import mindustry.world.blocks.distribution.OverflowDuct;
import mindustry.world.blocks.storage.StorageBlock.StorageBuild;

/**
 * Pinned upstream Erekir duct family on a stock Erekir map.
 *
 * The Serpulo factory probe covers conveyor/junction transport. This probe runs
 * the original duct, armored duct, duct router and duct unloader blocks inside
 * the port's offline tick loop, and checks that items still move through them at
 * the stock v160.8 rate.
 */
public final class UpstreamErekirDuctProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException(
                "Expected an Erekir .msav path; run with -Pglacier=<path>");
        }
        var app = UpstreamSimulationWorld.playAny(args);

        verifyCatalog();

        var team = Vars.state.rules.defaultTeam;
        var core = team.core();
        if (core == null) throw new AssertionError("Stock Erekir map has no default-team core");

        int bx = core.tile.x - 10, by = core.tile.y + 8;
        for (int x = bx; x <= bx + 7; x++) {
            for (int y = by; y <= by + 6; y++) {
                Vars.world.tile(x, y).setBlock(Blocks.air);
            }
        }

        // Standard ducts: storage -> unloader -> duct -> duct -> storage.
        Vars.world.tile(bx, by).setBlock(Blocks.container, team, 0);
        var source = (StorageBuild)Vars.world.tile(bx, by).build;
        source.items.add(Items.copper, 20);
        Vars.world.tile(bx + 2, by).setBlock(Blocks.ductUnloader, team, 0);
        var unloader = (DirectionalUnloaderBuild)Vars.world.tile(bx + 2, by).build;
        Vars.world.tile(bx + 3, by).setBlock(Blocks.duct, team, 0);
        Vars.world.tile(bx + 4, by).setBlock(Blocks.duct, team, 0);
        Vars.world.tile(bx + 5, by).setBlock(Blocks.container, team, 0);
        var sink = (StorageBuild)Vars.world.tile(bx + 5, by).build;
        var firstDuct = (DuctBuild)Vars.world.tile(bx + 3, by).build;
        var secondDuct = (DuctBuild)Vars.world.tile(bx + 4, by).build;
        firstDuct.onProximityUpdate();
        secondDuct.onProximityUpdate();
        if (unloader.front() != firstDuct) {
            throw new AssertionError("Stock duct unloader no longer fronts the duct line");
        }
        if (firstDuct.next != secondDuct || secondDuct.next != sink) {
            throw new AssertionError("Stock duct proximity links changed");
        }

        int arrival = -1;
        for (int tick = 1; tick <= 240; tick++) {
            tick(app);
            if (arrival == -1 && sink.items.get(Items.copper) > 0) arrival = tick;
        }
        if (arrival != 10) {
            throw new AssertionError("Stock duct line arrival changed: " + arrival);
        }
        if (source.items.get(Items.copper) != 0 || sink.items.get(Items.copper) != 20) {
            throw new AssertionError("Stock duct line lost items: source=" + source.items.get(Items.copper)
                + " sink=" + sink.items.get(Items.copper));
        }

        // Armored ducts only take input through their facing edge.
        Vars.world.tile(bx, by + 2).setBlock(Blocks.container, team, 0);
        var armoredSource = (StorageBuild)Vars.world.tile(bx, by + 2).build;
        armoredSource.items.add(Items.tungsten, 8);
        Vars.world.tile(bx + 2, by + 2).setBlock(Blocks.ductUnloader, team, 0);
        Vars.world.tile(bx + 3, by + 2).setBlock(Blocks.duct, team, 0);
        Vars.world.tile(bx + 4, by + 2).setBlock(Blocks.armoredDuct, team, 0);
        Vars.world.tile(bx + 5, by + 2).setBlock(Blocks.container, team, 0);
        var armoredSink = (StorageBuild)Vars.world.tile(bx + 5, by + 2).build;
        for (int x = bx + 2; x <= bx + 4; x++) {
            Vars.world.tile(x, by + 2).build.onProximityUpdate();
        }
        int armoredArrival = -1;
        for (int tick = 1; tick <= 240; tick++) {
            tick(app);
            if (armoredArrival == -1 && armoredSink.items.get(Items.tungsten) > 0) armoredArrival = tick;
        }
        if (armoredArrival == -1 || armoredSink.items.get(Items.tungsten) != 8) {
            throw new AssertionError("Stock armored duct line stalled: arrival=" + armoredArrival
                + " delivered=" + armoredSink.items.get(Items.tungsten));
        }

        // Duct router sends its sorted item through the facing duct.
        Vars.world.tile(bx, by + 4).setBlock(Blocks.ductRouter, team, 0);
        var router = (DuctRouterBuild)Vars.world.tile(bx, by + 4).build;
        Vars.world.tile(bx + 1, by + 4).setBlock(Blocks.duct, team, 0);
        Vars.world.tile(bx + 2, by + 4).setBlock(Blocks.container, team, 0);
        var routerSink = (StorageBuild)Vars.world.tile(bx + 2, by + 4).build;
        router.configure(Items.copper);
        router.onProximityUpdate();
        Vars.world.tile(bx + 1, by + 4).build.onProximityUpdate();
        if (router.front() != Vars.world.tile(bx + 1, by + 4).build) {
            throw new AssertionError("Stock duct router lost its facing output");
        }
        router.items.add(Items.copper, 1);
        for (int tick = 1; tick <= 120; tick++) {
            tick(app);
        }
        if (router.sortItem != Items.copper || routerSink.items.get(Items.copper) != 1
            || router.items.total() != 0) {
            throw new AssertionError("Stock duct router sort/filter changed: sort=" + router.sortItem
                + " delivered=" + routerSink.items.get(Items.copper)
                + " held=" + router.items.total());
        }
        System.out.println("UPSTREAM_EREKIR_DUCTS duct=4 armored=4 unloader=4 router=4"
            + " arrival=" + arrival + " delivered=" + sink.items.get(Items.copper)
            + " armoredArrival=" + armoredArrival + " armoredDelivered=" + armoredSink.items.get(Items.tungsten)
            + " bridge=" + ((Duct)Blocks.duct).bridgeReplacement.name
            + " overflow=" + (((OverflowDuct)Blocks.overflowDuct).invert ? "inverted" : "normal")
            + " underflow=" + (((OverflowDuct)Blocks.underflowDuct).invert ? "inverted" : "normal")
            + " sort=" + router.sortItem.name
            + " routerDelivered=" + routerSink.items.get(Items.copper));
    }

    private static void verifyCatalog() {
        if (!(Blocks.duct instanceof Duct) || !(Blocks.armoredDuct instanceof Duct)
            || !(Blocks.ductRouter instanceof DuctRouter) || !(Blocks.ductBridge instanceof DuctBridge)
            || !(Blocks.overflowDuct instanceof OverflowDuct)
            || !(Blocks.underflowDuct instanceof OverflowDuct)) {
            throw new AssertionError("Stock Erekir duct catalog changed");
        }
        var duct = (Duct)Blocks.duct;
        var armored = (Duct)Blocks.armoredDuct;
        if (duct.speed != 4f || duct.itemCapacity != 1 || duct.armored
            || armored.speed != 4f || !armored.armored) {
            throw new AssertionError("Stock duct speed/capacity/armored flags changed");
        }
        if (duct.bridgeReplacement != Blocks.ductBridge) {
            throw new AssertionError("Stock ducts no longer replace bridges with the duct bridge: "
                + duct.bridgeReplacement);
        }
        if (((OverflowDuct)Blocks.overflowDuct).invert || !((OverflowDuct)Blocks.underflowDuct).invert) {
            throw new AssertionError("Stock overflow/underflow duct polarity changed");
        }
        if (((DuctRouter)Blocks.ductRouter).speed != 4f
            || ((DirectionalUnloader)Blocks.ductUnloader).speed != 4f) {
            throw new AssertionError("Stock duct router/unloader speed changed");
        }
    }

    private static void tick(UpstreamWorldLoadProbe.ProbeApplication app) {
        Vars.logic.update();
        app.runPosted();
    }
}
