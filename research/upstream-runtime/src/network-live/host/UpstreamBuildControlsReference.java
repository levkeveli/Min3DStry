import arc.Core;
import arc.files.Fi;
import arc.mock.MockAudio;
import java.nio.file.Files;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Planets;
import mindustry.content.UnitTypes;
import mindustry.core.NetClient;
import mindustry.game.Universe;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.graphics.g3d.PlanetGrid;
import mindustry.mod.Mods;
import mindustry.net.NetworkIO;
import mindustry.net.Net;
import mindustry.type.Category;
import mindustry.io.SaveIO;
import mindustry.world.blocks.distribution.Sorter;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.units.UnitFactory;

/** Runs the touch adapter against the pinned upstream world, unit and build queue. */
public final class UpstreamBuildControlsReference {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Expected a captured v160.8 WorldStream and optional stock bundle base");
        }
        if (args.length == 2) verifyBundles(new Fi(args[1]));
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
        if (args.length == 2 && (!"Sortierer".equals(Blocks.sorter.localizedName)
                || !"Kupfer".equals(Items.copper.localizedName))) {
            throw new AssertionError("Content initialization did not use the selected official bundle");
        }
        verifyPlanetGrids();

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
        verifyPlanetShortcuts(unit);
        verifyMining(unit);
        Vars.player.unit(unit);
        verifyFactoryConfiguration(controls, unit);
        System.out.println("UPSTREAM_TOUCH_REFERENCE conveyor=85,26,1 wall=86,26 break=86,26");
    }

    private static void verifyFactoryConfiguration(UpstreamLiveBuildControls controls, Unit unit) throws Exception {
        var block = (UnitFactory)Blocks.groundFactory;
        var tile = Vars.world.tile(78, 22);
        tile.setBlock(block, unit.team(), 0);
        var factory = (UnitFactory.UnitFactoryBuild)tile.build;
        var banned = block.plans.get(0).unit;
        var selected = block.plans.get(1);
        var oldAudio = Core.audio;
        Core.audio = new MockAudio();
        Vars.state.rules.bannedUnits.add(banned);
        controls.viewport(73, 20);
        try {
            factory.progress = 10f;
            controls.update(unit, 0, touch(305, 64));
            controls.update(unit, 0, touch(120, 200));
            var config = controls.buildingConfig();
            if (!config.isOpen() || !config.unitPlans() || config.iconIds()[0] != selected.unit.id) {
                throw new AssertionError("Factory menu must omit banned units and show original unit icons");
            }
            int row = UpstreamBuildMenu.ROW_TOP + 2;
            controls.update(unit, 0, touch(160, row));
            if (factory.currentPlan != 1 || factory.progress != 0 || factory.unit() != selected.unit) {
                throw new AssertionError("Expected the stock plan index and progress reset");
            }
            for (var cost : selected.requirements) factory.items.add(cost.item, cost.amount);
            factory.progress = selected.time;
            factory.updateTile();
            if (!(factory.getPayload() instanceof UnitPayload payload) || payload.unit.type != selected.unit) {
                throw new AssertionError("The stock factory did not produce the selected unit");
            }
            for (var cost : selected.requirements) {
                if (factory.items.has(cost.item)) throw new AssertionError("Factory did not consume " + cost.item.name);
            }
            controls.update(unit, 0, touch(305, 64));
            controls.update(unit, 0, touch(120, 200));
            controls.update(unit, 0, touch(160, row));
            if (factory.currentPlan != -1) throw new AssertionError("Tapping the selected plan must clear it");
            controls.update(unit, 0, touch(305, 64));
            controls.update(unit, 0, touch(120, 200));
            controls.update(unit, 0, touch(160, row));
            verifyFactorySave(factory, selected);
            System.out.println("UPSTREAM_FACTORY_PLAN_REFERENCE banned=" + banned.name
                + " selected=" + selected.unit.name + " plan=1 reset=ok production=ok clear=ok reload=ok");
        } finally {
            Core.audio = oldAudio;
            Vars.state.rules.bannedUnits.remove(banned);
            tile.setBlock(Blocks.air);
        }
    }

    private static void verifyFactorySave(UnitFactory.UnitFactoryBuild factory, UnitFactory.UnitPlan selected)
            throws Exception {
        int x = factory.tileX(), y = factory.tileY();
        var path = Files.createTempFile("min3dstry-factory-plan-", ".msav");
        try {
            Files.delete(path);
            var save = new Fi(path.toFile());
            SaveIO.save(save);
            SaveIO.load(save);
            if (!(Vars.world.build(x, y) instanceof UnitFactory.UnitFactoryBuild restored)
                    || restored.currentPlan != 1 || restored.unit() != selected.unit
                    || !(restored.getPayload() instanceof UnitPayload payload) || payload.unit.type != selected.unit) {
                throw new AssertionError("The stock save did not restore the factory plan and produced unit");
            }
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void verifyMining(Unit gamma) {
        gamma.add();
        var floorOre = Vars.world.tile(85, 26);
        var otherFloorOre = Vars.world.tile(84, 26);
        var wallOre = Vars.world.tile(86, 26);
        floorOre.setBlock(Blocks.air);
        floorOre.setOverlay(Blocks.oreCopper);
        otherFloorOre.setBlock(Blocks.air);
        otherFloorOre.setOverlay(Blocks.oreCopper);
        wallOre.setBlock(Blocks.beryllicStoneWall);
        wallOre.setOverlay(Blocks.wallOreBeryllium);
        var controls = new UpstreamLiveBuildControls();
        controls.viewport(73, 20);
        controls.update(gamma, 0, touch(305, 96));
        controls.update(gamma, 0, touch(305, 96));
        gamma.addItem(Items.lead);
        controls.update(gamma, 0, touch(232, 136));
        if (gamma.mineTile != null) throw new AssertionError("Gamma should reject copper while carrying lead");
        gamma.clearItem();
        controls.update(gamma, 0, touch(232, 136));
        if (gamma.mineTile != floorOre || gamma.getMineResult(floorOre) != Items.copper) {
            throw new AssertionError("Gamma should select the upstream copper floor deposit");
        }
        controls.update(gamma, 0, touch(248, 136));
        if (gamma.mineTile != floorOre) throw new AssertionError("Gamma should ignore wall ore");
        controls.update(gamma, 0, touch(216, 136));
        if (gamma.mineTile != otherFloorOre) throw new AssertionError("Gamma should switch floor deposits");
        controls.update(gamma, 0, touch(216, 136));
        if (gamma.mineTile != null) throw new AssertionError("Tapping the target again should stop mining");

        var evoke = UnitTypes.evoke.create(gamma.team());
        Vars.player.unit(evoke);
        evoke.set(0f, 0f);
        evoke.add();
        controls.update(evoke, 0, touch(248, 136));
        if (evoke.mineTile != null) throw new AssertionError("Evoke should ignore an out-of-range wall deposit");
        evoke.set(gamma.x(), gamma.y());
        controls.update(evoke, 0, touch(232, 136));
        if (evoke.mineTile != null) throw new AssertionError("Evoke should ignore floor ore");
        controls.update(evoke, 0, touch(248, 136));
        if (evoke.mineTile != wallOre || evoke.getMineResult(wallOre) != Items.beryllium) {
            throw new AssertionError("Evoke should select the upstream beryllium wall deposit");
        }
        if (!gamma.plans().isEmpty() || !evoke.plans().isEmpty()) {
            throw new AssertionError("Selecting a deposit should leave the build queue empty");
        }
        System.out.println("UPSTREAM_MINE_REFERENCE copper=floor beryllium=wall invalid=ignored switch=ok cancel=ok");
    }

    private static void verifyPlanetShortcuts(Unit unit) {
        var controls = new UpstreamLiveBuildControls();
        var planet = Vars.state.rules.planet;
        try {
            if (controls.block() != Blocks.conveyor || controls.shortcut(1) != Blocks.copperWall) {
                throw new AssertionError("Glacier should retain its Serpulo shortcuts");
            }
            Vars.state.rules.planet = Planets.erekir;
            if (controls.block() != Blocks.duct || controls.shortcut(1) != Blocks.berylliumWall) {
                throw new AssertionError("Erekir should use Duct and Beryllium Wall shortcuts");
            }
            controls.update(unit, 0, touch(200, 24));
            if (controls.rotation() != 1) throw new AssertionError("Erekir Rotate should turn the Duct");
            controls.update(unit, 0, touch(120, 24));
            if (controls.block() != Blocks.berylliumWall || controls.rotation() != 0) {
                throw new AssertionError("Selecting the Beryllium Wall should clear the Duct rotation");
            }
        } finally {
            Vars.state.rules.planet = planet;
        }
    }

    private static void verifyPlanetGrids() {
        long[] stock = {0xaef3b2723ca51e3dL, 0x8b7bf905f4f1e241L, 0x73685278282bbc71L, 0xd8c194e69ddb9fabL};
        for (int size = 0; size < stock.length; size++) {
            var grid = PlanetGrid.create(size);
            long hash = 1;
            for (var tile : grid.tiles) {
                hash = 31 * hash + tile.id;
                hash = 31 * hash + Float.floatToIntBits(tile.v.x);
                hash = 31 * hash + Float.floatToIntBits(tile.v.y);
                hash = 31 * hash + Float.floatToIntBits(tile.v.z);
                for (var neighbor : tile.tiles) hash = 31 * hash + neighbor.id;
            }
            if (grid != PlanetGrid.create(size) || hash != stock[size]) {
                throw new AssertionError("Planet grid " + size + " differs from the stock cache or subdivision");
            }
        }
    }

    private static void verifyBundles(Fi path) {
        String[] locales = {"ja", "", "fr", "de", "it", "es", "zh_CN", "ko", "nl", "pt_PT", "ru", "zh_TW"};
        for (int language = 0; language < locales.length; language++) {
            var bundle = UpstreamInterfaceLocale.load(path, language);
            if (!locales[language].equals(bundle.getLocale().toString())) {
                throw new AssertionError("Stock bundle " + locales[language] + " did not load");
            }
        }
        int selectable = 0;
        for (Fi file : path.parent().list()) {
            String name = file.nameWithoutExtension();
            if (!name.startsWith("bundle_")) continue;
            String code = name.substring("bundle_".length());
            if (!code.equals(UpstreamInterfaceLocale.load(path, 1, code).getLocale().toString())) {
                throw new AssertionError("Cannot select the stock bundle " + code);
            }
            selectable++;
        }
        var english = UpstreamInterfaceLocale.load(path, 1);
        if (!"Router".equals(english.get("block.router.name")) || !"Copper".equals(english.get("item.copper.name"))) {
            throw new AssertionError("Expected the original English block and item labels");
        }
        Core.bundle = UpstreamInterfaceLocale.load(path, 1, "de");
        System.out.println("UPSTREAM_INTERFACE_REFERENCE systemLanguages=" + locales.length
            + " additionalBundles=" + selectable + " selected=de");
    }

    private static void verifyConfiguration(UpstreamLiveBuildControls controls, Unit unit) {
        var distribution = new UpstreamPlacementCatalog().getUnlockedByCategory(Category.distribution);
        int sorterIndex = distribution.indexOf(Blocks.sorter, true);
        if (sorterIndex < 0) throw new AssertionError("Stock catalog should expose Sorter");
        // No transport in this reference. Building.configure still runs the original local call.
        Vars.net = new Net(null);
        var tile = Vars.world.tile(87, 26);
        var input = Vars.world.tile(86, 26);
        var straight = Vars.world.tile(88, 26);
        var side = Vars.world.tile(87, 27);
        input.setBlock(Blocks.router, unit.team(), 0);
        straight.setBlock(Blocks.router, unit.team(), 0);
        side.setBlock(Blocks.router, unit.team(), 0);
        int copperIndex = Vars.content.items().select(UpstreamItemChoices::accepts).indexOf(Items.copper, true);
        if (copperIndex < 0 || copperIndex >= UpstreamBuildMenu.ROWS) {
            throw new AssertionError("Expected copper on the first stock item page");
        }
        int y = UpstreamBuildMenu.ROW_TOP + copperIndex * UpstreamBuildMenu.ROW_HEIGHT;
        for (var block : new Sorter[]{(Sorter) Blocks.sorter, (Sorter) Blocks.invertedSorter}) {
            tile.setBlock(block, unit.team(), 0);
            var sorter = (Sorter.SorterBuild) tile.build;
            for (int attempt = 0; attempt < 2; attempt++) {
                controls.update(unit, 0, touch(305, 64));
                controls.update(unit, 0, touch(264, 136));
                controls.update(unit, 0, touch(160, y));
                if (sorter.config() != (attempt == 0 ? Items.copper : null)) {
                    throw new AssertionError(block.name + " must select copper and clear it on the second tap");
                }
                boolean copperStraight = (attempt == 0) != block.invert;
                if (sorter.getTileTarget(Items.copper, input.build, false) != (copperStraight ? straight.build : side.build)
                        || sorter.getTileTarget(Items.lead, input.build, false) != (block.invert ? straight.build : side.build)) {
                    throw new AssertionError(block.name + " must route matching and other items correctly");
                }
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
