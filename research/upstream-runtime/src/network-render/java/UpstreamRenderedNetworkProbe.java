import port.render.UpstreamCrackAtlas;
import port.render.UpstreamFluidAtlas;
import port.render.UpstreamWorldLabel;
import port.audio.Arm11Audio;
import port.render.UpstreamLabelText;
import port.logic.UpstreamClientLogic;
import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.graphics.Camera;
import arc.graphics.g2d.Draw;
import arc.mock.MockGraphics;
import arc.math.Mathf;
import arc.struct.IntSet;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Time;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.core.World;
import mindustry.core.UI;
import mindustry.game.EventType.WorldLoadEvent;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Payloadc;
import mindustry.gen.Sounds;
import mindustry.gen.Tankc;
import mindustry.gen.Unit;
import mindustry.gen.UnitWaterMove;
import mindustry.graphics.CacheLayer;
import mindustry.type.UnitType;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;
import mindustry.world.Block;
import mindustry.type.Category;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.liquid.Conduit.ConduitBuild;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.distribution.Conveyor.ConveyorBuild;
import mindustry.world.blocks.logic.CanvasBlock.CanvasBuild;
import mindustry.world.blocks.logic.LogicDisplay.LogicDisplayBuild;
import mindustry.world.blocks.logic.TileableLogicDisplay.TileableLogicDisplayBuild;

/** Presents the received multiplayer world through the ARM11 terrain renderer. */
public final class UpstreamRenderedNetworkProbe {
    private static final int VISIBLE_CHAT_MESSAGES = 10;
    private static final ObjectSet<UnitType> drawableUnits = new ObjectSet<>();
    private final Fi atlas;
    private final UpstreamTerrainRenderProbe target = new UpstreamTerrainRenderProbe(false);
    private final UpstreamBuildingLayers buildingLayers = new UpstreamBuildingLayers();
    private final UpstreamWeatherLayers weatherLayers = new UpstreamWeatherLayers();
    private final Runnable drawWeatherUnder = weatherLayers::drawUnder;
    private final UpstreamLiveBuildControls buildingControls = new UpstreamLiveBuildControls();
    private final UpstreamChatInput chatInput = new UpstreamChatInput();
    private final Runnable drawEntities = this::drawEntities;
    private final IntSet reportedConveyors = new IntSet();
    private final IntSet reportedEnemies = new IntSet();
    private final IntSet goneEnemies = new IntSet();
    private UpstreamTerrainDrawProbe.Viewport viewport;
    private Unit controlled;
    private int draws;
    private boolean projectilesVisible, projectilesActive;
    private long previousVisualFrame;
    private long frameId;
    private float frameSeconds;
    private int lastLeft = -1, lastBottom = -1;
    private final UpstreamWorldLabel.Target worldLabel = this::drawWorldLabel;
    private final ObjectSet<String> reportedWorldLabels = new ObjectSet<>();
    private final ObjectSet<String> reportedStockWorldLabels = new ObjectSet<>();
    private final Seq<ChatLine> chatLines = new Seq<>();
    private float chatFade;
    private UpstreamLabelText.Label encodedLabel;
    private int[] encodedCharacters;
    private byte[] lastMenu;
    private Block lastPrimaryShortcut;
    private List<UpstreamObjectiveHud.Row> lastStatus;
    private byte[][] statusLabels = new byte[0][];
    private int[][] statusCharacters = new int[0][];
    private List<UpstreamObjectiveHud.Row> lastObjectives;
    private byte[][] objectiveLabels = new byte[0][];
    private int[][] objectiveCharacters = new int[0][];
    private boolean[] reportedStockObjectives = new boolean[0];

    private UpstreamRenderedNetworkProbe(Fi atlas) {
        this.atlas = atlas;
        buildingControls.schematics().nameCapturesWith(
            () -> UpstreamLiveNetworkProbe.whileKeepingAlive(UpstreamTerrainRenderProbe::readSchematicName),
            name -> UpstreamLiveNetworkProbe.whileKeepingAlive(() -> UpstreamTerrainRenderProbe.confirmSchematicReplacement(name)));
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var endpoint = UpstreamLiveNetworkProbe.endpointFile(args);
        run(endpoint, endpoint.sibling("sprites.aatls"));
    }

    static void run(Fi endpoint, Fi atlas) throws IOException, InterruptedException {
        run(endpoint.parent(), endpoint.readString(), atlas);
    }

    static void run(Fi directory, String address, Fi atlas) throws IOException, InterruptedException {
        var view = new UpstreamRenderedNetworkProbe(atlas);
        int language = view.target.mountAssets();
        var preference = directory.child("locale.txt");
        String requested = preference.exists() && preference.length() <= 16 ? preference.readString("UTF-8").trim() : "";
        Core.bundle = UpstreamInterfaceLocale.loadForStockClient(new Fi("/romfs/bundles/bundle"), language, requested);
        var locale = Core.bundle.getLocale();
        System.out.println("UPSTREAM_INTERFACE_LOCALE system=" + language + " bundle="
            + (locale.getLanguage().isEmpty() ? "en" : locale) + " router=" + Core.bundle.get("block.router.name")
            + " copper=" + Core.bundle.get("item.copper.name"));
        CacheLayer.init();
        try {
            Arm11Audio.open();
            var kick = UpstreamLiveNetworkProbe.run(directory, address, view::prepareClient, view::loadGraphics,
                view::render, view.buildingControls, view::receiveChat, view.chatInput);
            if (kick != null) view.showServerKick(kick);
        } finally {
            Arm11Audio.close();
        }
    }

    private void showServerKick(UpstreamLiveNetworkProbe.ServerKick kick) throws InterruptedException {
        var reason = kick.stockReason;
        if (reason != null && (reason.quiet || reason == mindustry.net.Packets.KickReason.serverRestarting)) return;
        String extra = reason == null ? null : reason.extraText();
        String title = extra == null ? Core.bundle.get("disconnect") : reason.toString();
        String detail = reason == null ? kick.getMessage() : extra == null ? reason.toString() : extra;

        target.begin(0, 0, UpstreamTerrainDrawProbe.VIEW_WIDTH, UpstreamTerrainDrawProbe.VIEW_HEIGHT);
        try {
            float y = drawKickLabel(title, 6f);
            if (detail != null) drawKickLabel(detail, y);
        } finally {
            target.end();
        }
        System.out.println("UPSTREAM_LIVE_KICK_NOTICE title=" + title
            + " detail=" + (detail == null ? "" : detail.replace('\n', ' ').replace('\r', ' ')));
        while (!UpstreamLiveNetworkProbe.kickNoticeDismissed()) Thread.sleep(16);
    }

    private float drawKickLabel(String label, float y) {
        var parsed = UpstreamLabelText.parse(UI.formatIcons(label));
        return target.drawObjective((label + "\0").getBytes(StandardCharsets.UTF_8), encodeLabel(parsed), null, y);
    }

    private void prepareClient() {
        if (Core.camera == null) Core.camera = new Camera();
        Arm11Audio.prepareMusicControl();
        Events.on(WorldLoadEvent.class, event -> {
            Arm11Audio.reset();
            weatherLayers.reset();
        });
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

    private void loadGraphics() {
        var locale = Core.bundle.getLocale();
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS atlas");
        UpstreamTerrainDrawProbe.loadAtlas(atlas, UpstreamFluidAtlas::load);
        weatherLayers.load();
        UpstreamLogicGraphics.load(new Fi("/romfs"));
        Vars.content.loadColors(new Fi("/romfs/block_colors.png"));
        UpstreamCrackAtlas.load();
        drawableUnits.clear();
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
            "List", "Config", Core.bundle.get("back"), Core.bundle.get("command.mine"),
            Core.bundle.get("category.items"), "Give", Core.bundle.get("command"), "Type", "Stance",
            "Ctrl", Core.bundle.get("back"), "Schem") + "\0";
        target.buildLabels(labels.getBytes(StandardCharsets.UTF_8));
        lastPrimaryShortcut = primary;
        System.out.println("UPSTREAM_BUILD_SHORTCUTS primary=" + primary.name + " secondary=" + secondary.name);
    }

    static boolean loadUnitGraphics(UnitType type) {
        if (type == null) return false;
        if (!drawableUnits.contains(type)) {
            type.loadIcon();
            type.load();
            drawableUnits.add(type);
            System.out.println("UPSTREAM_NETWORK_UNIT_ART type=" + type.name);
        }
        return true;
    }

    static boolean loadUnitGraphics(Unit unit) {
        if (!loadUnitGraphics(unit.type())) return false;
        if (unit instanceof Payloadc carrier && carrier.hasPayload()
                && carrier.payloads().first() instanceof UnitPayload payload) {
            loadUnitGraphics(payload.unit);
        }
        return true;
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
        viewport.draw(drawWeatherUnder, drawEntities);
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
            Time.update();
            if (!Vars.state.isPaused()) Groups.weather.update();
            weatherLayers.advance();
            Arm11Audio.updateClientAudio(now);
            UpstreamClientLogic.update(delta);
            for (var build : Groups.build) {
                if (build instanceof TileableLogicDisplayBuild display) display.updateTile();
                if (build instanceof ConduitBuild conduit) conduit.advanceLiquidDraw();
                if (build instanceof UnitFactoryBuild factory) advanceFactoryDraw(factory);
            }
            for (var unit : Groups.unit) {
                if (unit instanceof UnitWaterMove boat) boat.advanceWake();
                if (unit instanceof Tankc tank) advanceTankTreads(tank);
            }
            UpstreamClientProjectiles.update();
        } finally {
            Time.delta = oldDelta;
        }
    }

    static void advanceFactoryDraw(UnitFactoryBuild factory) {
        // UnitFactoryBuild.updateTile also advances production and emits units.
        // Reproduce its draw-only state from the stock plan and received efficiency.
        boolean active = factory.efficiency > 0f && factory.currentPlan != -1;
        if (active) {
            factory.time += factory.edelta() * factory.speedScl * Vars.state.rules.unitBuildSpeed(factory.team);
        }
        factory.speedScl = Mathf.lerpDelta(factory.speedScl, active ? 1f : 0f, 0.05f);
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
                int x = World.toTile(unit.x()), y = World.toTile(unit.y());
                if (x < viewport.left || x >= viewport.left + UpstreamTerrainDrawProbe.VIEW_WIDTH
                        || y < viewport.bottom || y >= viewport.bottom + UpstreamTerrainDrawProbe.VIEW_HEIGHT) continue;
                if (!loadUnitGraphics(unit)) continue;
                unit.draw();
                if (unit == controlled) draws++;
                if (unit.type() == UnitTypes.dagger && unit.team == Vars.state.rules.waveTeam
                        && reportedEnemies.add(unit.id)) {
                    System.out.println("UPSTREAM_LIVE_ENEMY type=dagger id=" + unit.id
                        + " x=" + unit.x() + " y=" + unit.y());
                }
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
        var enemies = reportedEnemies.iterator();
        while (enemies.hasNext) {
            int id = enemies.next();
            if (Groups.unit.getByID(id) == null && goneEnemies.add(id)) {
                System.out.println("UPSTREAM_LIVE_ENEMY_GONE id=" + id);
            }
        }
        UpstreamObjectiveMarkers.draw(Vars.state.rules, 240f / (UpstreamTerrainDrawProbe.VIEW_HEIGHT * Vars.tilesize),
            worldLabel);
        var orders = buildingControls.orders();
        orders.draw(viewport);
        var schematics = buildingControls.schematics();
        schematics.draw(viewport);
        weatherLayers.drawOver();
        drawObjectives(drawStatusHud());
        drawChat();
        if (chatInput.open()) {
            target.drawChatKeyboard(chatInput.labels());
            return;
        }
        var block = buildingControls.block();
        var catalog = buildingControls.catalog();
        var config = buildingControls.buildingConfig();
        var inventory = buildingControls.inventory();
        var commandMenu = orders.menu();
        int shortcut = block == buildingControls.shortcut(0) ? 0 : block == buildingControls.shortcut(1) ? 1 : -1;
        target.buildOverlay(buildingControls.cursorX() - viewport.left, buildingControls.cursorY() - viewport.bottom,
            shortcut, buildingControls.rotation(), buildingControls.removing(), block.rotate,
            catalog.isOpen() || config.isOpen() || inventory.isOpen() || commandMenu.isOpen() || schematics.isOpen(),
            buildingControls.configuring(), buildingControls.selectingMine(), buildingControls.inventoryAction(),
            orders.mode(), buildingControls.selectingControl(), schematics.active());
        if (schematics.isOpen()) {
            drawMenu(schematics.labels(), schematics.icons(), -1, schematics.previousPage(), schematics.nextPage(),
                UpstreamTerrainRenderProbe.SCHEMATIC_CATALOG);
        } else if (commandMenu.isOpen()) {
            target.buildCatalogRows(commandMenu.labels(), commandMenu.icons(), orders.activeMenuRows(),
                commandMenu.previous(), commandMenu.next(),
                commandMenu.stances() ? UpstreamTerrainRenderProbe.STANCE_CATALOG : UpstreamTerrainRenderProbe.COMMAND_CATALOG);
        } else if (catalog.isOpen()) {
            drawMenu(catalog.labels(), catalog.iconIds(), catalog.selectedRow(block),
                catalog.previousPage(), catalog.nextPage(), UpstreamTerrainRenderProbe.BLOCK_CATALOG);
        } else if (config.isOpen()) {
            drawMenu(config.labels(), config.iconIds(), config.selectedRow(),
                config.previousPage(), config.nextPage(),
                config.unitPlans() ? UpstreamTerrainRenderProbe.UNIT_CATALOG : UpstreamTerrainRenderProbe.ITEM_CATALOG);
        } else if (inventory.isOpen()) {
            drawMenu(inventory.labels(), inventory.iconIds(), -1, inventory.previousPage(), inventory.nextPage(),
                UpstreamTerrainRenderProbe.ITEM_CATALOG);
        } else {
            lastMenu = null;
        }
    }

    private record ChatLine(int sequence, byte[] text, int[] characters) {}

    private int chatSequence;
    private int latestDrawnChat;

    private void receiveChat(String message) {
        byte[] text = (message + "\0").getBytes(StandardCharsets.UTF_8);
        int[] characters = encodeLabel(UpstreamLabelText.parse(UI.formatIcons(message)));
        chatLines.insert(0, new ChatLine(++chatSequence, text, characters));
        chatLines.truncate(VISIBLE_CHAT_MESSAGES);
        chatFade = Math.min(chatFade + 1f, VISIBLE_CHAT_MESSAGES) + 1f;
        Sounds.uiChat.play();
    }

    private void drawChat() {
        // ChatFragment displays the newest lines first and expires one line per 180 game frames.
        int rows = chatInput.open() ? chatLines.size : Math.min(chatLines.size, Mathf.ceil(chatFade));
        float bottom = 234f;
        for (int i = 0; i < rows; i++) {
            var line = chatLines.get(i);
            bottom = target.drawChatLine(line.text(), line.characters(), bottom);
            if (bottom < 0f) break;
            if (line.sequence() > latestDrawnChat) {
                latestDrawnChat = line.sequence();
                System.out.println("UPSTREAM_LIVE_CHAT_DRAW line=" + latestDrawnChat);
            }
        }
        chatFade = Math.max(0f, chatFade - Math.min(frameSeconds * 60f, Vars.maxDeltaClient) / 180f);
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

    private float drawStatusHud() {
        var rows = UpstreamStatusHud.collect();
        boolean changed = !rows.equals(lastStatus);
        if (changed) {
            statusLabels = new byte[rows.size()][];
            statusCharacters = new int[rows.size()][];
            for (int i = 0; i < rows.size(); i++) {
                statusLabels[i] = (rows.get(i).text() + "\0").getBytes(StandardCharsets.UTF_8);
                statusCharacters[i] = encodeLabel(UpstreamLabelText.parse(UI.formatIcons(rows.get(i).markup())));
            }
            lastStatus = rows;
            System.out.println("UPSTREAM_STATUS_HUD wave=" + Vars.state.wave
                + " enemies=" + Math.max(0, Vars.state.enemies)
                + " copper=" + UpstreamStatusHud.copper()
                + " rows=" + rows.size());
        }
        float y = 4f;
        for (int i = 0; i < rows.size() && y < 240f; i++) {
            y = target.drawObjective(statusLabels[i], statusCharacters[i], rows.get(i).icon(), y);
        }
        return y;
    }

    private void drawObjectives(float y) {
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
            || UpstreamBuildingLayers.supportsPowerDraw(block) || UpstreamBuildingLayers.supportsDistributionDraw(block)
            || block.category == Category.liquid || block instanceof UnitFactory || block instanceof BaseTurret
            || UpstreamBuildingLayers.supportsMaterialDraw(block) || block == Blocks.copperWall
            || block == Blocks.berylliumWall || block == Blocks.copperWallLarge
            || UpstreamBuildingLayers.supportsExtractorDraw(block)
            || block == Blocks.overdriveProjector
            || block == Blocks.overdriveDome || block == Blocks.shockwaveTower
            || build instanceof PayloadConveyorBuild conveyor && (conveyor.item == null || supportsCargo(conveyor));
    }

    private static boolean supportsCargo(PayloadConveyorBuild conveyor) {
        if (conveyor.item instanceof UnitPayload payload) return loadUnitGraphics(payload.unit);
        return conveyor.item instanceof BuildPayload payload
            && (payload.block() == Blocks.copperWall || payload.block() == Blocks.copperWallLarge
                || payload.block() == Blocks.mechanicalDrill || payload.build instanceof ConveyorBuild
                || payload.build instanceof PayloadConveyorBuild);
    }

    private void drawMenu(byte[] labels, int[] icons, int selected,
                            boolean previous, boolean next, int catalog) {
        int drawn = target.buildCatalog(labels, icons, selected, previous, next, catalog);
        if (lastMenu != labels) {
            String type = switch (catalog) {
                case UpstreamTerrainRenderProbe.BLOCK_CATALOG -> "block";
                case UpstreamTerrainRenderProbe.UNIT_CATALOG -> "unit";
                default -> "item";
            };
            System.out.println("UPSTREAM_MENU_ICONS type=" + type + " drawn=" + drawn);
            lastMenu = labels;
        }
    }
}
