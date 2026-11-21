import port.render.UpstreamCrackAtlas;
import port.render.UpstreamFluidAtlas;
import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.Draw;
import arc.struct.IntSet;
import arc.util.Time;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.core.World;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.CacheLayer;
import mindustry.type.UnitType;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild;
import mindustry.world.blocks.liquid.Conduit;
import mindustry.world.blocks.distribution.Conveyor.ConveyorBuild;
import mindustry.world.blocks.distribution.Sorter;

/** Presents the received Glacier world through the ARM11 terrain renderer. */
public final class UpstreamRenderedNetworkProbe {
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
        UpstreamCrackAtlas.load();
        loadUnitGraphics();
        viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        target.initialize();
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS labels");
        var labels = String.join("\0", Blocks.conveyor.localizedName, Blocks.copperWall.localizedName,
            Core.bundle.get("keybind.rotate.name"), Core.bundle.get("waves.remove"),
            "List", "Config", Core.bundle.get("back")) + "\0";
        target.buildLabels(labels.getBytes(StandardCharsets.UTF_8));
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS ready");
    }

    static void loadUnitGraphics() {
        for (var type : new UnitType[]{UnitTypes.gamma, UnitTypes.dagger}) {
            type.loadIcon();
            type.load();
        }
    }

    private void render(Unit unit) {
        if (lastLeft == -1 && !Vars.state.map.name().equals("Glacier")) {
            throw new IllegalStateException("The rendered multiplayer probe is packaged for Glacier");
        }
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
        } finally {
            Time.delta = oldDelta;
        }
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
                if (unit.type() != UnitTypes.gamma && unit.type() != UnitTypes.dagger) continue;
                int x = World.toTile(unit.x()), y = World.toTile(unit.y());
                if (x < viewport.left || x >= viewport.left + UpstreamTerrainDrawProbe.VIEW_WIDTH
                        || y < viewport.bottom || y >= viewport.bottom + UpstreamTerrainDrawProbe.VIEW_HEIGHT) continue;
                unit.type().draw(unit);
                if (unit == controlled) draws++;
            }
        } finally {
            Draw.sort(false);
        }
        var block = buildingControls.block();
        var catalog = buildingControls.catalog();
        var config = buildingControls.itemConfig();
        int shortcut = block == Blocks.conveyor ? 0 : block == Blocks.copperWall ? 1 : -1;
        target.buildOverlay(buildingControls.cursorX() - viewport.left, buildingControls.cursorY() - viewport.bottom,
            shortcut, buildingControls.rotation(), buildingControls.removing(), block.rotate,
            catalog.isOpen() || config.isOpen(), buildingControls.configuring());
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

    static boolean supportsBuildingDraw(Building build) {
        var block = build.block;
        // These blocks use the sprite operations available in the live backend.
        return block == Blocks.coreNucleus || block == Blocks.conveyor || block == Blocks.copperWall
            || block == Blocks.copperWallLarge || block == Blocks.router || block instanceof Sorter
            || block == Blocks.mechanicalDrill || block instanceof Conduit
            || block == Blocks.phaseWeaver || block == Blocks.overdriveProjector
            || block == Blocks.overdriveDome || block == Blocks.shockwaveTower
            || build instanceof PayloadConveyorBuild conveyor && (conveyor.item == null || supportsCargo(conveyor));
    }

    private static boolean supportsCargo(PayloadConveyorBuild conveyor) {
        return conveyor.item instanceof BuildPayload payload
            && (payload.block() == Blocks.copperWall || payload.block() == Blocks.copperWallLarge
                || payload.block() == Blocks.mechanicalDrill || payload.build instanceof ConveyorBuild
                || payload.build instanceof PayloadConveyorBuild);
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
