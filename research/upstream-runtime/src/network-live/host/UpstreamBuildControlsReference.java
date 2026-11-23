import arc.Core;
import arc.files.Fi;
import arc.math.geom.Point2;
import arc.mock.MockAudio;
import arc.struct.Seq;
import arc.struct.StringMap;
import java.nio.file.Files;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.ai.ItemUnitStance;
import mindustry.ai.UnitCommand;
import mindustry.ai.UnitStance;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Planets;
import mindustry.content.UnitTypes;
import mindustry.core.NetClient;
import mindustry.game.FogControl;
import mindustry.game.Schematic;
import mindustry.game.Schematics;
import mindustry.game.Universe;
import mindustry.gen.Building;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.graphics.g3d.PlanetGrid;
import mindustry.mod.Mods;
import mindustry.net.NetworkIO;
import mindustry.net.Net;
import mindustry.type.Category;
import mindustry.type.Item;
import mindustry.io.SaveIO;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.MassDriver;
import mindustry.world.blocks.distribution.Sorter;
import mindustry.world.blocks.environment.Floor;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.payloads.PayloadMassDriver;
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
        verifyStockItemSelectors(controls, unit);
        verifyPlanetShortcuts(unit);
        verifyMining(unit);
        Vars.player.unit(unit);
        verifyInventory(unit);
        verifyStockOrders(unit);
        verifyStockCommandMenu(unit);
        verifyFactoryConfiguration(controls, unit);
        verifyStockLinks(unit);
        verifySchematicPlacement(unit);
        verifySchematicCapture(unit);
        System.out.println("UPSTREAM_TOUCH_REFERENCE conveyor=85,26,1 wall=86,26 break=86,26");
    }

    private static void verifyStockCommandMenu(Unit player) {
        var dagger = UnitTypes.dagger.create(player.team());
        var mono = UnitTypes.mono.create(player.team());
        dagger.set(600f, 216f);
        mono.set(616f, 216f);
        dagger.add();
        mono.add();
        try {
            mono.command().command(UnitCommand.moveCommand);
            var controls = new UpstreamLiveBuildControls();
            controls.viewport(73, 20);
            controls.update(player, 0, touch(305, 128));
            tapGridCell(controls, player, 75, 27);
            tapGridCell(controls, player, 77, 27);
            selectOrderOption(controls, player, false, UnitCommand.mineCommand.id);
            if (mono.command().currentCommand() != UnitCommand.mineCommand
                    || dagger.command().currentCommand() != UnitCommand.moveCommand) {
                throw new AssertionError("A mixed selection must apply Mine only to eligible units");
            }
            selectOrderOption(controls, player, true, UnitStance.holdFire.id);
            if (!dagger.command().hasStance(UnitStance.holdFire) || mono.command().hasStance(UnitStance.holdFire)) {
                throw new AssertionError("Hold Fire must apply only to the compatible selected unit");
            }
            int mixedHighlight = selectOrderOption(controls, player, true, UnitStance.holdFire.id);
            if (mixedHighlight != 0 || !dagger.command().hasStance(UnitStance.holdFire)) {
                throw new AssertionError("A mixed selection must use the last controller's stock stance bits");
            }
            tapGridCell(controls, player, 77, 27);
            int highlighted = selectOrderOption(controls, player, true, UnitStance.holdFire.id);
            if (highlighted == 0 || dagger.command().hasStance(UnitStance.holdFire)) {
                throw new AssertionError("An active stance must be highlighted and can be toggled off");
            }
            tapGridCell(controls, player, 77, 27);
            var copper = ItemUnitStance.getByItem(Items.copper);
            selectOrderOption(controls, player, true, copper.id);
            if (!mono.command().hasStance(copper) || dagger.command().hasStance(copper)) {
                throw new AssertionError("An ore stance must use the stock ore eligibility rules");
            }
            tapGridCell(controls, player, 86, 24);
            if (dagger.command().targetPos == null) throw new AssertionError("Expected a move order before cancel");
            selectOrderOption(controls, player, true, UnitStance.stop.id);
            if (dagger.command().targetPos != null || dagger.command().commandQueue.any()) {
                throw new AssertionError("Cancel Orders must clear the stock order state");
            }
            System.out.println("UPSTREAM_STOCK_COMMAND_MENU mixed=ok mine=copper holdfire=toggle cancel=ok");
        } finally {
            dagger.remove();
            mono.remove();
        }
    }

    private static int selectOrderOption(UpstreamLiveBuildControls controls, Unit player, boolean stances, int id) {
        controls.update(player, 0, touch(14, stances ? 96 : 64));
        var menu = controls.orders().menu();
        if (!menu.isOpen()) throw new AssertionError("Expected the original unit options menu");
        int page = 1;
        while (true) {
            int[] ids = menu.icons();
            for (int row = 0; row < ids.length; row++) {
                if (ids[row] == id) {
                    int active = controls.orders().activeMenuRows() & (1 << row);
                    System.out.println("UPSTREAM_STOCK_COMMAND_OPTION id=" + id + " page=" + page + " row=" + row);
                    controls.update(player, 0, touch(160, UpstreamBuildMenu.ROW_TOP + row * UpstreamBuildMenu.ROW_HEIGHT));
                    return active;
                }
            }
            if (!menu.next()) throw new AssertionError("Missing stock option " + id);
            controls.update(player, 0, touch(270, 215));
            page++;
        }
    }

    private static void verifyStockOrders(Unit player) {
        var rules = Vars.state.rules;
        boolean fog = rules.fog;
        var first = UnitTypes.dagger.create(player.team());
        var second = UnitTypes.dagger.create(player.team());
        var enemy = UnitTypes.dagger.create(rules.waveTeam);
        first.set(600f, 216f);
        second.set(616f, 216f);
        enemy.set(696f, 208f);
        first.add();
        second.add();
        enemy.add();
        rules.fog = false;
        try {
            var controls = new UpstreamLiveBuildControls();
            controls.viewport(73, 20);
            controls.update(player, 0, touch(305, 128));
            tapGridCell(controls, player, 75, 27);
            tapGridCell(controls, player, 77, 27);
            tapGridCell(controls, player, 86, 24);
            var firstCommand = first.command();
            var secondCommand = second.command();
            if (firstCommand.targetPos == null || firstCommand.targetPos.x != 688f || firstCommand.targetPos.y != 192f
                    || secondCommand.targetPos == null || firstCommand.group == null
                    || firstCommand.group != secondCommand.group) {
                throw new AssertionError("Stock move order must create a shared unit formation");
            }
            controls.update(player, 1 << 18, -1);
            tapGridCell(controls, player, 85, 25);
            if (firstCommand.commandQueue.size != 1 || firstCommand.commandQueue.first().getX() != 680f
                    || secondCommand.commandQueue.size != 1) {
                throw new AssertionError("Stock queued order must preserve the first destination");
            }
            controls.update(player, 1 << 18, -1);
            tapGridCell(controls, player, 87, 26);
            if (firstCommand.attackTarget != enemy || secondCommand.attackTarget != enemy
                    || firstCommand.commandQueue.any()) {
                throw new AssertionError("Stock attack order must target the enemy and replace the queued move");
            }
            var wall = Vars.world.tile(86, 24);
            wall.setBlock(Blocks.copperWall, rules.waveTeam, 0);
            tapGridCell(controls, player, 86, 24);
            if (firstCommand.attackTarget != wall.build || secondCommand.attackTarget != wall.build) {
                throw new AssertionError("Stock attack order must also target an opposing building");
            }
            wall.setBlock(Blocks.air);
            var factory = Vars.world.tile(78, 22);
            factory.setBlock(Blocks.groundFactory, player.team(), 0);
            tapGridCell(controls, player, 78, 22);
            tapGridCell(controls, player, 86, 24);
            var rally = ((UnitFactory.UnitFactoryBuild)factory.build).commandPos;
            if (rally == null || rally.x != 688f || rally.y != 192f || !player.plans().isEmpty()) {
                throw new AssertionError("Original factory rally point must be commanded without placing a block");
            }
            controls.update(player, 1 << 19, -1);
            if (controls.orders().active()) throw new AssertionError("B should leave Command mode");
            System.out.println("UPSTREAM_STOCK_COMMAND_REFERENCE formation=2 queued=ok attack=unit,building rally=ok");
        } finally {
            rules.fog = fog;
            first.remove();
            second.remove();
            enemy.remove();
        }
    }

    private static void tapGridCell(UpstreamLiveBuildControls controls, Unit player, int x, int y) {
        controls.update(player, 0, touch(32 + (x - 73) * 16 + 8, 48 + (31 - y) * 16 + 8));
    }

    private static void verifyInventory(Unit unit) {
        var controls = new UpstreamLiveBuildControls();
        controls.viewport(73, 20);
        Vars.player.set(unit.x, unit.y);
        var storage = Vars.world.tile(78, 22);
        storage.setBlock(Blocks.container, unit.team(), 0);
        var turretTile = Vars.world.tile(86, 26);
        turretTile.setBlock(Blocks.duo, unit.team(), 0);
        var turret = (ItemTurret.ItemTurretBuild)turretTile.build;
        storage.build.items.set(Items.copper, 200);
        storage.build.items.set(Items.lead, 10);
        unit.clearItem();
        int capacity = unit.maxAccepted(Items.copper);
        controls.update(unit, 0, touch(15, 96));
        controls.update(unit, 0, touch(120, 200));
        if (!controls.inventory().isOpen() || controls.inventory().iconIds()[0] != Items.copper.id) {
            throw new AssertionError("Stock storage did not expose its first item in the touch inventory");
        }
        controls.update(unit, 0, touch(160, 46));
        controls.update(unit, 0, touch(160, 71));
        if (unit.item() != Items.copper || unit.stack().amount != capacity
                || storage.build.items.get(Items.copper) != 200 - capacity
                || storage.build.items.get(Items.lead) != 10) {
            throw new AssertionError("Withdrawal changed stock transfer capacity or mixed the carried stack");
        }
        controls.update(unit, 0, touch(160, 218));

        int expectedItems = turret.acceptStack(Items.copper, capacity, unit);
        if (expectedItems <= 0 || expectedItems >= capacity) {
            throw new AssertionError("Expected the Duo to accept part of the carried stack");
        }
        float multiplier = ((ItemTurret)turret.block).ammoTypes.get(Items.copper).ammoMultiplier;
        controls.update(unit, 0, touch(15, 128));
        controls.update(unit, 0, touch(248, 136));
        if (unit.stack().amount != capacity - expectedItems || turret.totalAmmo != expectedItems * multiplier) {
            throw new AssertionError("Hand-loading did not use the stock Duo acceptance and ammo multiplier");
        }
        if (!unit.plans().isEmpty()) throw new AssertionError("Inventory actions must leave the build queue alone");

        var transfer = new UpstreamInventoryMenu();
        var sink = Vars.world.tile(87, 30);
        sink.setBlock(Blocks.container, unit.team(), 0);
        long now = 1_000_000_000L;
        var rules = Vars.state.rules;
        boolean onlyCore = rules.onlyDepositCore;
        try {
            rules.onlyDepositCore = true;
            transfer.deposit(sink.build, now);
            if (unit.stack().amount != capacity - expectedItems) {
                throw new AssertionError("The stock core-only deposit rule must prevent this deposit");
            }
            rules.onlyDepositCore = false;
            var distant = Vars.world.tile(78, 90);
            distant.setBlock(Blocks.container, unit.team(), 0);
            transfer.deposit(distant.build, now);
            var hostile = Vars.world.tile(75, 24);
            hostile.setBlock(Blocks.container, rules.waveTeam, 0);
            transfer.deposit(hostile.build, now);
            transfer.open(hostile.build);
            if (transfer.isOpen() || unit.stack().amount != capacity - expectedItems) {
                throw new AssertionError("Distant or hostile storage must reject item transfer");
            }
            transfer.deposit(sink.build, now);
            if (unit.stack().amount != 0) throw new AssertionError("Storage should accept the remaining stock items");
            unit.addItem(Items.copper, 5);
            transfer.deposit(sink.build, now + 1);
            if (unit.stack().amount != 5) throw new AssertionError("A second deposit must respect the stock cooldown");
            transfer.deposit(sink.build, now + 5_000_000_000L);
            if (unit.stack().amount != 0) throw new AssertionError("Depositing must resume after the stock cooldown");
        } finally {
            rules.onlyDepositCore = onlyCore;
        }
        System.out.println("UPSTREAM_INVENTORY_REFERENCE withdraw=" + capacity + " duo=" + expectedItems
            + " team=ok range=ok cooldown=ok");
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

    private static void verifySchematicPlacement(Unit unit) throws Exception {
        var folder = Files.createTempDirectory("min3dstry-stock-schematic");
        var broken = folder.resolve("00-broken.msch");
        var factory = folder.resolve("factory.msch");
        var rules = Vars.state.rules;
        boolean allowed = rules.schematicsAllowed, polygonProtection = rules.polygonCoreProtection;
        float buildRadius = rules.enemyCoreBuildRadius;
        try {
            rules.polygonCoreProtection = false;
            rules.enemyCoreBuildRadius = 0f;
            Files.writeString(broken, "broken header");
            var parts = Seq.with(
                new Schematic.Stile(Blocks.itemBridge, 0, 0, new Point2(2, 0), (byte)0),
                new Schematic.Stile(Blocks.sorter, 1, 0, Items.lead, (byte)0),
                new Schematic.Stile(Blocks.itemBridge, 2, 0, null, (byte)0)
            );
            Schematics.write(new Schematic(parts, new StringMap(), 3, 1), new Fi(factory.toString()));
            var controls = new UpstreamLiveBuildControls(new Fi(folder.toString()));
            controls.viewport(73, 20);

            Vars.state.rules.schematicsAllowed = false;
            controls.update(unit, 0, touch(15, 155));
            controls.update(unit, 0, touch(150, 79));
            if (!controls.schematics().isOpen()) throw new AssertionError("Schematic permission was bypassed");
            controls.update(unit, 1 << 19, -1);

            Vars.state.rules.schematicsAllowed = true;
            controls.update(unit, 0, touch(15, 155));
            controls.update(unit, 0, touch(150, 54));
            if (!controls.schematics().isOpen()) throw new AssertionError("Malformed schematic closed the picker");
            controls.update(unit, 0, touch(150, 79));
            tapGridCell(controls, unit, 83, 25);
            controls.update(unit, 1 << 19, -1);
            if (controls.schematics().active() || !unit.plans().isEmpty()) {
                throw new AssertionError("Cancelled preview must leave the build queue untouched");
            }

            for (int y = 23; y <= 25; y++) {
                var tile = Vars.world.tile(84, y);
                tile.setBlock(Blocks.air);
                tile.setFloor((Floor)Blocks.stone);
            }
            controls.update(unit, 0, touch(15, 155));
            controls.update(unit, 0, touch(150, 79));
            tapGridCell(controls, unit, 83, 25);
            controls.update(unit, 1 << 18, -1);
            controls.update(unit, 1 << 17, -1);
            var bridge = unit.plans().find(plan -> plan.y == 23);
            var sorter = unit.plans().find(plan -> plan.y == 24);
            var receiver = unit.plans().find(plan -> plan.y == 25);
            if (unit.plans().size != 3 || bridge == null || sorter == null || receiver == null
                    || bridge.x != 84 || sorter.x != 84 || receiver.x != 84
                    || bridge.block != Blocks.itemBridge || receiver.block != Blocks.itemBridge
                    || sorter.block != Blocks.sorter || sorter.config != Items.lead
                    || !(bridge.config instanceof Point2 link) || link.x != 0 || link.y != 2) {
                for (var plan : unit.plans()) {
                    System.err.println("plan " + plan.block.name + " " + plan.x + "," + plan.y + " cfg " + plan.config);
                }
                throw new AssertionError("Stock schematic rotation, placement, or configuration differed");
            }
            unit.clearBuilding();
            System.out.println("UPSTREAM_STOCK_SCHEMATIC plans=3 rotation=ok item=lead link=0,2");
        } finally {
            rules.schematicsAllowed = allowed;
            rules.polygonCoreProtection = polygonProtection;
            rules.enemyCoreBuildRadius = buildRadius;
            Files.deleteIfExists(factory);
            Files.deleteIfExists(broken);
            Files.deleteIfExists(folder);
        }
    }

    private static void verifySchematicCapture(Unit unit) throws Exception {
        var folder = Files.createTempDirectory("min3dstry-schematic-capture");
        var existing = folder.resolve("Schematic-1.msch");
        var exported = folder.resolve("Schematic-2.msch");
        var undiscovered = folder.resolve("Schematic-3.msch");
        var fog = Vars.fogControl;
        boolean oldFog = Vars.state.rules.fog, oldStaticFog = Vars.state.rules.staticFog;
        var controls = new UpstreamLiveBuildControls(new Fi(folder.toString()));
        controls.viewport(73, 20);
        try {
            Files.writeString(existing, "keep");
            for (int y = 30; y <= 31; y++) {
                for (int x = 87; x <= 88; x++) Vars.world.tile(x, y).setBlock(Blocks.air);
            }
            controls.update(unit, 0, touch(15, 155));
            controls.update(unit, 0, touch(268, 20));
            tapGridCell(controls, unit, 87, 30);
            tapGridCell(controls, unit, 88, 31);
            controls.update(unit, 1 << 17, -1);
            if (!controls.schematics().isOpen() || Files.exists(exported)) {
                throw new AssertionError("An empty selection must return without creating a file");
            }

            var source = Vars.world.tile(84, 23);
            var sorter = Vars.world.tile(84, 24);
            var receiver = Vars.world.tile(84, 25);
            var hid = Vars.world.tile(85, 24);
            source.setBlock(Blocks.itemBridge, unit.team(), 0);
            sorter.setBlock(Blocks.sorter, unit.team(), 0);
            receiver.setBlock(Blocks.itemBridge, unit.team(), 0);
            hid.setBlock(Blocks.copperWall, unit.team(), 0);
            source.build.wasVisible = sorter.build.wasVisible = receiver.build.wasVisible = true;
            hid.build.wasVisible = false;
            source.build.configureAny(new Point2(0, 2));
            sorter.build.configureAny(Items.lead);

            controls.update(unit, 0, touch(268, 20));
            tapGridCell(controls, unit, 84, 23);
            tapGridCell(controls, unit, 85, 25);
            controls.update(unit, 1 << 19, -1);
            if (controls.schematics().active() || Files.exists(exported)) {
                throw new AssertionError("Cancelling the capture must leave the directory unchanged");
            }

            controls.update(unit, 0, touch(15, 155));
            controls.update(unit, 0, touch(268, 20));
            tapGridCell(controls, unit, 85, 25);
            tapGridCell(controls, unit, 84, 23);
            controls.update(unit, 1 << 17, -1);
            if (!controls.schematics().isOpen() || !Files.readString(existing).equals("keep")) {
                throw new AssertionError("Capture should return to the picker and preserve existing files");
            }
            var schematic = Schematics.read(new Fi(exported.toString()));
            var first = schematic.tiles.find(tile -> tile.y == 0);
            var middle = schematic.tiles.find(tile -> tile.y == 1);
            var last = schematic.tiles.find(tile -> tile.y == 2);
            if (!schematic.name().equals("Schematic-2") || !"".equals(schematic.tags.get("description"))
                    || schematic.width != 1 || schematic.height != 3
                    || schematic.tiles.size != 3 || first == null || middle == null || last == null
                    || first.block != Blocks.itemBridge || middle.block != Blocks.sorter || last.block != Blocks.itemBridge
                    || middle.config != Items.lead || !(first.config instanceof Point2 link) || link.x != 0 || link.y != 2) {
                throw new AssertionError("Upstream reader did not recover captured footprint and configuration");
            }
            schematic.tags.put("name", "Bridge layout");
            Schematics.write(schematic, new Fi(exported.toString()));
            controls.schematics().open();
            var rows = new String(controls.schematics().labels(), java.nio.charset.StandardCharsets.UTF_8);
            if (!rows.contains("\0Bridge layout\0") || !rows.contains("\0Schematic-1\0")) {
                throw new AssertionError("Picker did not show the stock name and legacy filename fallback");
            }
            Vars.fogControl = new FogControl();
            Vars.state.rules.fog = Vars.state.rules.staticFog = true;
            controls.update(unit, 0, touch(268, 20));
            tapGridCell(controls, unit, 84, 23);
            tapGridCell(controls, unit, 84, 25);
            controls.update(unit, 1 << 17, -1);
            if (Files.exists(undiscovered)) {
                throw new AssertionError("The graphical ARM11 adapter exported an undiscovered selection");
            }
            Vars.fogControl = fog;
            Vars.state.rules.fog = oldFog;
            Vars.state.rules.staticFog = oldStaticFog;
            schematic.tags.put("description", "Keep this metadata");
            Schematics.write(schematic, new Fi(exported.toString()));
            var original = Files.readAllBytes(exported);
            controls.update(unit, 0, touch(268, 20));
            tapGridCell(controls, unit, 84, 24);
            tapGridCell(controls, unit, 84, 24);
            controls.schematics().saveCapture(unit.team(), () -> "Bridge layout", name -> false);
            if (!java.util.Arrays.equals(original, Files.readAllBytes(exported))) {
                throw new AssertionError("Declining replacement changed the original schematic");
            }
            controls.update(unit, 0, touch(268, 20));
            tapGridCell(controls, unit, 84, 24);
            tapGridCell(controls, unit, 84, 24);
            controls.schematics().saveCapture(unit.team(), () -> "Bridge layout", name -> true);
            var updated = Schematics.read(new Fi(exported.toString()));
            if (!updated.name().equals("Bridge layout") || !"Keep this metadata".equals(updated.tags.get("description"))
                    || updated.tiles.size != 1 || updated.tiles.first().block != Blocks.sorter || Files.exists(undiscovered)) {
                throw new AssertionError("Replacement did not replace the selected layout and preserve its metadata");
            }
            System.out.println("UPSTREAM_STOCK_SCHEMATIC_CAPTURE crop=1x3 blocks=3 link=0,2 item=lead fog=ok");
        } finally {
            Vars.fogControl = fog;
            Vars.state.rules.fog = oldFog;
            Vars.state.rules.staticFog = oldStaticFog;
            Files.deleteIfExists(undiscovered);
            Files.deleteIfExists(exported);
            Files.deleteIfExists(existing);
            Files.deleteIfExists(folder);
        }
    }

    private static void verifyStockLinks(Unit unit) {
        var controls = new UpstreamLiveBuildControls();
        controls.viewport(73, 20);
        var source = Vars.world.tile(74, 29);
        var receiver = Vars.world.tile(78, 29);
        var enemy = Vars.world.tile(74, 24);
        var distant = Vars.world.tile(87, 29);
        source.setBlock(Blocks.powerNode, unit.team(), 0);
        receiver.setBlock(Blocks.battery, unit.team(), 0);
        enemy.setBlock(Blocks.battery, Vars.state.rules.waveTeam, 0);
        distant.setBlock(Blocks.battery, unit.team(), 0);
        source.build.configure(new Point2[0]);

        controls.update(unit, 0, touch(305, 64));
        tapGridCell(controls, unit, source.x, source.y);
        if (!controls.configuring() || controls.buildingConfig().isOpen()) {
            throw new AssertionError("Link configuration must leave the world grid visible");
        }
        tapGridCell(controls, unit, enemy.x, enemy.y);
        if (source.build.power.links.contains(enemy.pos())) throw new AssertionError("Linked an enemy battery");
        tapGridCell(controls, unit, distant.x, distant.y);
        if (source.build.power.links.contains(distant.pos()) || controls.configuring()) {
            throw new AssertionError("An out-of-range power target must leave the source unlinked");
        }
        controls.update(unit, 0, touch(305, 64));
        tapGridCell(controls, unit, source.x, source.y);
        tapGridCell(controls, unit, receiver.x, receiver.y);
        if (!source.build.power.links.contains(receiver.pos()) || !receiver.build.power.links.contains(source.pos())
                || source.build.power.graph != receiver.build.power.graph) {
            throw new AssertionError("The stock power node did not join the two grids");
        }
        tapGridCell(controls, unit, receiver.x, receiver.y);
        if (source.build.power.links.contains(receiver.pos()) || receiver.build.power.links.contains(source.pos())) {
            throw new AssertionError("Tapping a linked power block must disconnect it");
        }
        controls.update(unit, 0, touch(305, 64));
        source.setBlock(Blocks.air);
        receiver.setBlock(Blocks.air);
        enemy.setBlock(Blocks.air);
        distant.setBlock(Blocks.air);

        Block[] transports = {Blocks.itemBridge, Blocks.phaseConveyor, Blocks.bridgeConduit, Blocks.phaseConduit,
            Blocks.massDriver, Blocks.payloadMassDriver, Blocks.largePayloadMassDriver};
        for (Block block : transports) {
            receiver = Vars.world.tile(source.x + Math.max(4, block.size + 1), source.y);
            source.setBlock(block, unit.team(), 0);
            receiver.setBlock(block, unit.team(), 0);
            controls.update(unit, 0, touch(305, 64));
            tapGridCell(controls, unit, source.x, source.y);
            tapGridCell(controls, unit, receiver.x, receiver.y);
            if (linkPosition(source.build) != receiver.pos() || controls.cursorX() != source.x) {
                throw new AssertionError("Stock " + block.name + " did not link to the tapped receiver");
            }
            if (block instanceof ItemBridge) {
                controls.update(unit, 0, touch(305, 64));
                controls.update(unit, 0, touch(305, 64));
                tapGridCell(controls, unit, receiver.x, receiver.y);
                tapGridCell(controls, unit, source.x, source.y);
                if (linkPosition(source.build) != -1 || linkPosition(receiver.build) != source.pos()) {
                    throw new AssertionError("Stock " + block.name + " did not reverse an incoming bridge");
                }
            } else {
                tapGridCell(controls, unit, receiver.x, receiver.y);
                if (linkPosition(source.build) != -1) throw new AssertionError("Stock " + block.name + " did not unlink");
            }
            if (controls.configuring()) controls.update(unit, 1 << 19, -1);
            if (controls.configuring()) throw new AssertionError("B must cancel link configuration");
            source.setBlock(Blocks.air);
            receiver.setBlock(Blocks.air);
        }
        if (!unit.plans().isEmpty()) throw new AssertionError("Link taps must never queue builds");
        System.out.println("UPSTREAM_STOCK_BUILDING_LINKS power=ok transports=" + transports.length);
    }

    private static int linkPosition(Building building) {
        if (building instanceof ItemBridge.ItemBridgeBuild bridge) return bridge.link;
        if (building instanceof MassDriver.MassDriverBuild driver) return driver.link;
        return ((PayloadMassDriver.PayloadDriverBuild)building).link;
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

    private static void verifyStockItemSelectors(UpstreamLiveBuildControls controls, Unit unit) {
        var expected = Vars.content.blocks().select(block -> block.configurations.containsKey(Item.class));
        if (expected.size != 9 || !expected.contains(Blocks.ductRouter) || !expected.contains(Blocks.landingPad)) {
            throw new AssertionError("Unexpected pinned item selector registry: " + expected);
        }
        var tile = Vars.world.tile(87, 26);
        var rules = Vars.state.rules;
        var planet = rules.planet;
        var sector = rules.sector;
        boolean infinite = rules.infiniteResources;
        boolean editing = rules.allowEditWorldProcessors;
        boolean editor = rules.editor;
        try {
            rules.sector = null;
            rules.planet = Planets.serpulo;
            rules.infiniteResources = false;
            rules.allowEditWorldProcessors = false;
            rules.editor = false;
            tile.setBlock(Blocks.landingPad, unit.team(), 0);
            controls.buildingConfig().open(tile.build);
            if (controls.buildingConfig().isOpen()) {
                throw new AssertionError("Stock landing pad access restricted in this game mode");
            }
            rules.infiniteResources = true;
            for (var block : expected) {
                tile.setBlock(block, unit.team(), 0);
                selectStockItem(controls, unit, tile.build, Items.copper);
            }
            rules.planet = Planets.erekir;
            tile.setBlock(Blocks.ductRouter, unit.team(), 0);
            selectStockItem(controls, unit, tile.build, Items.beryllium);
        } finally {
            rules.planet = planet;
            rules.sector = sector;
            rules.infiniteResources = infinite;
            rules.allowEditWorldProcessors = editing;
            rules.editor = editor;
            tile.setBlock(Blocks.air);
        }
        System.out.println("UPSTREAM_STOCK_ITEM_SELECTORS blocks=" + expected.size + " planet=erekir clear=ok");
    }

    private static void selectStockItem(UpstreamLiveBuildControls controls, Unit unit, Building build, Item item) {
        var available = Vars.content.items().select(UpstreamItemChoices::accepts);
        int itemIndex = available.indexOf(item, true);
        if (itemIndex < 0 || item == Items.beryllium && available.contains(Items.copper)) {
            throw new AssertionError("Unexpected stock item eligibility for " + item.name + " in " + available);
        }
        int itemPage = itemIndex / UpstreamBuildMenu.ROWS, itemRow = itemIndex % UpstreamBuildMenu.ROWS;
        for (int attempt = 0; attempt < 2; attempt++) {
            controls.update(unit, 0, touch(305, 64));
            controls.update(unit, 0, touch(264, 136));
            var config = controls.buildingConfig();
            if (!config.isOpen() || config.unitPlans()) {
                throw new AssertionError("Stock item menu did not open for " + build.block.name);
            }
            if (attempt == 0) {
                for (int page = 0; page < itemPage; page++) {
                    controls.update(unit, 0, touch(276, UpstreamBuildMenu.FOOTER_TOP + 8));
                }
            }
            if (config.iconIds()[itemRow] != item.id
                    || (attempt == 0 ? config.selectedRow() >= 0 : config.selectedRow() != itemRow)) {
                throw new AssertionError("Stock item or selection missing for " + build.block.name);
            }
            controls.update(unit, 0, touch(160, UpstreamBuildMenu.ROW_TOP + itemRow * UpstreamBuildMenu.ROW_HEIGHT));
            if (build.config() != (attempt == 0 ? item : null)) {
                throw new AssertionError("Stock item configuration failed for " + build.block.name);
            }
        }
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
