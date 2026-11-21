import arc.files.Fi;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.core.NetClient;
import mindustry.game.Universe;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.mod.Mods;
import mindustry.net.NetworkIO;
import mindustry.net.Net;
import mindustry.type.Category;
import mindustry.world.blocks.distribution.Sorter;

/** Runs the touch adapter against the pinned upstream world, unit and build queue. */
public final class UpstreamBuildControlsReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a captured v160.8 WorldStream");
        var fixture = new Fi(args[0]);
        var app = UpstreamWorldLoadProbe.initialize(fixture.parent());
        Vars.mods = new Mods();
        Vars.universe = new Universe();
        Vars.indexer = new BlockIndexer();
        Vars.netClient = NetClient.forSnapshotDispatch();
        Vars.player = Player.create();
        Vars.net.setClientConnected();
        NetworkIO.loadWorld(new InflaterInputStream(fixture.read()));
        app.runPosted();

        var team = Vars.player.team();
        var unit = UnitTypes.gamma.create(team);
        unit.set(648f, 208f);
        Vars.player.unit(unit);
        var controls = new UpstreamLiveBuildControls();
        controls.viewport(73, 20);
        controls.update(unit, 0, touch(288, 136));
        if (!unit.plans().isEmpty()) throw new AssertionError("The grid's right edge must be exclusive");
        controls.update(unit, 0, touch(200, 24));
        controls.update(unit, 0, touch(232, 136));
        var conveyor = unit.plans().removeFirst();
        if (conveyor.x != 85 || conveyor.y != 26 || conveyor.rotation != 1
                || conveyor.block != Blocks.conveyor || conveyor.breaking) {
            throw new AssertionError("Wrong upstream conveyor plan");
        }

        controls.update(unit, 0, touch(120, 24));
        controls.update(unit, 0, touch(248, 136));
        var wall = unit.plans().removeFirst();
        if (wall.x != 86 || wall.y != 26 || wall.block != Blocks.copperWall || wall.breaking) {
            throw new AssertionError("Wrong upstream wall plan");
        }
        wall.tile().setBlock(wall.block, team, wall.rotation);
        controls.update(unit, 0, touch(280, 24));
        controls.update(unit, 0, touch(248, 136));
        var removal = unit.plans().removeFirst();
        if (!removal.breaking || removal.x != wall.x || removal.y != wall.y) {
            throw new AssertionError("Wrong upstream deconstruction plan");
        }
        controls.viewport(87, 20);
        if (controls.cursorX() != -1 || controls.cursorY() != -1) {
            throw new AssertionError("An offscreen cursor must clear");
        }
        verifyCatalog(controls, unit);
        verifyConfiguration(controls, unit);
        System.out.println("UPSTREAM_TOUCH_REFERENCE conveyor=85,26,1 wall=86,26 break=86,26");
    }

    private static void verifyConfiguration(UpstreamLiveBuildControls controls, Unit unit) {
        var distribution = new UpstreamPlacementCatalog().getUnlockedByCategory(Category.distribution);
        int sorterIndex = distribution.indexOf(Blocks.sorter, true);
        if (sorterIndex < 0) throw new AssertionError("Stock catalog should expose Sorter");
        // No transport in this reference. Building.configure still runs the original local call.
        Vars.net = new Net(null);
        var tile = Vars.world.tile(87, 26);
        tile.setBlock(Blocks.sorter, unit.team(), 0);
        var sorter = (Sorter.SorterBuild) tile.build;
        int copperIndex = Vars.content.items().select(UpstreamItemChoices::accepts).indexOf(Items.copper, true);
        if (copperIndex < 0 || copperIndex >= UpstreamBuildMenu.ROWS) {
            throw new AssertionError("Expected copper on the first stock item page");
        }
        int y = UpstreamBuildMenu.ROW_TOP + copperIndex * UpstreamBuildMenu.ROW_HEIGHT;
        for (int attempt = 0; attempt < 2; attempt++) {
            controls.update(unit, 0, touch(305, 64));
            controls.update(unit, 0, touch(264, 136));
            controls.update(unit, 0, touch(160, y));
            if (sorter.config() != (attempt == 0 ? Items.copper : null)) {
                throw new AssertionError("Sorter must select copper and clear it on the second tap");
            }
        }
        System.out.println("UPSTREAM_CONFIG_REFERENCE sorterPage=" + (sorterIndex / UpstreamBuildMenu.ROWS + 1)
            + " sorterRow=" + sorterIndex % UpstreamBuildMenu.ROWS + " copperRow=" + copperIndex);
    }

    private static void verifyCatalog(UpstreamLiveBuildControls controls, Unit unit) {
        var upstream = new UpstreamPlacementCatalog();
        var menu = controls.catalog();
        int verified = 0;
        for (var category : Category.all) {
            var blocks = upstream.getUnlockedByCategory(category);
            for (int index = 0; index < blocks.size; index++) {
                var block = blocks.get(index);
                menu.open(block);
                int row = index % UpstreamBuildMenu.ROWS;
                if (menu.selectedRow(block) != row
                        || menu.tap(120, UpstreamBuildMenu.ROW_TOP + row * UpstreamBuildMenu.ROW_HEIGHT) != block
                        || menu.isOpen()) {
                    throw new AssertionError("Catalog selection lost upstream block " + block.name);
                }
                verified++;
            }
        }
        var distribution = upstream.getUnlockedByCategory(Category.distribution);
        int routerIndex = distribution.indexOf(Blocks.router, true);
        if (routerIndex < 0 || verified <= UpstreamBuildMenu.ROWS) {
            throw new AssertionError("Stock Glacier should expose a multi-page catalog including Router");
        }
        menu.open(Blocks.conveyor);
        menu.tap(285, 20);
        menu.tap(35, 20);
        if (menu.selectedRow(Blocks.conveyor) < 0 || menu.tap(160, 218) != null || menu.isOpen()) {
            throw new AssertionError("Category navigation must return to distribution and Back must leave the menu");
        }
        var rules = Vars.state.rules;
        boolean hideBanned = rules.hideBannedBlocks;
        rules.hideBannedBlocks = true;
        rules.bannedBlocks.add(Blocks.router);
        try {
            if (upstream.getUnlockedByCategory(Category.distribution).contains(Blocks.router, true)) {
                throw new AssertionError("The stock hide-banned rule must remove Router from the catalog");
            }
        } finally {
            rules.bannedBlocks.remove(Blocks.router);
            rules.hideBannedBlocks = hideBanned;
        }
        controls.viewport(73, 20);
        controls.update(unit, 0, touch(40, 24));
        controls.update(unit, 0, touch(15, 64));
        for (int page = 0; page < routerIndex / UpstreamBuildMenu.ROWS; page++) {
            controls.update(unit, 0, touch(268, 212));
        }
        int row = routerIndex % UpstreamBuildMenu.ROWS;
        controls.update(unit, 0, touch(160, UpstreamBuildMenu.ROW_TOP + row * UpstreamBuildMenu.ROW_HEIGHT));
        controls.update(unit, 0, touch(264, 136));
        var router = unit.plans().removeFirst();
        if (router.block != Blocks.router || router.x != 87 || router.y != 26) {
            throw new AssertionError("Catalog choice did not reach the original construction queue");
        }
        System.out.println("UPSTREAM_CATALOG_REFERENCE entries=" + verified
            + " routerPage=" + (routerIndex / UpstreamBuildMenu.ROWS + 1) + " routerRow=" + row);
    }

    private static int touch(int x, int y) {
        return x | (y << 16);
    }
}
