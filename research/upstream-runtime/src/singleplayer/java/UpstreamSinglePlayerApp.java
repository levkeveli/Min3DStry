import port.render.UpstreamCrackAtlas;
import port.render.UpstreamFluidAtlas;
import port.render.UpstreamLabelText;
import port.render.UpstreamWorldLabel;
import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.IntSet;
import arc.struct.ObjectSet;
import arc.util.Time;
import arc.util.Strings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.UnitTypes;
import mindustry.core.UI;
import mindustry.core.World;
import mindustry.entities.bullet.BulletType;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.WaveEvent;
import mindustry.game.Gamemode;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.io.SaveIO;
import mindustry.maps.Map;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Tankc;
import mindustry.gen.Unit;
import mindustry.gen.UnitWaterMove;
import mindustry.type.UnitType;
import mindustry.type.Category;
import mindustry.world.Block;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.production.Pump;
import mindustry.world.blocks.production.Separator;
import mindustry.world.blocks.storage.StorageBlock;
import mindustry.world.blocks.units.UnitFactory;

/** Graphical offline single-player Mindustry v160.8 ARM11 entry point on Ground Zero. */
public final class UpstreamSinglePlayerApp {
    private static final long FRAME_NS = 16_666_667L;
    private static final float DEAD_ZONE = 20f / 127f;
    private static final float STOP_SPEED = 0.03f;
    private static final ObjectSet<UnitType> drawableUnits = new ObjectSet<>();

    private final UpstreamTerrainRenderProbe target = new UpstreamTerrainRenderProbe(false);
    private final UpstreamBuildingLayers buildingLayers = new UpstreamBuildingLayers();
    private final UpstreamLiveBuildControls buildingControls = new UpstreamLiveBuildControls();
    private final Runnable drawEntities = this::drawEntities;
    private final UpstreamWorldLabel.Target worldLabel = this::drawWorldLabel;
    private final Vec2 moveInput = new Vec2();

    private UpstreamSinglePlayerSession session;
    private UpstreamTerrainDrawProbe.Viewport viewport;
    private boolean rendererReady;
    private Unit controlled;
    private Fi campaignSlot;
    private int draws;
    private long previousVisualFrame;
    private float frameSeconds = 1f / 60f;
    private int lastLeft = -1, lastBottom = -1;
    private int lastCopper = -1;
    private boolean moving;
    private String inputDevice;
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
    private final IntSet reportedEnemies = new IntSet();

    public static void main(String[] args) throws IOException {
        try {
            new UpstreamSinglePlayerApp().run(args);
        } catch (IOException | RuntimeException failure) {
            System.out.println("UPSTREAM_SP_ERROR " + failure.getClass().getName() + ": " + failure.getMessage());
            throw failure;
        }
    }

    private void run(String[] args) throws IOException {
        int language = target.mountAssets();
        int bootInput = UpstreamSinglePlayerInput.poll();
        boolean customRequested = args.length == 0 && bootInput >= 0
            && (bootInput & UpstreamSinglePlayerInput.CUSTOM_GAME_HELD) != 0;
        Fi mapFile = args.length >= 1 ? new Fi(args[0]) : firstExisting("/romfs/maps/groundZero.msav", "/3ds/min3dstry/groundZero.msav");
        Fi saveFile = args.length >= 2 ? new Fi(args[1]) : new Fi("/3ds/min3dstry/singleplayer-save.msav");
        Fi atlas = firstExisting("/romfs/sprites.aatls", "/3ds/min3dstry/sprites.aatls");
        Fi preference = new Fi("/3ds/min3dstry/locale.txt");
        String requested = preference.exists() && preference.length() <= 16 ? preference.readString("UTF-8").trim() : "";

        Core.bundle = UpstreamInterfaceLocale.loadForStockClient(new Fi("/romfs/bundles/bundle"), language, requested);
        var locale = Core.bundle.getLocale();
        String bundleTag = locale.getLanguage().isEmpty() ? "en" : locale.toString();
        System.out.println("UPSTREAM_INTERFACE_LOCALE system=" + language + " bundle=" + bundleTag
            + " router=" + Core.bundle.get("block.router.name") + " copper=" + Core.bundle.get("item.copper.name"));

        Fi mapDir = saveFile.parent() != null ? saveFile.parent() : new Fi("/3ds/min3dstry");
        session = UpstreamSinglePlayerSession.create(mapDir, bundleTag, new Fi("/romfs/planets"));
        UpstreamSectorCapture.install();
        Events.on(WaveEvent.class, event -> {
            String warning = UpstreamGuardianWarning.find(Vars.state.rules, Vars.state.wave);
            if (warning != null) UpstreamNotification.show(warning, 5f);
        });
        try {
            CustomGame custom = null;
            Fi customSave = mapDir.child("custom-game-save.msav");
            if (customRequested) {
                var maps = UpstreamCustomGameMaps.discover(new Fi("/romfs/maps/default"), mapDir.child("maps"));
                boolean canResume = customSave.exists() && SaveIO.isSaveValid(customSave);
                if (!maps.isEmpty() || canResume) {
                    loadInterfaceRenderer();
                    custom = chooseCustomGame(maps, canResume);
                    if (custom == null) return;
                }
            }
            List<UpstreamSinglePlayerSession.SectorSlot> sectorSaves =
                args.length < 2 && custom == null ? session.sectorSaves() : List.of();
            UpstreamSinglePlayerSession.SectorSlot selected = null;
            if (sectorSaves.size() == 1) {
                selected = sectorSaves.get(0);
                System.out.println("UPSTREAM_CAMPAIGN_SELECTED slot=" + selected.file.name() + " source=single name=" + selected.sector.name());
            } else if (!sectorSaves.isEmpty()) {
                loadInterfaceRenderer();
                selected = chooseSector(sectorSaves, mapDir.child("campaign-select.txt"));
                if (selected == null) return;
            }
            // Load the world before initializing world sprites to keep peak TeaVM heap bounded.
            if (selected != null) {
                saveFile = selected.file;
                if (!session.start(null, saveFile, "campaign", selected.sector)) return;
                campaignSlot = saveFile;
            } else if (custom == null) {
                session.start(mapFile, saveFile, null);
            } else {
                saveFile = customSave;
                if (custom.map == null) {
                    System.out.println("UPSTREAM_CUSTOM_GAME_SELECTED source=save");
                    session.start(null, saveFile, "customgame-save");
                } else {
                    System.out.println("UPSTREAM_CUSTOM_GAME_SELECTED map=" + custom.map.file.name()
                        + " mode=" + custom.mode.name());
                    Gamemode mode = session.startCustomGame(custom.map, custom.mode);
                    System.out.println("UPSTREAM_CUSTOM_GAME_STARTED map=" + custom.map.file.name() + " mode=" + mode.name());
                }
            }
            loadInterfaceGraphics(atlas);
            loadWorldGraphics();
            Events.on(BlockBuildEndEvent.class, event -> {
                if (event.tile == null) return;
                if (!event.breaking && event.tile.build != null) {
                    System.out.println("UPSTREAM_SP_BUILT block=" + event.tile.block().name
                        + " x=" + event.tile.x + " y=" + event.tile.y
                        + " rotation=" + event.tile.build.rotation);
                } else if (event.breaking && event.tile.block() == Blocks.air) {
                    System.out.println("UPSTREAM_SP_REMOVED x=" + event.tile.x + " y=" + event.tile.y);
                }
            });
            runMainLoop(saveFile);
        } finally {
            if (campaignSlot != null) {
                try {
                    session.save(campaignSlot);
                    System.out.println("UPSTREAM_CAMPAIGN_SAVED slot=" + campaignSlot.name());
                } catch (Throwable failure) {
                    System.out.println("UPSTREAM_CAMPAIGN_SAVE_ERROR "
                        + failure.getClass().getName() + ": " + failure.getMessage());
                }
            }
            session.shutdown();
            System.out.println("UPSTREAM_SP_EXIT");
        }
    }

    private static Fi firstExisting(String primary, String fallback) {
        var first = new Fi(primary);
        return first.exists() ? first : new Fi(fallback);
    }

    private record CustomGame(Map map, Gamemode mode) {}

    private record MenuRows(byte[][] labels, int[][] characters) {}

    private CustomGame chooseCustomGame(List<Map> maps, boolean canResume) {
        Gamemode[] modes = {Gamemode.survival, Gamemode.sandbox, Gamemode.attack, Gamemode.pvp};
        int firstMap = canResume ? 1 : 0;
        int count = firstMap + maps.size();
        int selected = 0, mode = 0, lastX = 0, lastY = 0, renderedRow = -1, renderedMode = -1;
        MenuRows rows = null;
        target.buildLabels(new byte[UpstreamTerrainRenderProbe.PALETTE_LABEL_COUNT]);
        System.out.println("UPSTREAM_CUSTOM_GAME_MENU maps=" + maps.size());
        // Consume the launcher's button edges before accepting menu input.
        UpstreamSinglePlayerInput.poll();
        while (true) {
            long nextFrame = System.nanoTime() + FRAME_NS;
            int input = UpstreamSinglePlayerInput.poll();
            if (input < 0 || (input & (1 << 19)) != 0) {
                System.out.println("UPSTREAM_CUSTOM_GAME_CANCEL input=" + input);
                return null;
            }
            int x = (byte)input;
            int y = (byte)(input >> 8);
            if (y > 60 && lastY <= 20) selected = Math.max(0, selected - 1);
            if (y < -60 && lastY >= -20) selected = Math.min(count - 1, selected + 1);
            if (x > 60 && lastX <= 20) mode = (mode + 1) % modes.length;
            if (x < -60 && lastX >= -20) mode = (mode + modes.length - 1) % modes.length;
            lastX = x;
            lastY = y;
            if ((input & (1 << 17)) != 0) {
                return new CustomGame(selected < firstMap ? null : maps.get(selected - firstMap), modes[mode]);
            }
            if (selected != renderedRow || mode != renderedMode) {
                rows = encodeMenuRows(customGameCaptions(maps, firstMap, selected, modes[mode]));
                renderedRow = selected;
                renderedMode = mode;
            }
            drawMenuRows(rows);
            while (System.nanoTime() < nextFrame) Thread.yield();
        }
    }

    static String[] customGameCaptions(List<Map> maps, int firstMap, int selected, Gamemode mode) {
        int first = (selected / 6) * 6;
        int end = Math.min(first + 6, firstMap + maps.size());
        String[] captions = new String[1 + end - first];
        String title = Core.bundle.get("customgame");
        captions[0] = selected < firstMap ? title : title + " / " + mode;
        for (int i = first; i < end; i++) {
            String label = i < firstMap ? Core.bundle.get("loadgame")
                : Strings.stripColors(maps.get(i - firstMap).name());
            captions[1 + i - first] = (i == selected ? "> " : "  ") + label;
        }
        return captions;
    }

    private static MenuRows encodeMenuRows(String[] captions) {
        byte[][] labels = new byte[captions.length][];
        int[][] characters = new int[captions.length][];
        for (int i = 0; i < captions.length; i++) {
            var caption = captions[i];
            labels[i] = (caption + "\0").getBytes(StandardCharsets.UTF_8);
            characters[i] = encodePlainMenuLabel(caption);
        }
        return new MenuRows(labels, characters);
    }

    private static int[] encodePlainMenuLabel(String caption) {
        int[] glyphs = new int[caption.codePointCount(0, caption.length()) * 7];
        int slot = 0;
        for (int offset = 0; offset < caption.length();) {
            int code = caption.codePointAt(offset);
            glyphs[slot] = code;
            glyphs[slot + 1] = -1;
            glyphs[slot + 2] = -1;
            slot += 7;
            offset += Character.charCount(code);
        }
        return glyphs;
    }

    private void drawMenuRows(MenuRows rows) {
        target.begin(0, 0, UpstreamTerrainDrawProbe.VIEW_WIDTH, UpstreamTerrainDrawProbe.VIEW_HEIGHT);
        try {
            float rowY = 6f;
            for (int i = 0; i < rows.labels.length; i++) {
                rowY = target.drawObjective(rows.labels[i], rows.characters[i], null, rowY);
            }
        } finally {
            target.end();
        }
    }

    private void loadInterfaceGraphics(Fi atlas) {
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS atlas");
        UpstreamTerrainDrawProbe.loadAtlas(atlas, UpstreamFluidAtlas::load);
        Vars.content.loadColors(new Fi("/romfs/block_colors.png"));
        UpstreamCrackAtlas.load();
        viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        loadInterfaceRenderer();
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS ready");
    }

    private void loadInterfaceRenderer() {
        if (rendererReady) return;
        target.initialize();
        var locale = Core.bundle.getLocale();
        boolean stock = target.loadStockFont(locale.getLanguage().equals("ja"));
        rendererReady = true;
        System.out.println("UPSTREAM_INTERFACE_FONT source=" + (stock ? "v160.8" : "system"));
    }

    private void loadWorldGraphics() {
        loadUnitGraphics();
        loadBulletGraphics();
    }

    private UpstreamSinglePlayerSession.SectorSlot chooseSector(
            List<UpstreamSinglePlayerSession.SectorSlot> slots, Fi automatic) {
        int selected = 0;
        int lastAxis = 0;
        System.out.println("UPSTREAM_CAMPAIGN_MENU slots=" + slots.size());
        if (automatic.exists() && !automatic.isDirectory() && automatic.length() <= 16) {
            try {
                int index = Integer.parseInt(automatic.readString("UTF-8").trim());
                if (index >= 0 && index < slots.size()) {
                    var slot = slots.get(index);
                    System.out.println("UPSTREAM_CAMPAIGN_SELECTED slot=" + slot.file.name() + " source=auto name=" + slot.sector.name());
                    return slot;
                }
            } catch (NumberFormatException ignored) {
                // Invalid selection files leave the controller menu available.
            }
        }
        int renderedRow = -1;
        MenuRows rows = null;
        target.buildLabels(new byte[UpstreamTerrainRenderProbe.PALETTE_LABEL_COUNT]);
        UpstreamSinglePlayerInput.poll();
        while (true) {
            long nextFrame = System.nanoTime() + FRAME_NS;
            int input = UpstreamSinglePlayerInput.poll();
            if (input == -1) {
                System.out.println("UPSTREAM_CAMPAIGN_CANCEL");
                return null;
            }
            int axis = (byte)((input >> 8) & 0xff);
            if (axis > 60 && lastAxis <= 20 && selected > 0) {
                selected--;
            } else if (axis < -60 && lastAxis >= -20 && selected + 1 < slots.size()) {
                selected++;
            }
            lastAxis = axis;
            if ((input & (1 << 17)) != 0) {
                var slot = slots.get(selected);
                System.out.println("UPSTREAM_CAMPAIGN_SELECTED slot=" + slot.file.name() + " name=" + slot.sector.name());
                return slot;
            }
            if (renderedRow != selected) {
                rows = encodeMenuRows(campaignCaptions(slots, selected));
                renderedRow = selected;
            }
            drawMenuRows(rows);
            while (System.nanoTime() < nextFrame) Thread.yield();
        }
    }

    static String[] campaignCaptions(List<UpstreamSinglePlayerSession.SectorSlot> slots, int selected) {
        int first = (selected / 6) * 6;
        int end = Math.min(first + 6, slots.size());
        String[] captions = new String[1 + end - first];
        captions[0] = Core.bundle.get("campaign");
        for (int i = first; i < end; i++) {
            var sector = slots.get(i).sector;
            captions[1 + i - first] = (i == selected ? "> " : "  ")
                + sector.planet.localizedName + " / " + sector.name();
        }
        return captions;
    }

    private static void loadUnitGraphics() {
        var types = new UnitType[]{
            UnitTypes.alpha, UnitTypes.beta, UnitTypes.gamma,
            UnitTypes.dagger, UnitTypes.mace, UnitTypes.fortress, UnitTypes.crawler,
            UnitTypes.flare, UnitTypes.horizon, UnitTypes.zenith,
            UnitTypes.mono, UnitTypes.poly,
            UnitTypes.nova, UnitTypes.pulsar
        };
        drawableUnits.clear();
        for (var type : types) {
            type.loadIcon();
            type.load();
            drawableUnits.add(type);
        }
    }

    private static void loadBulletGraphics() {
        boolean wasHeadless = Vars.headless;
        Vars.headless = false;
        try {
            loadTurretBullets(Blocks.duo);
            loadTurretBullets(Blocks.scatter);
            for (var type : drawableUnits) {
                for (var weapon : type.weapons) {
                    if (weapon.bullet != null) weapon.bullet.load();
                }
            }
        } finally {
            Vars.headless = wasHeadless;
        }
    }

    private static void loadTurretBullets(Block block) {
        if (block instanceof ItemTurret turret) {
            for (var entry : turret.ammoTypes) {
                BulletType bullet = entry.value;
                if (bullet != null) bullet.load();
            }
        }
    }

    private void runMainLoop(Fi saveFile) throws IOException {
        var unit = session.ensurePlayerSpawn();
        var core = session.core();
        lastCopper = core == null ? 0 : core.items.get(Items.copper);
        if (unit != null) {
            System.out.println("UPSTREAM_SP_READY unit=" + unit.id() + " x=" + unit.x() + " y=" + unit.y()
                + " copper=" + lastCopper + " wave=" + Vars.state.wave);
        }

        long previousTime = System.nanoTime();
        long accumulator = 0L;
        while (true) {
            UpstreamNotification.update(1f / 60f);
            int input = UpstreamSinglePlayerInput.poll();
            if (input == -1) break;
            int touch = UpstreamSinglePlayerInput.touch();

            if ((input & UpstreamSinglePlayerInput.PAUSE_PRESSED) != 0) {
                boolean paused = session.togglePause();
                System.out.println("UPSTREAM_SP_PAUSE paused=" + paused + " updateId=" + Vars.state.updateId);
            }
            if ((input & UpstreamSinglePlayerInput.WAVE_PRESSED) != 0) {
                int wave = session.triggerWave();
                int enemies = Groups.unit.count(u -> u.team == Vars.state.rules.waveTeam && !u.dead);
                System.out.println("UPSTREAM_SP_WAVE wave=" + wave + " enemies=" + enemies);
            }
            if ((input & UpstreamSinglePlayerInput.SAVE_PRESSED) != 0) {
                session.save(saveFile);
            }

            unit = session.ensurePlayerSpawn();
            if (unit != null) {
                buildingControls.update(unit, input, touch);
            }

            moveInput.set((byte)input / 127f, (byte)(input >>> 8) / 127f);
            if (moveInput.len2() < DEAD_ZONE * DEAD_ZONE) {
                moveInput.setZero();
            } else {
                moveInput.limit(1f);
            }

            long now = System.nanoTime();
            long elapsed = Math.min(Math.max(0L, now - previousTime), FRAME_NS * 4L);
            previousTime = now;
            accumulator += elapsed;

            if (Vars.state.isPaused()) {
                accumulator = 0L;
            } else {
                int steps = 0;
                while (accumulator >= FRAME_NS && steps < 2) {
                    session.stepSimulation(moveInput.x, moveInput.y);
                    accumulator -= FRAME_NS;
                    steps++;
                }
                if (steps == 2 && accumulator >= FRAME_NS * 2L) {
                    accumulator = 0L;
                }
            }

            unit = session.ensurePlayerSpawn();
            updateTelemetry(unit, input);
            render(unit);
            Thread.yield();
        }
    }

    private void updateTelemetry(Unit unit, int input) {
        if (unit != null) {
            if (!moveInput.isZero() && !moving) {
                moving = true;
                inputDevice = (input & UpstreamSinglePlayerInput.DPAD_ACTIVE) != 0 ? "dpad" : "circle";
                System.out.println("UPSTREAM_SP_MOVING unit=" + unit.id() + " input=" + inputDevice);
            } else if (moving && moveInput.isZero() && unit.vel.len2() < STOP_SPEED * STOP_SPEED) {
                moving = false;
                System.out.println("UPSTREAM_SP_STOPPED unit=" + unit.id() + " input=" + inputDevice
                    + " x=" + unit.x() + " y=" + unit.y());
            }
        }
        var core = session.core();
        int copper = core == null ? 0 : core.items.get(Items.copper);
        if (copper != lastCopper) {
            if (copper > lastCopper) {
                System.out.println("UPSTREAM_SP_MINED item=copper copper=" + copper);
            }
            lastCopper = copper;
        }
    }

    private void updateBuildLabels() {
        var primary = buildingControls.shortcut(0);
        if (primary == lastPrimaryShortcut) return;
        var secondary = buildingControls.shortcut(1);
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS labels");
        var labels = String.join("\0", primary.localizedName, secondary.localizedName,
            Core.bundle.get("keybind.rotate.name"), Core.bundle.get("waves.remove"),
            "List", "Config", Core.bundle.get("back"), Core.bundle.get("command.mine"),
            Core.bundle.get("category.items"), "Give", Core.bundle.get("command")) + "\0";
        target.buildLabels(labels.getBytes(StandardCharsets.UTF_8));
        lastPrimaryShortcut = primary;
        System.out.println("UPSTREAM_BUILD_SHORTCUTS primary=" + primary.name + " secondary=" + secondary.name);
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
            System.out.println("UPSTREAM_SP_FRAME unit=" + unit.id() + " left=" + lastLeft
                + " bottom=" + lastBottom + " sprites=" + draws + " wave=" + Vars.state.wave);
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
        if (Vars.state.isPaused()) return;

        float delta = Math.min(frameSeconds * 60f, Vars.maxDeltaClient);
        Time.delta = delta;
        Time.setInternalTime(Time.getInternalTime() + delta);
        try {
            for (var unit : Groups.unit) {
                if (unit instanceof UnitWaterMove boat) boat.advanceWake();
                if (unit instanceof Tankc tank) {
                    tank.updateLastPosition();
                    tank.treadTime(tank.treadTime() + tank.deltaLen());
                }
            }
        } finally {
            Time.delta = oldDelta;
        }
    }

    private void drawEntities() {
        buildingLayers.render(viewport, Vars.player.team(), UpstreamSinglePlayerApp::supportsBuildingDraw);
        drawConstructBuilds();
        Draw.sort(true);
        try {
            for (var unit : Groups.unit) {
                if (!drawableUnits.contains(unit.type())) continue;
                int x = World.toTile(unit.x()), y = World.toTile(unit.y());
                if (x < viewport.left || x >= viewport.left + UpstreamTerrainDrawProbe.VIEW_WIDTH
                        || y < viewport.bottom || y >= viewport.bottom + UpstreamTerrainDrawProbe.VIEW_HEIGHT) continue;
                unit.draw();
                if (unit == controlled) draws++;
                if (unit.team == Vars.state.rules.waveTeam && reportedEnemies.add(unit.id)) {
                    System.out.println("UPSTREAM_SP_ENEMY type=" + unit.type.name + " id=" + unit.id
                        + " x=" + unit.x() + " y=" + unit.y());
                }
            }
            for (var bullet : Groups.bullet) {
                int x = World.toTile(bullet.x()), y = World.toTile(bullet.y());
                if (x < viewport.left || x >= viewport.left + UpstreamTerrainDrawProbe.VIEW_WIDTH
                        || y < viewport.bottom || y >= viewport.bottom + UpstreamTerrainDrawProbe.VIEW_HEIGHT) continue;
                bullet.draw();
            }
        } finally {
            Draw.sort(false);
        }
        UpstreamObjectiveMarkers.draw(Vars.state.rules, 240f / (UpstreamTerrainDrawProbe.VIEW_HEIGHT * Vars.tilesize),
            worldLabel);
        var orders = buildingControls.orders();
        orders.draw(viewport);
        drawObjectives(drawStatusHud());
        var block = buildingControls.block();
        var catalog = buildingControls.catalog();
        var config = buildingControls.buildingConfig();
        var inventory = buildingControls.inventory();
        int shortcut = block == buildingControls.shortcut(0) ? 0 : block == buildingControls.shortcut(1) ? 1 : -1;
        target.buildOverlay(buildingControls.cursorX() - viewport.left, buildingControls.cursorY() - viewport.bottom,
            shortcut, buildingControls.rotation(), buildingControls.removing(), block.rotate,
            catalog.isOpen() || config.isOpen() || inventory.isOpen(), buildingControls.configuring(),
            buildingControls.selectingMine(), buildingControls.inventoryAction(), orders.mode());
        if (catalog.isOpen()) {
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

    private void drawConstructBuilds() {
        Draw.sort(true);
        try {
            for (var build : Groups.build) {
                if (!(build instanceof ConstructBuild cb) || cb.current == Blocks.air) continue;
                if (!viewport.contains(build)) continue;
                Draw.z(Layer.blockBuilding);
                Draw.color(Pal.accent, Pal.remove, cb.constructColor);
                if (cb.current.fullIcon == null) cb.current.loadIcon();
                if (cb.current.fullIcon != null && cb.current.fullIcon.found()) {
                    Draw.rect(cb.current.fullIcon, cb.x, cb.y, cb.current.rotate ? cb.rotdeg() : 0f);
                }
                Draw.reset();
            }
        } finally {
            Draw.sort(false);
        }
    }

    private void drawWorldLabel(UpstreamLabelText.Label text, float x, float y, int flags, float size, int align, int lineAlign) {
        if (encodedLabel != text) {
            encodedCharacters = encodeLabel(text);
            encodedLabel = text;
        }
        target.drawWorldLabel(text.plain(), encodedCharacters, x, y, flags, size, align, lineAlign);
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
        if (!rows.equals(lastStatus)) {
            statusLabels = new byte[rows.size()][];
            statusCharacters = new int[rows.size()][];
            for (int i = 0; i < rows.size(); i++) {
                statusLabels[i] = (rows.get(i).text() + "\0").getBytes(StandardCharsets.UTF_8);
                statusCharacters[i] = encodeLabel(UpstreamLabelText.parse(UI.formatIcons(rows.get(i).markup())));
            }
            lastStatus = rows;
        }
        float y = 4f;
        for (int i = 0; i < rows.size() && y < 240f; i++) {
            y = target.drawObjective(statusLabels[i], statusCharacters[i], rows.get(i).icon(), y);
        }
        return y;
    }

    private void drawObjectives(float y) {
        var rows = UpstreamObjectiveHud.collect(Vars.state.rules);
        if (!rows.equals(lastObjectives)) {
            objectiveLabels = new byte[rows.size()][];
            objectiveCharacters = new int[rows.size()][];
            for (int i = 0; i < rows.size(); i++) {
                objectiveLabels[i] = (rows.get(i).text() + "\0").getBytes(StandardCharsets.UTF_8);
                objectiveCharacters[i] = encodeLabel(UpstreamLabelText.parse(UI.formatIcons(rows.get(i).markup())));
            }
            lastObjectives = rows;
        }
        for (int i = 0; i < rows.size() && y < 240f; i++) {
            var row = rows.get(i);
            y = target.drawObjective(objectiveLabels[i], objectiveCharacters[i], row.icon(), y);
        }
    }

    static boolean supportsBuildingDraw(Building build) {
        var block = build.block;
        return UpstreamBuildingLayers.supportsPowerDraw(block) || UpstreamBuildingLayers.supportsDistributionDraw(block)
            || block.category == Category.liquid || block instanceof UnitFactory
            || block instanceof StorageBlock || block instanceof Wall
            || block == Blocks.mechanicalDrill || block == Blocks.pneumaticDrill
            || block instanceof BaseTurret
            || block instanceof GenericCrafter || block instanceof Separator || block instanceof Pump
            || block == Blocks.overdriveProjector
            || block == Blocks.overdriveDome || block == Blocks.shockwaveTower;
    }

    private void drawMenu(byte[] labels, int[] icons, int selected,
                            boolean previous, boolean next, int catalog) {
        target.buildCatalog(labels, icons, selected, previous, next, catalog);
        lastMenu = labels;
    }
}
