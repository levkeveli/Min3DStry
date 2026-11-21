import port.render.UpstreamCrackAtlas;
import port.render.UpstreamFluidAtlas;
import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.Draw;
import arc.struct.IntSet;
import arc.struct.ObjectSet;
import arc.util.Time;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.core.World;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Tankc;
import mindustry.gen.Unit;
import mindustry.gen.UnitWaterMove;
import mindustry.graphics.CacheLayer;
import mindustry.type.UnitType;
import mindustry.world.Block;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.liquid.Conduit;
import mindustry.world.blocks.distribution.Conveyor.ConveyorBuild;
import mindustry.world.blocks.distribution.Sorter;
import mindustry.world.blocks.storage.CoreBlock;

/** Presents the received multiplayer world through the ARM11 terrain renderer. */
public final class UpstreamRenderedNetworkProbe {
    private static final ObjectSet<UnitType> drawableUnits = new ObjectSet<>();
    private final Fi atlas;
    private final UpstreamTerrainRenderProbe target = new UpstreamTerrainRenderProbe(false);
    private final UpstreamBuildingLayers buildingLayers = new UpstreamBuildingLayers();
    private final UpstreamLiveBuildControls buildingControls = new UpstreamLiveBuildControls();
    private final Runnable drawEntities = this::drawEntities;
    private final IntSet reportedConveyors = new IntSet();
    private UpstreamTerrainDrawProbe.Viewport viewport;
    private Unit controlled;
    private int draws;
    private long previousVisualFrame;
    private int lastLeft = -1, lastBottom = -1;
    private byte[] lastMenu;
    private Block lastPrimaryShortcut;
    private List<UpstreamObjectiveHud.Row> lastObjectives;
    private byte[][] objectiveLabels = new byte[0][];

    private UpstreamRenderedNetworkProbe(Fi atlas) {
        this.atlas = atlas;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var endpoint = UpstreamLiveNetworkProbe.endpointFile(args);
        var view = new UpstreamRenderedNetworkProbe(endpoint.sibling("sprites.aatls"));
        int language = view.target.mountAssets();
        var preference = endpoint.sibling("locale.txt");
        String requested = preference.exists() && preference.length() <= 16 ? preference.readString("UTF-8").trim() : "";
        Core.bundle = UpstreamInterfaceLocale.load(new Fi("/romfs/bundles/bundle"), language, requested);
        var locale = Core.bundle.getLocale();
        System.out.println("UPSTREAM_INTERFACE_LOCALE system=" + language + " bundle="
            + (locale.getLanguage().isEmpty() ? "en" : locale) + " router=" + Core.bundle.get("block.router.name")
            + " copper=" + Core.bundle.get("item.copper.name"));
        CacheLayer.init();
        UpstreamLiveNetworkProbe.run(endpoint, view::loadGraphics, view::render, view.buildingControls);
    }

    private void loadGraphics() {
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS atlas");
        UpstreamTerrainDrawProbe.loadAtlas(atlas, UpstreamFluidAtlas::load);
        Vars.content.loadColors(new Fi("/romfs/block_colors.png"));
        UpstreamCrackAtlas.load();
        loadUnitGraphics();
        viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        target.initialize();
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS ready");
    }

    private void updateBuildLabels() {
        var primary = buildingControls.shortcut(0);
        if (primary == lastPrimaryShortcut) return;
        var secondary = buildingControls.shortcut(1);
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS labels");
        var labels = String.join("\0", primary.localizedName, secondary.localizedName,
            Core.bundle.get("keybind.rotate.name"), Core.bundle.get("waves.remove"),
            "List", "Config", Core.bundle.get("back"), Core.bundle.get("command.mine")) + "\0";
        target.buildLabels(labels.getBytes(StandardCharsets.UTF_8));
        lastPrimaryShortcut = primary;
        System.out.println("UPSTREAM_BUILD_SHORTCUTS primary=" + primary.name + " secondary=" + secondary.name);
    }

    static UnitType[] loadUnitGraphics() {
        var types = new UnitType[]{
            UnitTypes.alpha, UnitTypes.beta, UnitTypes.gamma,
            UnitTypes.evoke, UnitTypes.incite, UnitTypes.emanate,
            UnitTypes.dagger, UnitTypes.mace, UnitTypes.fortress, UnitTypes.scepter, UnitTypes.reign, UnitTypes.crawler,
            UnitTypes.nova, UnitTypes.pulsar, UnitTypes.quasar, UnitTypes.vela,
            UnitTypes.flare, UnitTypes.horizon, UnitTypes.zenith, UnitTypes.antumbra, UnitTypes.eclipse,
            UnitTypes.mono, UnitTypes.poly,
            UnitTypes.risso, UnitTypes.minke, UnitTypes.bryde, UnitTypes.sei, UnitTypes.omura,
            UnitTypes.retusa, UnitTypes.oxynoe, UnitTypes.cyerce, UnitTypes.aegires, UnitTypes.navanax,
            UnitTypes.stell, UnitTypes.locus, UnitTypes.precept, UnitTypes.vanquish, UnitTypes.conquer
        };
        drawableUnits.clear();
        for (var type : types) {
            type.loadIcon();
            type.load();
            drawableUnits.add(type);
        }
        return types;
    }

    private void render(Unit unit) {
        updateBuildLabels();
        controlled = unit;
        float x = unit == null ? Vars.player.x() : unit.x();
        float y = unit == null ? Vars.player.y() : unit.y();
        viewport.moveTo(World.toTile(x) - UpstreamTerrainDrawProbe.VIEW_WIDTH / 2,
                        World.toTile(y) - UpstreamTerrainDrawProbe.VIEW_HEIGHT / 2);
        advanceVisualState();
        draws = 0;
        viewport.draw(drawEntities);
        buildingControls.viewport(viewport.left, viewport.bottom);
        if (unit != null && (lastLeft != viewport.left || lastBottom != viewport.bottom)) {
            lastLeft = viewport.left;
            lastBottom = viewport.bottom;
            System.out.println("UPSTREAM_NETWORK_FRAME unit=" + unit.id() + " left=" + lastLeft
                + " bottom=" + lastBottom + " sprites=" + draws);
        }
    }

    private void advanceVisualState() {
        long now = System.nanoTime();
        float delta = previousVisualFrame == 0 ? 1f
            : Math.min((now - previousVisualFrame) * (60f / 1_000_000_000f), Vars.maxDeltaClient);
        previousVisualFrame = now;
        if (Vars.state.isPaused()) return;

        // Advance the original animation clock and conduit smoothing without replaying liquid transfers.
        float oldDelta = Time.delta;
        Time.delta = delta;
        Time.setInternalTime(Time.getInternalTime() + delta);
        try {
            for (var build : Groups.build) {
                if (build instanceof Conduit.ConduitBuild conduit) conduit.advanceLiquidDraw();
            }
            for (var unit : Groups.unit) {
                if (unit instanceof UnitWaterMove boat) boat.advanceWake();
                if (unit instanceof Tankc tank) advanceTankTreads(tank);
            }
        } finally {
            Time.delta = oldDelta;
        }
    }

    static void advanceTankTreads(Tankc tank) {
        // TankComp advances the tread strip by distance on clients. Reuse HitboxComp's position history.
        tank.updateLastPosition();
        tank.treadTime(tank.treadTime() + tank.deltaLen());
    }

    private void drawEntities() {
        buildingLayers.render(viewport, Vars.player.team(), UpstreamRenderedNetworkProbe::supportsBuildingDraw);
        for (var building : Groups.build) {
            if (building.block == Blocks.conveyor && viewport.contains(building)
                    && reportedConveyors.add(building.pos())) {
                System.out.println("UPSTREAM_NETWORK_CONVEYOR x=" + building.tileX()
                    + " y=" + building.tileY() + " rotation=" + building.rotation);
            }
        }
        Draw.sort(true);
        try {
            for (var unit : Groups.unit) {
                if (!supportsUnit(unit.type())) continue;
                int x = World.toTile(unit.x()), y = World.toTile(unit.y());
                if (x < viewport.left || x >= viewport.left + UpstreamTerrainDrawProbe.VIEW_WIDTH
                        || y < viewport.bottom || y >= viewport.bottom + UpstreamTerrainDrawProbe.VIEW_HEIGHT) continue;
                unit.draw();
                if (unit == controlled) draws++;
            }
        } finally {
            Draw.sort(false);
        }
        drawObjectives();
        var block = buildingControls.block();
        var catalog = buildingControls.catalog();
        var config = buildingControls.itemConfig();
        int shortcut = block == buildingControls.shortcut(0) ? 0 : block == buildingControls.shortcut(1) ? 1 : -1;
        target.buildOverlay(buildingControls.cursorX() - viewport.left, buildingControls.cursorY() - viewport.bottom,
            shortcut, buildingControls.rotation(), buildingControls.removing(), block.rotate,
            catalog.isOpen() || config.isOpen(), buildingControls.configuring(), buildingControls.selectingMine());
        if (catalog.isOpen()) {
            drawMenu(catalog.labels(), catalog.iconIds(), catalog.selectedRow(block),
                catalog.previousPage(), catalog.nextPage(), true);
        } else if (config.isOpen()) {
            drawMenu(config.labels(), config.iconIds(), config.selectedRow(),
                config.previousPage(), config.nextPage(), false);
        } else {
            lastMenu = null;
        }
    }

    private void drawObjectives() {
        var rows = UpstreamObjectiveHud.collect(Vars.state.rules);
        boolean changed = !rows.equals(lastObjectives);
        if (changed) {
            objectiveLabels = new byte[rows.size()][];
            for (int i = 0; i < rows.size(); i++) {
                objectiveLabels[i] = (rows.get(i).text() + "\0").getBytes(StandardCharsets.UTF_8);
            }
            lastObjectives = rows;
        }
        float y = 4f;
        for (int i = 0; i < rows.size() && y < 240f; i++) {
            var row = rows.get(i);
            y = target.drawObjective(objectiveLabels[i], row.icon(), y);
            if (changed && y < 240f) {
                System.out.println("UPSTREAM_OBJECTIVE_HUD icon=" + row.contentName()
                    + " text=" + row.text().replace('\n', '|'));
            }
        }
    }

    static boolean supportsBuildingDraw(Building build) {
        var block = build.block;
        // These blocks use the sprite operations available in the live backend.
        return block instanceof CoreBlock || block == Blocks.conveyor || block == Blocks.copperWall
            || block == Blocks.duct || block == Blocks.berylliumWall
            || block == Blocks.copperWallLarge || block == Blocks.router || block instanceof Sorter
            || block == Blocks.mechanicalDrill || block instanceof Conduit
            || block == Blocks.phaseWeaver || block == Blocks.overdriveProjector
            || block == Blocks.overdriveDome || block == Blocks.shockwaveTower
            || build instanceof PayloadConveyorBuild conveyor && (conveyor.item == null || supportsCargo(conveyor));
    }

    private static boolean supportsCargo(PayloadConveyorBuild conveyor) {
        if (conveyor.item instanceof UnitPayload payload) return supportsUnit(payload.unit.type());
        return conveyor.item instanceof BuildPayload payload
            && (payload.block() == Blocks.copperWall || payload.block() == Blocks.copperWallLarge
                || payload.block() == Blocks.mechanicalDrill || payload.build instanceof ConveyorBuild
                || payload.build instanceof PayloadConveyorBuild);
    }

    private static boolean supportsUnit(UnitType type) {
        return type != null && drawableUnits.contains(type);
    }

    private void drawMenu(byte[] labels, int[] icons, int selected,
                          boolean previous, boolean next, boolean categories) {
        int drawn = target.buildCatalog(labels, icons, selected, previous, next, categories);
        if (lastMenu != labels) {
            System.out.println("UPSTREAM_MENU_ICONS type=" + (categories ? "block" : "item") + " drawn=" + drawn);
            lastMenu = labels;
        }
    }
}
