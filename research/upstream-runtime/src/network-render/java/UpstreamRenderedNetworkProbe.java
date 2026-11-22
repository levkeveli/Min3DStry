import port.render.UpstreamCrackAtlas;
import port.render.UpstreamFluidAtlas;
import port.render.UpstreamLogicAtlas;
import port.render.UpstreamLogicFont;
import port.render.UpstreamLogicFramebuffer;
import port.render.UpstreamWorldLabel;
import port.logic.UpstreamClientLogic;
import port.world.DarknessScratch;
import port.audio.Arm11Audio;
import port.render.UpstreamLabelText;
import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.Draw;
import arc.mock.MockGraphics;
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
import mindustry.core.UI;
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
import mindustry.world.blocks.logic.CanvasBlock.CanvasBuild;
import mindustry.world.blocks.logic.LogicDisplay.LogicDisplayBuild;
import mindustry.world.blocks.logic.TileableLogicDisplay.TileableLogicDisplayBuild;
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
    private boolean projectilesVisible;
    private boolean projectilesActive;
    private long previousVisualFrame;
    private long frameId;
    private float frameSeconds;
    private int lastLeft = -1, lastBottom = -1;
    private final UpstreamWorldLabel.Target worldLabel = this::drawWorldLabel;
    private final ObjectSet<String> reportedWorldLabels = new ObjectSet<>();
    private final ObjectSet<String> reportedStockWorldLabels = new ObjectSet<>();
    private UpstreamLabelText.Label encodedLabel;
    private int[] encodedCharacters;
    private byte[] lastMenu;
    private Block lastPrimaryShortcut;
    private List<UpstreamObjectiveHud.Row> lastObjectives;
    private byte[][] objectiveLabels = new byte[0][];
    private int[][] objectiveCharacters = new int[0][];
    private boolean[] reportedStockObjectives = new boolean[0];

    private UpstreamRenderedNetworkProbe(Fi atlas) {
        this.atlas = atlas;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var endpoint = UpstreamLiveNetworkProbe.endpointFile(args);
        var view = new UpstreamRenderedNetworkProbe(endpoint.sibling("sprites.aatls"));
        int language = view.target.mountAssets();
        var preference = endpoint.sibling("locale.txt");
        String requested = preference.exists() && preference.length() <= 16 ? preference.readString("UTF-8").trim() : "";
        Core.bundle = UpstreamInterfaceLocale.loadForStockClient(new Fi("/romfs/bundles/bundle"), language, requested);
        var locale = Core.bundle.getLocale();
        System.out.println("UPSTREAM_INTERFACE_LOCALE system=" + language + " bundle="
            + (locale.getLanguage().isEmpty() ? "en" : locale) + " router=" + Core.bundle.get("block.router.name")
            + " copper=" + Core.bundle.get("item.copper.name"));
        CacheLayer.init();
        try {
            Arm11Audio.open();
            UpstreamLiveNetworkProbe.run(endpoint, view::prepareClient, view::worldLoaded, view::render, view.buildingControls);
        } finally {
            Arm11Audio.close();
        }
    }

    private void prepareClient() {
        Arm11Audio.prepareMusicControl();
        DarknessScratch.useNativeMemory();
        UpstreamClientProjectiles.initialize();
        UpstreamClientLogic.initialize(new Fi("/romfs/logicids.dat"));
        Core.graphics = new MockGraphics() {
            @Override public int getWidth() { return 400; }
            @Override public int getHeight() { return 240; }
            @Override public float getDensity() { return 1f; }
            @Override public float getDeltaTime() { return frameSeconds; }
            @Override public long getFrameId() { return frameId; }
        };
        var locale = Core.bundle.getLocale();
        Core.settings.put("locale", locale.getLanguage().isEmpty() ? "en" : locale.toString());
    }

    private void worldLoaded() {
        Arm11Audio.reset();
        if (viewport == null) loadGraphics();
        previousVisualFrame = 0;
        projectilesVisible = false;
        projectilesActive = false;
        lastLeft = lastBottom = -1;
        reportedConveyors.clear();
        reportedWorldLabels.clear();
        lastObjectives = null;
    }

    private void loadGraphics() {
        UpstreamLogicFont.load(new Fi("/romfs/logic-font.dat"));
        var locale = Core.bundle.getLocale();
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS atlas");
        UpstreamTerrainDrawProbe.loadAtlas(atlas, UpstreamFluidAtlas::load);
        var displayAtlas = new UpstreamLogicAtlas(new Fi("/romfs/world-atlas"));
        UpstreamLogicFramebuffer.atlasPixels((texture, x, y) ->
            displayAtlas.sample(UpstreamTerrainDrawProbe.pageIndex(texture), x, y));
        Blocks.logicDisplay.load();
        Blocks.largeLogicDisplay.load();
        Blocks.tileLogicDisplay.load();
        Vars.content.loadColors(new Fi("/romfs/block_colors.png"));
        UpstreamCrackAtlas.load();
        loadUnitGraphics();
        UpstreamClientProjectiles.load();
        viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        target.initialize();
        boolean stock = target.loadStockFont(locale.getLanguage().equals("ja"));
        System.out.println("UPSTREAM_INTERFACE_FONT source=" + (stock ? "v160.8" : "system"));
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
        frameId++;
        updateBuildLabels();
        controlled = unit;
        float x = unit == null ? Vars.player.x() : unit.x();
        float y = unit == null ? Vars.player.y() : unit.y();
        viewport.moveTo(World.toTile(x) - UpstreamTerrainDrawProbe.VIEW_WIDTH / 2,
                        World.toTile(y) - UpstreamTerrainDrawProbe.VIEW_HEIGHT / 2);
        float width = UpstreamTerrainDrawProbe.VIEW_WIDTH * Vars.tilesize;
        float height = UpstreamTerrainDrawProbe.VIEW_HEIGHT * Vars.tilesize;
        Arm11Audio.updateCamera((viewport.left - 0.5f) * Vars.tilesize + width / 2f,
            (viewport.bottom - 0.5f) * Vars.tilesize + height / 2f, width, height);
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
        frameSeconds = previousVisualFrame == 0 ? 1f / 60f
            : (now - previousVisualFrame) * (1f / 1_000_000_000f);
        previousVisualFrame = now;
        float oldDelta = Time.delta;
        Time.updateGlobal();
        Time.delta = oldDelta;
        // Advance the original animation clock and conduit smoothing without replaying liquid transfers.
        float delta = Math.min(frameSeconds * 60f, Vars.maxDeltaClient);
        Time.delta = delta;
        try {
            boolean paused = Vars.state.isPaused();
            if (!paused) {
                Time.update();
                Groups.weather.update();
            }
            Arm11Audio.updateClientAudio(now);
            if (paused) return;
            for (var build : Groups.build) {
                if (build instanceof TileableLogicDisplayBuild display) display.updateTile();
                if (build instanceof Conduit.ConduitBuild conduit) conduit.advanceLiquidDraw();
            }
            UpstreamClientLogic.update(frameSeconds * 60f);
            for (var unit : Groups.unit) {
                if (unit instanceof UnitWaterMove boat) boat.advanceWake();
                if (unit instanceof Tankc tank) advanceTankTreads(tank);
            }
            UpstreamClientProjectiles.update(controlled);
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
            int bullets = UpstreamClientProjectiles.draw(viewport);
            boolean active = !Groups.bullet.isEmpty();
            if (projectilesActive != active || projectilesVisible != (bullets > 0)) {
                boolean trail = Groups.bullet.find(bullet -> bullet.trail != null) != null;
                System.out.println("UPSTREAM_NETWORK_PROJECTILES visible=" + bullets
                    + " active=" + Groups.bullet.size() + " trail=" + trail);
            }
            projectilesVisible = bullets > 0;
            projectilesActive = active;
        } finally {
            Draw.sort(false);
        }
        UpstreamObjectiveMarkers.draw(Vars.state.rules, 240f / (UpstreamTerrainDrawProbe.VIEW_HEIGHT * Vars.tilesize),
            worldLabel);
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

    private void drawWorldLabel(UpstreamLabelText.Label text, float x, float y, int flags, float size, int align, int lineAlign) {
        if (encodedLabel != text) {
            encodedCharacters = encodeLabel(text);
            encodedLabel = text;
        }
        int font = target.drawWorldLabel(text.plain(), encodedCharacters, x, y, flags, size, align, lineAlign);
        if (font != 0 && reportedWorldLabels.add(text.plain())) {
            System.out.println("UPSTREAM_WORLD_LABEL x=" + x + " y=" + y + " text=" + text.plain().replace('\n', '|'));
        }
        if (font == 2 && reportedStockWorldLabels.add(text.plain())) {
            System.out.println("UPSTREAM_WORLD_LABEL_FONT x=" + x + " y=" + y + " source=v160.8");
        }
    }

    private static int[] encodeLabel(UpstreamLabelText.Label text) {
        int[] encoded = new int[text.characters().size() * 7];
        int offset = 0;
        for (var glyph : text.characters()) {
            var art = glyph.art();
            encoded[offset++] = glyph.codepoint();
            encoded[offset++] = glyph.color();
            encoded[offset++] = art == null ? -1 : UpstreamTerrainDrawProbe.pageIndex(art);
            encoded[offset++] = art == null ? 0 : art.getX();
            encoded[offset++] = art == null ? 0 : art.getY();
            encoded[offset++] = art == null ? 0 : art.width;
            encoded[offset++] = art == null ? 0 : art.height;
        }
        return encoded;
    }

    private void drawObjectives() {
        var rows = UpstreamObjectiveHud.collect(Vars.state.rules);
        boolean changed = !rows.equals(lastObjectives);
        if (changed) {
            objectiveLabels = new byte[rows.size()][];
            objectiveCharacters = new int[rows.size()][];
            reportedStockObjectives = new boolean[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                objectiveLabels[i] = (rows.get(i).text() + "\0").getBytes(StandardCharsets.UTF_8);
                objectiveCharacters[i] = encodeLabel(UpstreamLabelText.parse(UI.formatIcons(rows.get(i).markup())));
            }
            lastObjectives = rows;
        }
        float y = 4f;
        for (int i = 0; i < rows.size() && y < 240f; i++) {
            var row = rows.get(i);
            y = target.drawObjective(objectiveLabels[i], objectiveCharacters[i], row.icon(), y);
            if (changed && y < 240f) {
                System.out.println("UPSTREAM_OBJECTIVE_HUD icon=" + row.contentName()
                    + " text=" + row.text().replace('\n', '|'));
            }
            if (target.drewStockObjectiveFont() && !reportedStockObjectives[i]) {
                System.out.println("UPSTREAM_OBJECTIVE_FONT source=v160.8 icon=" + row.contentName()
                    + " text=" + row.text().replace('\n', '|'));
                reportedStockObjectives[i] = true;
            }
        }
    }

    static boolean supportsBuildingDraw(Building build) {
        var block = build.block;
        // These blocks use the sprite operations available in the live backend.
        return build instanceof CanvasBuild || build instanceof LogicDisplayBuild
            || block instanceof CoreBlock || block == Blocks.conveyor || block == Blocks.copperWall
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
