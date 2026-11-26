import port.render.UpstreamCrackAtlas;
import port.render.UpstreamFluidAtlas;
import port.render.UpstreamLabelText;
import port.render.UpstreamWorldLabel;
import arc.Core;
import arc.Events;
import arc.Settings;
import arc.audio.Audio;
import arc.files.Fi;
import arc.graphics.Camera;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.mock.MockAudio;
import arc.struct.IntSet;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Time;
import arc.util.Strings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Planets;
import mindustry.content.TechTree.TechNode;
import mindustry.core.UI;
import mindustry.core.World;
import mindustry.entities.bullet.BulletType;
import mindustry.game.EventType.BlockBuildEndEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.EventType.WaveEvent;
import mindustry.game.Gamemode;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.maps.Map;
import mindustry.gen.BlockUnitc;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.gen.Payloadc;
import mindustry.gen.Tankc;
import mindustry.gen.Unit;
import mindustry.gen.UnitWaterMove;
import mindustry.type.ItemSeq;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.type.UnitType;
import mindustry.type.Category;
import mindustry.world.Block;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.defense.Wall;
import mindustry.world.blocks.defense.turrets.BaseTurret;
import mindustry.world.blocks.logic.CanvasBlock.CanvasBuild;
import mindustry.world.blocks.logic.LogicDisplay.LogicDisplayBuild;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.units.UnitFactory;

/** Graphical offline single-player Mindustry v160.8 ARM11 entry point on Ground Zero. */
public final class UpstreamSinglePlayerApp {
    private static final long FRAME_NS = 16_666_667L;
    private static final float DEAD_ZONE = 20f / 127f;
    private static final float STOP_SPEED = 0.03f;
    private final AudioHooks audio;
    private final UpstreamTerrainRenderProbe target = new UpstreamTerrainRenderProbe(false);
    private final UpstreamBuildingLayers buildingLayers = new UpstreamBuildingLayers();
    private final UpstreamBuildingPowerHud buildingPowerHud = new UpstreamBuildingPowerHud();
    private final UpstreamBuildingLiquidHud buildingLiquidHud = new UpstreamBuildingLiquidHud();
    private final UpstreamBuildingBasicBars buildingBasicBars = new UpstreamBuildingBasicBars();
    private UpstreamLiveBuildControls buildingControls = createBuildControls();
    private final UpstreamWeatherLayers weatherLayers = new UpstreamWeatherLayers();
    private final Runnable drawWeatherUnder = weatherLayers::drawUnder;
    private final Runnable drawEntities = this::drawEntities;
    private final UpstreamWorldLabel.Target worldLabel = this::drawWorldLabel;
    private final Vec2 moveInput = new Vec2();
    private final UpstreamOfflineWeaponInput weaponInput = new UpstreamOfflineWeaponInput();
    private Unit reportedControl;
    private final ObjectSet<UnitType> loadedUnitGraphics = new ObjectSet<>();
    private final ObjectSet<BulletType> loadedBulletGraphics = new ObjectSet<>();

    private UpstreamSinglePlayerSession session;
    private UpstreamTerrainDrawProbe.Viewport viewport;
    private boolean rendererReady;
    private boolean interfaceGraphicsReady;
    private Unit controlled;
    private Fi campaignSlot;
    private boolean pickerExitRequested;
    private boolean returnToTitleRequested;
    private int draws;
    private long previousVisualFrame;
    private float frameSeconds = 1f / 60f;
    private int lastLeft = -1, lastBottom = -1;
    private int lastCopper = -1;
    private boolean moving;
    private boolean reportedMinimap;
    private int minimapFrame;
    private final UpstreamMinimapView minimapView = new UpstreamMinimapView();
    private byte[] minimapViewTitle;
    private int[] minimapViewTitleCharacters;
    private byte[] minimapViewBack;
    private int[] minimapViewBackCharacters;
    private String inputDevice;
    private UpstreamLabelText.Label encodedLabel;
    private int[] encodedCharacters;
    private byte[] lastMenu;
    private Block lastPrimaryShortcut;
    private List<UpstreamObjectiveHud.Row> lastStatus;
    private int lastCoreItemCount = -1;
    private int lastCoreItemDrawn = -1;
    private final UpstreamCoreItems.Tracker coreItemTracker = new UpstreamCoreItems.Tracker();
    private final UpstreamPauseMenu pauseMenu = new UpstreamPauseMenu();
    private boolean pauseOpen;
    private int pauseAxis;
    private Fi sourceMap;
    private final UpstreamSettingsMenu settingsMenu = new UpstreamSettingsMenu();
    private boolean settingsOpen;
    private byte[][] statusLabels = new byte[0][];
    private int[][] statusCharacters = new int[0][];
    private List<UpstreamObjectiveHud.Row> lastObjectives;
    private byte[][] objectiveLabels = new byte[0][];
    private int[][] objectiveCharacters = new int[0][];
    private String lastBossText;
    private byte[] bossLabel = new byte[0];
    private int[] bossCharacters = new int[0];
    private String lastPositionText;
    private byte[] positionLabel = new byte[0];
    private int[] positionCharacters = new int[0];
    private final IntSet reportedEnemies = new IntSet();

    interface AudioHooks {
        default Audio open() { return new MockAudio(); }
        default void prepareWorld() {}
        default void leaveWorld() {}
        default void update(long now) {}
    }

    interface TitleJoin {
        boolean open(UpstreamWorldLoadProbe.ProbeApplication app, boolean atlasReady, Settings settings) throws IOException;
    }

    interface SessionHooks {
        default void open() throws IOException {}
        default void tick() {}
        default void close() {}
        default boolean canHost() { return false; }
        default boolean hosting() { return false; }
        default void toggleHosting() throws IOException {}
    }

    private final SessionHooks sessionHooks;

    private UpstreamSinglePlayerApp(AudioHooks audio, SessionHooks sessionHooks) {
        this.audio = audio;
        this.sessionHooks = sessionHooks;
    }

    public static void main(String[] args) throws IOException {
        run(args, new AudioHooks() {});
    }

    static void run(String[] args, AudioHooks audio) throws IOException {
        run(args, audio, null, new SessionHooks() {});
    }

    static void run(String[] args, AudioHooks audio, TitleJoin join, SessionHooks hooks) throws IOException {
        try {
            new UpstreamSinglePlayerApp(audio, hooks).run(args, join);
        } catch (IOException | RuntimeException failure) {
            System.out.println("UPSTREAM_SP_ERROR " + failure.getClass().getName() + ": " + failure.getMessage());
            throw failure;
        }
    }

    private void run(String[] args, TitleJoin join) throws IOException {
        int language = target.mountAssets();
        int bootInput = UpstreamSinglePlayerInput.poll();
        boolean customRequested = args.length == 0 && bootInput >= 0
            && (bootInput & UpstreamSinglePlayerInput.L_HELD) != 0;
        boolean editorRequested = customRequested && (bootInput & UpstreamSinglePlayerInput.CAMPAIGN_HELD) != 0;
        editorRequested |= args.length > 0 && args[0].equals("--editor");
        boolean databaseRequested = args.length > 0 && args[0].equals("--database");
        boolean titleRequested = args.length > 0 && args[0].equals("--title");
        boolean loadRequested = false;
        boolean campaignRequested = args.length == 0 && bootInput >= 0
            && (bootInput & UpstreamSinglePlayerInput.CAMPAIGN_HELD) != 0 && !customRequested;
        Fi mapFile = !editorRequested && !databaseRequested && !titleRequested && args.length >= 1
            ? new Fi(args[0]) : firstExisting("/romfs/maps/groundZero.msav", "/3ds/min3dstry/groundZero.msav");
        sourceMap = mapFile;
        Fi saveFile = !editorRequested && !databaseRequested && !titleRequested && args.length >= 2
            ? new Fi(args[1]) : new Fi("/3ds/min3dstry/singleplayer-save.msav");
        Fi atlas = firstExisting("/romfs/sprites.aatls", "/3ds/min3dstry/sprites.aatls");
        Fi mapDir = saveFile.parent() != null ? saveFile.parent() : new Fi("/3ds/min3dstry");
        var settings = UpstreamSinglePlayerSession.loadSettings(mapDir);
        Fi preference = new Fi("/3ds/min3dstry/locale.txt");
        String requested = requestedLocale(preference, settings);

        Core.bundle = UpstreamInterfaceLocale.loadForStockClient(new Fi("/romfs/bundles/bundle"), language, requested);
        UI.loadColors();
        var locale = Core.bundle.getLocale();
        String bundleTag = locale.getLanguage().isEmpty() ? "en" : locale.toString();
        System.out.println("UPSTREAM_INTERFACE_LOCALE system=" + language + " bundle=" + bundleTag
            + " router=" + Core.bundle.get("block.router.name") + " copper=" + Core.bundle.get("item.copper.name"));

        try {
            Fi customSave = mapDir.child("custom-game-save.msav");
            Fi defaultSave = saveFile;
            while (true) {
                saveFile = defaultSave;
                if (titleRequested) {
                    var selection = chooseTitleAction(atlas, saveFile, () -> prepareSession(mapDir, bundleTag, settings), join, settings);
                    if (selection == null) return;
                    campaignRequested = selection.action() == TitleAction.campaign;
                    customRequested = selection.action() == TitleAction.custom;
                    editorRequested = selection.action() == TitleAction.editor;
                    loadRequested = selection.action() == TitleAction.load;
                    if (loadRequested) {
                        saveFile = selection.save();
                        System.out.println("UPSTREAM_TITLE_LOAD_SELECTED file=" + saveFile.name());
                    }
                }
                prepareSession(mapDir, bundleTag, settings);
                if (databaseRequested) {
                    loadInterfaceGraphics(atlas);
                    new UpstreamCoreDatabaseMenu(target).run();
                    return;
                }
                if (editorRequested) {
                    var source = !titleRequested && args.length >= 2 ? new Fi(args[1]) : mapDir.child("editor-map.msav");
                    var output = !titleRequested && args.length >= 3 ? new Fi(args[2]) : null;
                    var editor = new UpstreamMapEditorApp(target, source, output, mapDir.child("maps"));
                    editor.open();
                    loadInterfaceGraphics(atlas);
                    editor.run(viewport);
                    return;
                }
                CustomGame custom = null;
                if (customRequested) {
                    var maps = UpstreamCustomGameMaps.discover(new Fi("/romfs/maps/default"), mapDir.child("maps"));
                    var saves = UpstreamCustomGameSaves.discover(Vars.saveDirectory, customSave);
                    if (!maps.isEmpty() || !saves.isEmpty()) {
                        loadInterfaceRenderer();
                        custom = chooseCustomGame(maps, saves);
                        if (custom == null) {
                            if (titleRequested && !pickerExitRequested) continue;
                            return;
                        }
                    } else if (titleRequested) {
                        if (chooseTitleRow(Core.bundle.get("maps.none"), "back") == -2) return;
                        continue;
                    }
                }
                List<UpstreamSinglePlayerSession.SectorSlot> sectorSaves =
                    args.length < 2 && custom == null && !loadRequested ? session.sectorSaves() : List.of();
                UpstreamCampaignResearch.installAutoUnlocks();
                boolean showCampaign = campaignRequested
                    || sectorSaves.stream().anyMatch(UpstreamSinglePlayerSession::needsCampaignRetry);
                if (showCampaign) sectorSaves = session.campaignSlots(sectorSaves);
                CampaignGame campaign = null;
                if (sectorSaves.size() == 1 && !showCampaign) {
                    var selected = sectorSaves.get(0);
                    System.out.println("UPSTREAM_CAMPAIGN_SELECTED slot=" + selected.file.name() + " source=single name=" + selected.sector.name());
                    campaign = new CampaignGame(selected, null);
                } else if (!sectorSaves.isEmpty()) {
                    campaign = chooseCampaign(sectorSaves, mapDir.child("campaign-select.txt"));
                    if (campaign == null) {
                        if (titleRequested && !pickerExitRequested) continue;
                        return;
                    }
                }
                try {
                    playWorld(atlas, loadRequested ? null : mapFile, saveFile, customSave, custom, campaign);
                } finally {
                    sessionHooks.close();
                }
                if (!titleRequested || !returnToTitleRequested) return;
                returnToTitleRequested = false;
                campaignSlot = null;
                returnToTitle();
            }
        } finally {
            if (campaignSlot != null && !Vars.state.gameOver) {
                try {
                    session.save(campaignSlot);
                    System.out.println("UPSTREAM_CAMPAIGN_SAVED slot=" + campaignSlot.name());
                } catch (Throwable failure) {
                    System.out.println("UPSTREAM_CAMPAIGN_SAVE_ERROR "
                        + failure.getClass().getName() + ": " + failure.getMessage());
                }
            }
            if (session != null) session.shutdown();
            System.out.println("UPSTREAM_SP_EXIT");
        }
    }

    private void playWorld(Fi atlas, Fi mapFile, Fi saveFile, Fi customSave, CustomGame custom,
                           CampaignGame campaign) throws IOException {
        // Load the initial world before initializing world sprites to keep peak TeaVM heap bounded.
        if (campaign != null) {
            saveFile = openCampaign(campaign);
        } else if (custom == null) {
            session.start(mapFile, saveFile, null);
        } else {
            if (custom.map == null) {
                saveFile = custom.save;
                System.out.println("UPSTREAM_CUSTOM_GAME_SELECTED source=save file=" + saveFile.name());
                session.start(null, saveFile, "customgame-save");
            } else {
                saveFile = customSave;
                System.out.println("UPSTREAM_CUSTOM_GAME_SELECTED map=" + custom.map.file.name()
                    + " mode=" + custom.mode.name());
                Gamemode mode = session.startCustomGame(custom.map, custom.mode);
                System.out.println("UPSTREAM_CUSTOM_GAME_STARTED map=" + custom.map.file.name() + " mode=" + mode.name());
            }
        }
        loadInterfaceGraphics(atlas);
        weatherLayers.load();
        reportWeather();
        uploadMinimap();
        session.ensurePlayerSpawn();
        sessionHooks.open();
        campaignLoop:
        while (runMainLoop(saveFile)) {
            var origin = Vars.state.rules.sector;
            boolean defeated = Vars.state.gameOver;
            if (defeated) {
                session.saveCampaignDefeat(campaignSlot);
            } else {
                session.save(campaignSlot);
            }
            CampaignGame next;
            do {
                if (defeated && !showCampaignDefeat(origin)) break campaignLoop;
                System.out.println("UPSTREAM_CAMPAIGN_WORLD_MENU sector=" + origin.id);
                next = chooseCampaign(session.campaignSlots(session.sectorSaves(), origin), null);
                if (pickerExitRequested) break campaignLoop;
            } while (defeated && next == null);
            // Opening the selector replaces the bottom-screen labels even if the player returns to this sector.
            lastPrimaryShortcut = null;
            previousVisualFrame = 0;
            if (next == null || !defeated && next.slot().sector == origin) continue;
            saveFile = openCampaign(next);
            resetWorldInput();
            weatherLayers.reset();
            reportWeather();
        }
    }

    private void returnToTitle() {
        Vars.player.clearUnit();
        resetWorldInput();
        pauseAxis = 0;
        previousVisualFrame = 0;
        weatherLayers.reset();
        UpstreamNotification.clear();
        audio.leaveWorld();
        Vars.logic.reset();
        session.application().runPosted();
        System.out.println("UPSTREAM_TITLE_RETURN");
    }

    private void prepareSession(Fi mapDir, String bundleTag, Settings settings) {
        if (session != null) return;
        session = UpstreamSinglePlayerSession.create(mapDir, bundleTag, new Fi("/romfs/planets"), settings,
            new Fi("/romfs/maps"), audio.open());
        Core.camera = new Camera();
        audio.prepareWorld();
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
        UpstreamSectorCapture.install();
        Events.on(WaveEvent.class, event -> {
            String warning = UpstreamGuardianWarning.find(Vars.state.rules, Vars.state.wave);
            if (warning != null) UpstreamNotification.show(warning, 5f);
        });
        Events.run(Trigger.teamCoreDamage, () ->
            UpstreamNotification.show(Core.bundle.get("coreattack"), 4f));
    }

    static String requestedLocale(Fi preference, Settings settings) {
        if (preference.exists() && !preference.isDirectory() && preference.length() <= 16) {
            String override = preference.readString("UTF-8").trim();
            if (!override.isEmpty()) return override;
        }
        return settings.get("locale", "") instanceof String locale ? locale : "";
    }

    private static Fi firstExisting(String primary, String fallback) {
        var first = new Fi(primary);
        return first.exists() ? first : new Fi(fallback);
    }

    private record CustomGame(Map map, Gamemode mode, Fi save) {}

    private record CampaignGame(UpstreamSinglePlayerSession.SectorSlot slot, UpstreamCampaignLoadout loadout) {}

    private CampaignGame chooseCampaign(List<UpstreamSinglePlayerSession.SectorSlot> slots, Fi automatic) {
        if (slots.isEmpty()) return null;
        loadInterfaceRenderer();
        while (!pickerExitRequested) {
            var selected = chooseSector(slots, automatic);
            if (selected == null) return null;
            if (selected.origin == null || selected.origin == selected.sector) return new CampaignGame(selected, null);
            var loadout = confirmLaunch(selected);
            if (loadout != null) return new CampaignGame(selected, loadout);
            automatic = null;
        }
        return null;
    }

    private Fi openCampaign(CampaignGame choice) throws IOException {
        var slot = choice.slot();
        // A failed load must never save the partially loaded world over the origin.
        campaignSlot = null;
        if (slot.origin != null) {
            drawMenuRows(encodeMenuRows(new String[]{
                Strings.stripColors(Core.bundle.get("loading")), slot.sector.name()
            }));
            System.out.println("UPSTREAM_CAMPAIGN_GENERATING planet=" + slot.sector.planet.name
                + " sector=" + slot.sector.id);
            long started = System.nanoTime();
            if (slot.timestamp < 0) {
                session.launchNewSector(slot.origin, slot.sector, slot.file, choice.loadout());
            } else {
                session.retryCampaignSector(slot, choice.loadout());
            }
            System.out.println("UPSTREAM_CAMPAIGN_GENERATION_DONE planet=" + slot.sector.planet.name
                + " sector=" + slot.sector.id + " elapsedMs=" + (System.nanoTime() - started) / 1_000_000);
        } else if (!session.start(null, slot.file, "campaign", slot.sector)) {
            throw new IOException("Unable to load campaign slot " + slot.file.name());
        }
        campaignSlot = slot.file;
        return campaignSlot;
    }

    private static UpstreamLiveBuildControls createBuildControls() {
        var controls = new UpstreamLiveBuildControls();
        controls.schematics().nameCapturesWith(UpstreamTerrainRenderProbe::readSchematicName,
            UpstreamTerrainRenderProbe::confirmSchematicReplacement);
        controls.schematics().editWith(UpstreamTerrainRenderProbe::editSchematicName,
            UpstreamTerrainRenderProbe::editSchematicDescription);
        return controls;
    }

    private void resetWorldInput() {
        buildingControls = createBuildControls();
        controlled = null;
        reportedControl = null;
        moving = false;
        inputDevice = null;
        minimapView.reset();
        lastLeft = lastBottom = -1;
        lastMenu = null;
        lastStatus = null;
        lastObjectives = null;
        lastBossText = null;
        lastPositionText = null;
        lastCoreItemCount = -1;
        lastCoreItemDrawn = -1;
        coreItemTracker.reset();
        reportedEnemies.clear();
        loadedUnitGraphics.clear();
        loadedBulletGraphics.clear();
    }

    private void reportWeather() {
        for (var state : Groups.weather) {
            System.out.println("UPSTREAM_SP_WEATHER name=" + state.weather().name);
        }
    }

    private record MenuRows(byte[][] labels, int[][] characters) {}

    private CustomGame chooseCustomGame(List<Map> maps, List<UpstreamCustomGameSaves.Slot> saves) {
        Gamemode[] modes = {Gamemode.survival, Gamemode.sandbox, Gamemode.attack, Gamemode.pvp};
        int firstMap = saves.size();
        int count = firstMap + maps.size();
        int selected = 0, mode = 0, lastX = 0, lastY = 0, renderedRow = -1, renderedMode = -1;
        MenuRows rows = null;
        target.buildLabels(new byte[UpstreamTerrainRenderProbe.PALETTE_LABEL_COUNT]);
        System.out.println("UPSTREAM_CUSTOM_GAME_MENU maps=" + maps.size() + " saves=" + firstMap);
        // Consume the launcher's button edges before accepting menu input.
        UpstreamSinglePlayerInput.poll();
        while (true) {
            long nextFrame = System.nanoTime() + FRAME_NS;
            int input = UpstreamSinglePlayerInput.poll();
            if (input < 0 || (input & UpstreamSinglePlayerInput.B_PRESSED) != 0) {
                if (input < 0) pickerExitRequested = true;
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
                return selected < firstMap ? new CustomGame(null, modes[mode], saves.get(selected).file())
                    : new CustomGame(maps.get(selected - firstMap), modes[mode], null);
            }
            if (selected != renderedRow || mode != renderedMode) {
                rows = encodeMenuRows(customGameCaptions(maps, saves, selected, modes[mode]));
                renderedRow = selected;
                renderedMode = mode;
            }
            drawMenuRows(rows);
            while (System.nanoTime() < nextFrame) Thread.yield();
        }
    }

    static String[] customGameCaptions(List<Map> maps, List<UpstreamCustomGameSaves.Slot> saves,
                                      int selected, Gamemode mode) {
        int firstMap = saves.size();
        int first = (selected / 6) * 6;
        int end = Math.min(first + 6, firstMap + maps.size());
        String[] captions = new String[1 + end - first];
        String title = Core.bundle.get("customgame");
        captions[0] = selected < firstMap ? title : title + " / " + mode;
        for (int i = first; i < end; i++) {
            String label = i < firstMap ? saves.get(i).title() : maps.get(i - firstMap).name();
            label = Strings.stripColors(label);
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

    static int[] encodePlainMenuLabel(String caption) {
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
        drawMenuRows(rows, null);
    }

    private void drawMenuRows(MenuRows rows, byte[] titleControls) {
        drawMenuRows(rows, titleControls, 2);
    }

    private void drawMenuRows(MenuRows rows, byte[] titleControls, int controlRows) {
        target.begin(0, 0, UpstreamTerrainDrawProbe.VIEW_WIDTH, UpstreamTerrainDrawProbe.VIEW_HEIGHT);
        try {
            float rowY = 6f;
            for (int i = 0; i < rows.labels.length; i++) {
                rowY = target.drawObjective(rows.labels[i], rows.characters[i], null, rowY);
            }
            if (titleControls != null) target.drawMenuHelp(titleControls, controlRows);
        } finally {
            target.end();
        }
    }

    private enum TitleAction { campaign, custom, load, editor }

    private record TitleSelection(TitleAction action, Fi save) {}

    private TitleSelection chooseTitleAction(Fi atlas, Fi defaultSave, Runnable prepare, TitleJoin join, Settings settings) throws IOException {
        loadInterfaceRenderer();
        while (true) {
            switch (chooseTitleRow("Mindustry", "play", "database.button", "editor", "settings", "quit")) {
                case 0 -> {
                    var playKeys = join == null ? new String[]{"campaign", "customgame", "loadgame"}
                        : new String[]{"campaign", "joingame", "customgame", "loadgame"};
                    int route = chooseTitleRow(Core.bundle.get("play"), playKeys);
                    if (route == 0) return new TitleSelection(TitleAction.campaign, null);
                    if (route == 1 && join != null) {
                        if (join.open(session == null ? null : session.application(), interfaceGraphicsReady, settings)) return null;
                        continue;
                    }
                    if (route > 0 && join != null) route--;
                    if (route == 1) return new TitleSelection(TitleAction.custom, null);
                    if (route == 2) {
                        prepare.run();
                        var customSave = defaultSave.parent().child("custom-game-save.msav");
                        var saves = UpstreamCustomGameSaves.discoverForTitle(Vars.saveDirectory, defaultSave, customSave);
                        if (saves.isEmpty()) {
                            if (chooseTitleRow(Core.bundle.get("save.none"), "back") == -2) return null;
                        } else {
                            int selected = chooseTitlePagedRow(Core.bundle.get("loadgame"), saves.size(), 0,
                                index -> saves.get(index).title());
                            if (selected == -2) return null;
                            if (selected >= 0) return new TitleSelection(TitleAction.load, saves.get(selected).file());
                        }
                    }
                    if (route == -2) return null;
                }
                case 1 -> {
                    int route = chooseTitleRow(Core.bundle.get("database.button"), "database", "about.button", "schematics");
                    if (route == -2) return null;
                    if (route == 0) {
                        prepare.run();
                        loadInterfaceGraphics(atlas);
                        if (!new UpstreamCoreDatabaseMenu(target).run()) return null;
                    } else if (route == 1 && !chooseTitleAbout()) {
                        return null;
                    } else if (route == 2) {
                        prepare.run();
                        if (!chooseTitleSchematics(new UpstreamSchematicPlacement(Vars.schematicDirectory))) return null;
                    }
                }
                case 2 -> { return new TitleSelection(TitleAction.editor, null); }
                case 3 -> {
                    if (!chooseTitleSettings(settings)) return null;
                }
                default -> { return null; }
            }
        }
    }

    private boolean chooseTitleSchematics(UpstreamSchematicPlacement library) {
        Fi[] files = library.libraryFiles();
        System.out.println("UPSTREAM_TITLE_SCHEMATICS files=" + files.length);
        return browseTitlePages(Core.bundle.get("schematics"), files.length, index -> {
            String name = UpstreamSchematicPlacement.readDisplayName(files[index]);
            return name != null ? name : files[index].nameWithoutExtension();
        }, index -> showTitleSchematic(library, files[index]));
    }

    private boolean showTitleSchematic(UpstreamSchematicPlacement library, Fi file) {
        try {
            var schematic = UpstreamSchematicPlacement.readSchematic(file);
            System.out.println("UPSTREAM_TITLE_SCHEMATIC file=" + file.name() + " blocks=" + schematic.tiles.size);
            var details = Core.bundle.format("schematic.info", schematic.width, schematic.height, schematic.tiles.size);
            int action = chooseTitleRow(schematic.name(), 0,
                new String[]{Core.bundle.get("schematic.edit"), Core.bundle.get("back")},
                details, schematic.description());
            if (action == 0) {
                library.editMetadata(file, UpstreamTerrainRenderProbe::editSchematicName,
                    UpstreamTerrainRenderProbe::editSchematicDescription);
            }
            return action != -2;
        } catch (IOException | RuntimeException failure) {
            System.out.println("UPSTREAM_TITLE_SCHEMATIC_ERROR file=" + file.name());
            return chooseTitleRow(Core.bundle.get("error"), "back") != -2;
        }
    }

    private boolean chooseTitleAbout() {
        var links = mindustry.ui.Links.getLinks();
        System.out.println("UPSTREAM_TITLE_ABOUT links=" + links.length);
        return browseTitlePages(Core.bundle.get("about.button"), links.length + 1,
            index -> index == 0 ? Core.bundle.get("credits") : links[index - 1].title,
            index -> {
                if (index == 0) return showTitleCredits();
                var link = links[index - 1];
                return chooseTitleRow(link.title, 0, new String[]{Core.bundle.get("back")},
                    link.description, link.link) != -2;
            });
    }

    private boolean browseTitlePages(String title, int count, java.util.function.IntFunction<String> option,
                                     java.util.function.IntPredicate choose) {
        if (count == 0) return chooseTitleRow(title, "back") != -2;
        int selected = 0;
        while (true) {
            selected = chooseTitlePagedRow(title, count, selected, option);
            if (selected < 0) return selected == -1;
            if (!choose.test(selected)) return false;
        }
    }

    private int chooseTitlePagedRow(String title, int count, int selected, java.util.function.IntFunction<String> option) {
        final int pageSize = 7;
        while (true) {
            int first = selected / pageSize * pageSize;
            int end = Math.min(first + pageSize, count);
            var options = new String[end - first + (first > 0 ? 1 : 0) + (end < count ? 1 : 0)];
            int offset = first > 0 ? 1 : 0;
            if (offset == 1) options[0] = "<";
            for (int index = first; index < end; index++) {
                options[offset + index - first] = option.apply(index);
            }
            if (end < count) options[options.length - 1] = ">";
            int row = chooseTitleRow(title, offset + selected - first, options);
            if (row < 0) return row;
            if (row < offset) {
                selected = first - 1;
            } else if (first + row - offset >= end) {
                selected = end;
            } else {
                return first + row - offset;
            }
        }
    }

    private boolean showTitleCredits() {
        var credits = new Fi("/romfs/contributors").readString("UTF-8").split("\n");
        System.out.println("UPSTREAM_TITLE_CREDITS contributors=" + credits.length);
        final int pageSize = 7;
        for (int start = 0; ; ) {
            int end = Math.min(start + pageSize, credits.length);
            boolean previous = start > 0, next = end < credits.length;
            var names = new String[2 + end - start];
            names[0] = Strings.stripColors(Core.bundle.get("credits.text"));
            names[1] = Core.bundle.get("contributors");
            for (int i = start; i < end; i++) names[2 + i - start] = credits[i].strip();
            var actions = new String[(previous ? 1 : 0) + (next ? 1 : 0) + 1];
            int back = actions.length - 1;
            if (previous) actions[0] = "<";
            if (next) actions[back - 1] = ">";
            actions[back] = Core.bundle.get("back");
            int row = chooseTitleRow(Core.bundle.get("credits"), Math.max(0, back - 1), actions, names);
            if (row < 0 || row == back) return row != -2;
            start += previous && row == 0 ? -pageSize : pageSize;
        }
    }

    private boolean chooseTitleSettings(Settings settings) {
        openHudSettings(settings);
        System.out.println("UPSTREAM_TITLE_SETTINGS coreitems=" + settingsMenu.coreItems());
        while (true) {
            int row = chooseTitleRow(Core.bundle.get("settings"), settingsMenu.selected(), hudSettingsRows());
            if (row < 0) return row == -1;
            settingsMenu.move(row - settingsMenu.selected());
            if (settingsMenu.backSelected()) return true;
            if (settingsMenu.soundSelected()) {
                if (!chooseSoundSettings(settings)) return false;
            } else {
                toggleHudSetting(settings);
                settings.manualSave();
            }
        }
    }

    private int chooseTitleRow(String title, String... optionKeys) {
        var captions = new String[optionKeys.length];
        for (int i = 0; i < optionKeys.length; i++) captions[i] = Core.bundle.get(optionKeys[i]);
        return chooseTitleRow(title, 0, captions);
    }

    private int chooseTitleRow(String title, int selected, String[] options, String... headings) {
        int previousY = 0, drawn = -1;
        MenuRows rows = null;
        byte[] controls = ("A  " + Core.bundle.get("ok") + "\0B  " + Core.bundle.get("back") + "\0")
            .getBytes(StandardCharsets.UTF_8);
        UpstreamSinglePlayerInput.poll();
        while (true) {
            long nextFrame = System.nanoTime() + FRAME_NS;
            int input = UpstreamSinglePlayerInput.poll();
            if (input < 0) return -2;
            int touch = UpstreamTerrainRenderProbe.menuHelpAction(UpstreamSinglePlayerInput.touch(), 2);
            if ((input & UpstreamSinglePlayerInput.B_PRESSED) != 0 || touch == 1) return -1;
            int y = (byte)(input >> 8);
            if (y > 60 && previousY <= 20) selected = Math.floorMod(selected - 1, options.length);
            if (y < -60 && previousY >= -20) selected = (selected + 1) % options.length;
            previousY = y;
            if ((input & UpstreamSinglePlayerInput.A_PRESSED) != 0 || touch == 0) return selected;
            if (drawn != selected) {
                var captions = new String[options.length + headings.length + 1];
                captions[0] = title;
                System.arraycopy(headings, 0, captions, 1, headings.length);
                for (int i = 0; i < options.length; i++) {
                    captions[i + headings.length + 1] = (selected == i ? "> " : "  ") + options[i];
                }
                rows = encodeMenuRows(captions);
                drawn = selected;
            }
            drawMenuRows(rows, controls);
            while (System.nanoTime() < nextFrame) Thread.yield();
        }
    }

    private void loadInterfaceGraphics(Fi atlas) {
        if (interfaceGraphicsReady) return;
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS atlas");
        UpstreamTerrainDrawProbe.loadAtlas(atlas, UpstreamFluidAtlas::load);
        UpstreamLogicGraphics.load(new Fi("/romfs"));
        Vars.content.loadColors(new Fi("/romfs/block_colors.png"));
        UpstreamCrackAtlas.load();
        viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        loadInterfaceRenderer();
        interfaceGraphicsReady = true;
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS ready");
    }

    private void loadInterfaceRenderer() {
        if (rendererReady) return;
        target.initialize();
        target.buildLabels(new byte[8]);
        var locale = Core.bundle.getLocale();
        boolean stock = target.loadStockFont(locale.getLanguage().equals("ja"));
        rendererReady = true;
        System.out.println("UPSTREAM_INTERFACE_FONT source=" + (stock ? "v160.8" : "system"));
    }

    private UpstreamSinglePlayerSession.SectorSlot chooseSector(
            List<UpstreamSinglePlayerSession.SectorSlot> slots, Fi automatic) {
        int selected = UpstreamSinglePlayerSession.lastSectorRow(slots);
        final boolean globeAvailable = target.campaignPlanetAvailable();
        int lastAxis = 0;
        System.out.println("UPSTREAM_CAMPAIGN_MENU slots=" + slots.size() + " selected=" + slots.get(selected).file.name());
        if (automatic != null && automatic.exists() && !automatic.isDirectory() && automatic.length() <= 16) {
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
        if (pollCampaignInput() == -1) return null;
        while (true) {
            long nextFrame = System.nanoTime() + FRAME_NS;
            int input = pollCampaignInput();
            if (input == -1 || (input & (1 << 19)) != 0) {
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
                var captions = campaignCaptions(slots, selected);
                if (globeAvailable) {
                    captions = Arrays.copyOf(captions, captions.length + 1);
                    captions[captions.length - 1] = "X " + Core.bundle.get("planet", "Planet Map");
                }
                rows = encodeMenuRows(captions);
                renderedRow = selected;
            }
            if (globeAvailable && (input & UpstreamSinglePlayerInput.X_PRESSED) != 0) {
                var slot = chooseSectorOnGlobe(slots, slots.get(selected).sector);
                if (slot != null || pickerExitRequested) return slot;
            }
            drawMenuRows(rows);
            while (System.nanoTime() < nextFrame) Thread.yield();
        }
    }

    private UpstreamSinglePlayerSession.SectorSlot chooseSectorOnGlobe(
            List<UpstreamSinglePlayerSession.SectorSlot> slots, Sector initial) {
        Vars.universe.updateGlobal();
        if (initial.planet != Planets.erekir && initial.planet != Planets.serpulo) {
            initial = Planets.serpulo.getStartSector();
        }
        var globe = new UpstreamCampaignGlobe(initial);
        var overlay = new UpstreamCampaignSectorOverlay();
        var grid = new UpstreamCampaignGrid();
        var arcs = new UpstreamCampaignArcs();
        var markers = new UpstreamCampaignMarkers();
        UpstreamCampaignSurface surface = null;
        boolean checkedSurface = false;
        boolean liveSurface = false;
        long openedAt = System.nanoTime();
        float cursorX = 200, cursorY = 120;
        MenuRows rows = null;
        Sector previous = null;
        Sector previousSelection = null;
        UpstreamSinglePlayerSession.SectorSlot preview = null;
        Planet previousPlanet = null;
        boolean gridLogged = false, arcsLogged = false;
        int stableFrames = 0;

        try {
            while (true) {
                long nextFrame = System.nanoTime() + FRAME_NS;
                int input = pollCampaignInput();
                if (input == -1) return null;
                if ((input & UpstreamSinglePlayerInput.B_PRESSED) != 0) {
                    if (preview == null) return null;
                    preview = null;
                }
                if ((input & UpstreamSinglePlayerInput.WAVE_PRESSED) != 0) {
                    preview = null;
                    globe.switchPlanet();
                    cursorX = 200;
                    cursorY = 120;
                }
                if ((input & (1 << 26)) != 0) globe.zoom(0.9f);
                if ((input & (1 << 27)) != 0) globe.zoom(1 / 0.9f);
                globe.rotate((byte)input, (byte)(input >> 8));
                int touch = UpstreamSinglePlayerInput.touch();
                if (touch != -1) {
                    cursorX = (touch & 0xffff) * 400f / 320f;
                    cursorY = touch >>> 16;
                }

                Sector sector = globe.update(cursorX, cursorY);
                UpstreamSinglePlayerSession.SectorSlot hovered = null;
                for (var slot : slots) {
                    if (slot.sector == sector) {
                        hovered = slot;
                        break;
                    }
                }
                if ((input & UpstreamSinglePlayerInput.A_PRESSED) != 0) {
                    if (preview != null && (hovered == null || hovered == preview)) {
                        System.out.println("UPSTREAM_CAMPAIGN_SELECTED slot=" + preview.file.name()
                            + " name=" + preview.sector.name() + " source=planet-map");
                        return preview;
                    }
                    if (hovered != null) {
                        preview = hovered;
                        System.out.println("UPSTREAM_CAMPAIGN_GLOBE_PREVIEW planet=" + preview.sector.planet.name
                            + " sector=" + preview.sector.id + " resume=" + (preview.origin == null));
                    }
                }
                Sector selectedSector = preview == null ? null : preview.sector;
                boolean planetChanged = previousPlanet != globe.planet();
                if (planetChanged) gridLogged = arcsLogged = false;
                if (rows == null || previous != sector || previousSelection != selectedSector || planetChanged) {
                    stableFrames = 0;
                    Sector labelSector = selectedSector == null ? sector : selectedSector;
                    String label = labelSector == null ? "" : " / " + Strings.stripColors(labelSector.name());
                    String action = preview != null
                        ? (hovered != null && hovered != preview ? "sectors.select"
                            : preview.origin == null ? "sectors.resume" : "sectors.launch")
                        : hovered == null ? null : "sectors.select";
                    rows = encodeMenuRows(new String[]{
                        globe.planet().localizedName + label,
                        "Y " + Core.bundle.get("planet", "Planet") + "   B "
                            + Core.bundle.get(preview == null ? "back" : "cancel"),
                        action == null ? "" : "A " + Core.bundle.get(action),
                    });
                    previous = sector;
                    previousSelection = selectedSector;
                    previousPlanet = globe.planet();
                    if (sector != null) {
                        System.out.println("UPSTREAM_CAMPAIGN_GLOBE planet=" + globe.planet().name
                            + " sector=" + sector.id + " available=" + (hovered != null));
                    }
                }
                float ticks = (System.nanoTime() - openedAt) * (60f / 1_000_000_000f);
                Sector active = Vars.state.getSector();
                if (globe.planet() == Planets.serpulo) {
                    if (!checkedSurface) {
                        checkedSurface = true;
                        try {
                            surface = UpstreamCampaignSurface.open(Planets.serpulo,
                                new Fi("/romfs/planet-meshes/serpulo.m3ps"));
                            if (surface != null) Vars.content.loadColors(new Fi("/romfs/block_colors.png"));
                        } catch (IOException | RuntimeException failure) {
                            System.out.println("UPSTREAM_CAMPAIGN_SURFACE_ERROR " + failure.getMessage());
                        }
                    }
                    if (surface != null) surface.update(192);
                }
                int[] surfaceFaces = UpstreamCampaignSurface.packedFaces(
                    globe.planet() == Planets.serpulo ? surface : null);
                boolean updatedSurface = surfaceFaces.length != 0;
                if (liveSurface != updatedSurface) {
                    liveSurface = updatedSurface;
                    stableFrames = 0;
                }
                overlay.update(globe.planet(), active, sector, selectedSector, ticks);
                int sectorVertices = overlay.vertices.size / UpstreamCampaignSectorOverlay.FLOATS_PER_VERTEX;
                int gridEdges = grid.append(globe.planet(), globe.projection, globe.gridCursor, overlay.vertices);
                int gridVertices = overlay.vertices.size / UpstreamCampaignSectorOverlay.FLOATS_PER_VERTEX - sectorVertices;
                int routeCount = arcs.append(globe.planet(), active, sector, selectedSector,
                    ticks, globe.projection, overlay.vertices);
                int overlayVertices = overlay.vertices.size / UpstreamCampaignSectorOverlay.FLOATS_PER_VERTEX;
                markers.update(globe, sector, ticks);
                // The 20x12 framing gives the globe the full 400x240 top screen.
                target.begin(0, 0, 20, 12);
                try {
                    if (!target.drawCampaignPlanet(globe.meshIndex(), globe.projection.val, globe.lighting,
                            overlay.vertices.items, overlayVertices, sectorVertices, surfaceFaces,
                            markers.ids.items, markers.planes.items, globe.camera.combined.val, markers.ids.size / 2)) {
                        System.out.println("UPSTREAM_CAMPAIGN_GLOBE_ERROR planet=" + globe.planet().name);
                        return null;
                    }
                    if (planetChanged) {
                        System.out.println("UPSTREAM_CAMPAIGN_SECTOR_OVERLAY planet=" + globe.planet().name
                            + " vertices=" + sectorVertices + " shadowed=" + overlay.shadowed
                            + " outlined=" + overlay.outlined + " marked=" + overlay.marked);
                    }
                    if (!gridLogged && gridEdges > 0) {
                        gridLogged = true;
                        System.out.println("UPSTREAM_CAMPAIGN_SECTOR_GRID planet=" + globe.planet().name
                            + " edges=" + gridEdges + " vertices=" + gridVertices);
                    }
                    if (!arcsLogged && routeCount > 0) {
                        arcsLogged = true;
                        System.out.println("UPSTREAM_CAMPAIGN_SECTOR_ARCS planet=" + globe.planet().name
                            + " routes=" + routeCount);
                    }
                    target.hudPanel(cursorX - 4, cursorY, 9, 1, -1);
                    target.hudPanel(cursorX, cursorY - 4, 1, 9, -1);
                    target.drawObjective(rows.labels[0], rows.characters[0], null, 6);
                    target.drawObjective(rows.labels[1], rows.characters[1], null, 210);
                    if (rows.characters[2].length > 0) target.drawObjective(rows.labels[2], rows.characters[2], null, 192);
                } finally {
                    target.end();
                }
                if (stableFrames < 3 && ++stableFrames == 3) {
                    // FrameBegin has synchronized earlier draws in this same selection state.
                    System.out.println("UPSTREAM_CAMPAIGN_GLOBE_FRAME planet=" + globe.planet().name
                        + " sector=" + (sector == null ? -1 : sector.id)
                        + " selection=" + (selectedSector == null ? -1 : selectedSector.id) + " surface=" + liveSurface);
                }
                while (System.nanoTime() < nextFrame) Thread.yield();
            }
        } finally {
            target.releaseCampaignPlanet();
        }
    }

    private UpstreamCampaignLoadout confirmLaunch(UpstreamSinglePlayerSession.SectorSlot selected) {
        var choices = UpstreamCampaignLoadout.choices(selected.origin, selected.sector);
        int schematic = choices.selected();
        boolean maximumCargo = Core.settings.getBool("maxresources", true);
        var requestedCargo = Vars.universe.getLaunchResources().copy();
        var deployment = UpstreamCampaignLoadout.prepare(selected.origin, selected.sector, choices, schematic, maximumCargo, requestedCargo);
        System.out.println("UPSTREAM_CAMPAIGN_LOADOUT sector=" + selected.sector.id
            + " origin=" + selected.origin.id + " affordable=" + deployment.affordable());
        var cost = deployment.cost().toArray();
        int page = 0;
        int lastAxis = 0;
        int lastHorizontal = 0;
        int rendered = -1;
        MenuRows rows = null;
        if (pollCampaignInput() == -1) return null;
        while (true) {
            long nextFrame = System.nanoTime() + FRAME_NS;
            int input = pollCampaignInput();
            if (input == -1 || (input & (1 << 19)) != 0) return null;
            int horizontal = (byte) (input & 0xff);
            int previous = schematic;
            if (horizontal > 60 && lastHorizontal <= 20 && schematic + 1 < choices.options().size) schematic++;
            if (horizontal < -60 && lastHorizontal >= -20 && schematic > 0) schematic--;
            lastHorizontal = horizontal;
            boolean toggleCargo = selected.sector.allowLaunchLoadout() && (input & UpstreamSinglePlayerInput.X_PRESSED) != 0;
            if (toggleCargo) maximumCargo = !maximumCargo;
            boolean edit = selected.sector.allowLaunchLoadout() && !maximumCargo
                && (input & UpstreamSinglePlayerInput.WAVE_PRESSED) != 0;
            if (edit && !editLaunchCargo(selected.origin.planet, requestedCargo,
                    UpstreamCampaignLoadout.cargoCapacity(selected.origin, choices.options().get(schematic)))) return null;
            if (schematic != previous || toggleCargo || edit) {
                deployment = UpstreamCampaignLoadout.prepare(selected.origin, selected.sector, choices, schematic, maximumCargo, requestedCargo);
                cost = deployment.cost().toArray();
                page = 0;
                rendered = -1;
            }
            if ((input & (1 << 17)) != 0 && deployment.affordable()) return deployment;
            int axis = (byte) ((input >> 8) & 0xff);
            if (axis > 60 && lastAxis <= 20 && page > 0) page--;
            if (axis < -60 && lastAxis >= -20 && (page + 1) * 4 < cost.length) page++;
            lastAxis = axis;
            if (rendered != page) {
                int count = Math.min(4, cost.length - page * 4);
                String[] captions = new String[count + 3];
                captions[0] = selected.sector.name();
                captions[1] = Strings.stripColors(Core.bundle.format("launch.from", selected.origin.name()))
                    + " / " + Strings.stripColors(deployment.schematic().name());
                for (int i = 0; i < count; i++) {
                    var stack = cost[page * 4 + i];
                    captions[i + 2] = stack.item.localizedName + "  " + stack.amount;
                }
                captions[count + 2] = Strings.stripColors(Core.bundle.get(deployment.affordable() ? "launch.text" : "sector.missingresources"));
                if (selected.sector.allowLaunchLoadout() && maximumCargo) captions[count + 2] += " / " + Core.bundle.get("resources.max");
                rows = encodeMenuRows(captions);
                rendered = page;
            }
            drawMenuRows(rows);
            while (System.nanoTime() < nextFrame) Thread.yield();
        }
    }

    private boolean editLaunchCargo(Planet planet, ItemSeq requested, int capacity) {
        var items = Vars.content.items().select(item -> item.isOnPlanet(planet)
            && (requested.get(item) > 0 || item.unlocked() && !item.isHidden()));
        if (items.isEmpty()) return true;
        requested.min(capacity);
        int selected = 0, lastVertical = 0, lastHorizontal = 0;
        MenuRows rows = null;
        while (true) {
            long nextFrame = System.nanoTime() + FRAME_NS;
            int input = pollCampaignInput();
            if (input == -1) return false;
            if ((input & ((1 << 17) | (1 << 19))) != 0) return true;
            int vertical = (byte) ((input >> 8) & 0xff);
            int horizontal = (byte) (input & 0xff);
            int previous = selected;
            if (vertical > 60 && lastVertical <= 20 && selected > 0) selected--;
            if (vertical < -60 && lastVertical >= -20 && selected + 1 < items.size) selected++;
            int direction = horizontal > 60 && lastHorizontal <= 20 ? 1
                : horizontal < -60 && lastHorizontal >= -20 ? -1 : 0;
            lastVertical = vertical;
            lastHorizontal = horizontal;
            if (direction != 0) {
                var item = items.get(selected);
                boolean fine = (input & UpstreamSinglePlayerInput.CAMPAIGN_HELD) != 0;
                int amount = UpstreamCampaignLoadout.adjustCargo(requested.get(item), direction, capacity, fine);
                requested.set(item, amount);
                System.out.println("UPSTREAM_CAMPAIGN_CARGO item=" + item.name + " amount=" + amount);
            }
            if (rows == null || selected != previous || direction != 0) {
                int first = (selected / 5) * 5, end = Math.min(first + 5, items.size);
                String[] captions = new String[1 + end - first];
                captions[0] = Core.bundle.get("resources");
                for (int i = first; i < end; i++) {
                    var item = items.get(i);
                    captions[1 + i - first] = (i == selected ? "> " : "  ") + item.localizedName + "  " + requested.get(item);
                }
                rows = encodeMenuRows(captions);
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
            var slot = slots.get(i);
            var sector = slot.sector;
            String caption = sector.planet.localizedName + " / " + sector.name();
            if (slot.timestamp < 0) caption += " / " + Core.bundle.get("newgame");
            else if (slot.origin != null) caption += " / " + Core.bundle.get("sector.curlost");
            captions[1 + i - first] = (i == selected ? "> " : "  ") + caption;
        }
        return captions;
    }

    private void loadUnitGraphics(Unit unit) {
        UnitType type = unit.type();
        if (loadedUnitGraphics.add(type)) {
            type.loadIcon();
            type.load();
            System.out.println("UPSTREAM_SP_UNIT_ART type=" + type.name);
        }
        // Upstream UnitType.drawPayload draws only the first carried payload.
        if (unit instanceof Payloadc carrier && carrier.hasPayload()
                && carrier.payloads().first() instanceof UnitPayload payload) {
            loadUnitGraphics(payload.unit);
        }
    }

    private void loadBulletGraphics(BulletType type) {
        if (!loadedBulletGraphics.add(type)) return;
        boolean wasHeadless = Vars.headless;
        Vars.headless = false;
        try {
            type.load();
        } finally {
            Vars.headless = wasHeadless;
        }
    }

    private int pollCampaignInput() {
        int input = UpstreamSinglePlayerInput.poll();
        if (input == -1) pickerExitRequested = true;
        return input;
    }

    private boolean showCampaignDefeat(Sector sector) {
        MenuRows rows = encodeMenuRows(new String[]{
            Strings.stripColors(Core.bundle.format("sector.lost", sector.name())),
            "A " + Core.bundle.get("continue")
        });
        while (true) {
            long nextFrame = System.nanoTime() + FRAME_NS;
            int input = pollCampaignInput();
            if (input == -1) return false;
            if ((input & UpstreamSinglePlayerInput.A_PRESSED) != 0) return true;
            drawMenuRows(rows);
            while (System.nanoTime() < nextFrame) Thread.yield();
        }
    }

    private boolean openResearch(TechNode root, Fi saveFile) throws IOException {
        var research = new UpstreamCampaignResearch(root);
        Seq<TechNode> nodes = new Seq<>();
        listResearch(root, nodes);
        int selected = 0, page = 0, lastAxis = 0;
        boolean detail = false;
        MenuRows rows = null;
        System.out.println("UPSTREAM_CAMPAIGN_RESEARCH_OPEN tree=" + root.name + " visible=" + nodes.size);
        while (true) {
            long nextFrame = System.nanoTime() + FRAME_NS;
            int input = UpstreamSinglePlayerInput.poll();
            if (input == -1) return false;

            TechNode node = nodes.get(selected);
            if ((input & UpstreamSinglePlayerInput.B_PRESSED) != 0) {
                if (!detail) return true;
                detail = false;
                rows = null;
            } else if ((input & UpstreamSinglePlayerInput.A_PRESSED) != 0) {
                if (!detail) {
                    detail = true;
                    page = 0;
                    rows = null;
                    System.out.println("UPSTREAM_CAMPAIGN_RESEARCH_SELECTED node=" + node.content.name
                        + " selectable=" + research.selectable(node));
                } else if (research.spend(node)) {
                    session.save(saveFile);
                    nodes.clear();
                    listResearch(root, nodes);
                    selected = nodes.indexOf(node, true);
                    rows = null;
                    System.out.println("UPSTREAM_CAMPAIGN_RESEARCH_CHANGED node=" + node.content.name
                        + " unlocked=" + node.content.unlockedHost());
                }
            }

            int axis = (byte)(input >> 8);
            int direction = axis > 60 && lastAxis <= 20 ? -1 : axis < -60 && lastAxis >= -20 ? 1 : 0;
            lastAxis = axis;
            if (direction != 0) {
                if (detail) {
                    int lastPage = Math.max(0, (node.requirements.length + node.objectives.size - 1) / 4);
                    page = Mathf.clamp(page + direction, 0, lastPage);
                } else {
                    selected = Mathf.clamp(selected + direction, 0, nodes.size - 1);
                }
                rows = null;
            }
            if (rows == null) {
                rows = encodeMenuRows(detail ? researchDetail(node, research, page) : researchList(nodes, selected));
            }
            drawMenuRows(rows);
            while (System.nanoTime() < nextFrame) Thread.yield();
        }
    }

    private static void listResearch(TechNode node, Seq<TechNode> nodes) {
        nodes.add(node);
        if (node.content.unlockedHost()) {
            for (TechNode child : node.children) listResearch(child, nodes);
        }
    }

    private static String[] researchList(Seq<TechNode> nodes, int selected) {
        int first = (selected / 5) * 5, end = Math.min(first + 5, nodes.size);
        String[] captions = new String[2 + end - first];
        captions[0] = Core.bundle.get("research") + "  " + (selected + 1) + " / " + nodes.size;
        for (int i = first; i < end; i++) {
            var content = nodes.get(i).content;
            captions[i - first + 1] = (i == selected ? "> " : "  ") + content.localizedName
                + (content.unlockedHost() ? "  [x]" : "");
        }
        captions[captions.length - 1] = "B " + Core.bundle.get("back");
        return captions;
    }

    private static String[] researchDetail(TechNode node, UpstreamCampaignResearch research, int page) {
        int first = page * 4, end = Math.min(first + 4, node.requirements.length + node.objectives.size);
        String[] captions = new String[2 + end - first];
        captions[0] = node.content.localizedName;
        for (int i = first; i < end; i++) {
            if (i < node.objectives.size) {
                var objective = node.objectives.get(i);
                captions[i - first + 1] = (objective.complete() ? "[x] " : "[ ] ")
                    + Strings.stripColors(objective.display()).replace('\n', ' ');
            } else {
                int index = i - node.objectives.size;
                var requirement = node.requirements[index];
                captions[i - first + 1] = requirement.item.localizedName + "  "
                    + node.finishedRequirements[index].amount + " / " + requirement.amount
                    + "  (+" + research.available(requirement.item) + ")";
            }
        }
        String status = node.content.unlockedHost() ? Strings.stripColors(Core.bundle.format("researched", node.content.localizedName))
            : research.selectable(node) ? "A " + Core.bundle.get("research") : Core.bundle.get("locked");
        captions[captions.length - 1] = status + "   B " + Core.bundle.get("back");
        return captions;
    }

    private boolean runMainLoop(Fi saveFile) throws IOException {
        var unit = session.ensurePlayerSpawn();
        var core = session.core();
        UpstreamHudFixture.apply();
        lastCopper = core == null ? 0 : core.items.get(Items.copper);
        if (unit != null) {
            System.out.println("UPSTREAM_SP_READY unit=" + unit.id() + " x=" + unit.x() + " y=" + unit.y()
                + " copper=" + lastCopper + " wave=" + Vars.state.wave);
        }

        long previousTime = System.nanoTime();
        session.beginPlaytimeInterval(previousTime);
        long accumulator = 0L;
        while (true) {
            UpstreamNotification.update(1f / 60f);
            int input = UpstreamSinglePlayerInput.poll();
            long now = System.nanoTime();
            session.updatePlaytime(now);
            if (input == -1) return false;
            if (campaignSlot != null && (input & UpstreamSinglePlayerInput.CAMPAIGN_HELD) != 0
                    && (input & UpstreamSinglePlayerInput.SAVE_PRESSED) != 0) return true;
            int touch = UpstreamSinglePlayerInput.touch();

            var sector = Vars.state.rules.sector;
            int researchKeys = UpstreamSinglePlayerInput.L_HELD | UpstreamSinglePlayerInput.SAVE_PRESSED;
            if (sector != null && sector.planet.techTree != null && (input & researchKeys) == researchKeys) {
                if (!openResearch(sector.planet.techTree, saveFile)) return false;
                previousTime = System.nanoTime();
                session.beginPlaytimeInterval(previousTime);
                accumulator = 0L;
                continue;
            }

            int axis = (byte)(input >> 8);
            int direction = axis > 60 && pauseAxis <= 20 ? -1 : axis < -60 && pauseAxis >= -20 ? 1 : 0;
            pauseAxis = axis;
            if (pauseOpen) {
                if (settingsOpen) {
                    if (direction != 0) settingsMenu.move(direction);
                    if ((input & UpstreamSinglePlayerInput.A_PRESSED) != 0) {
                        if (settingsMenu.backSelected()) {
                            settingsOpen = false;
                        } else if (settingsMenu.soundSelected()) {
                            if (!chooseSoundSettings(Core.settings)) return false;
                            previousTime = System.nanoTime();
                        } else {
                            toggleHudSetting(Core.settings);
                        }
                    } else if ((input & (UpstreamSinglePlayerInput.B_PRESSED
                            | UpstreamSinglePlayerInput.X_PRESSED)) != 0) {
                        settingsOpen = false;
                    }
                    moveInput.setZero();
                } else {
                    if (sessionHooks.canHost() && !pauseMenu.confirmingQuit()
                            && (input & UpstreamSinglePlayerInput.R_PRESSED) != 0) {
                        try {
                            sessionHooks.toggleHosting();
                            closePauseMenu();
                        } catch (IOException failure) {
                            System.out.println("UPSTREAM_HOST_ERROR " + failure.getMessage());
                            UpstreamNotification.show(Core.bundle.get("server.error"), 4f);
                            closePauseMenu();
                        }
                        previousTime = System.nanoTime();
                        accumulator = 0L;
                        continue;
                    }
                    if ((input & UpstreamSinglePlayerInput.WAVE_PRESSED) != 0 && !pauseMenu.confirmingQuit()) {
                        if (!new UpstreamCoreDatabaseMenu(target).run()) return false;
                        previousTime = System.nanoTime();
                        accumulator = 0L;
                        continue;
                    }
                    if (direction != 0) pauseMenu.move(direction);
                    if ((input & UpstreamSinglePlayerInput.A_PRESSED) != 0) {
                        var action = pauseMenu.activate();
                        if (action == UpstreamPauseMenu.Action.RESUME) {
                            closePauseMenu();
                        } else if (action == UpstreamPauseMenu.Action.LOAD) {
                            closePauseMenu();
                            var loadSector = Vars.state.rules.sector;
                            session.start(sourceMap, saveFile, campaignSlot == null ? null : "campaign", loadSector);
                            resetWorldInput();
                            weatherLayers.reset();
                            reportWeather();
                            uploadMinimap();
                            previousTime = System.nanoTime();
                            accumulator = 0L;
                            System.out.println("UPSTREAM_PAUSE_MENU load=true");
                            continue;
                        } else if (action == UpstreamPauseMenu.Action.RESEARCH) {
                            closePauseMenu();
                            var researchSector = Vars.state.rules.sector;
                            if (researchSector == null || researchSector.planet == null
                                    || researchSector.planet.techTree == null
                                    || !openResearch(researchSector.planet.techTree, saveFile)) return false;
                            previousTime = System.nanoTime();
                            accumulator = 0L;
                            continue;
                        } else if (action == UpstreamPauseMenu.Action.SETTINGS) {
                            settingsOpen = true;
                            openHudSettings(Core.settings);
                            System.out.println("UPSTREAM_PAUSE_MENU settings=true");
                        } else if (action == UpstreamPauseMenu.Action.SAVE) {
                            session.save(saveFile);
                            System.out.println("UPSTREAM_PAUSE_MENU save=true");
                        } else if (action == UpstreamPauseMenu.Action.QUIT) {
                            pauseOpen = false;
                            session.save(saveFile);
                            returnToTitleRequested = true;
                            System.out.println("UPSTREAM_PAUSE_MENU quit=true");
                            return false;
                        }
                    } else if ((input & (UpstreamSinglePlayerInput.B_PRESSED
                            | UpstreamSinglePlayerInput.X_PRESSED)) != 0) {
                        if (pauseMenu.cancel() == UpstreamPauseMenu.Action.RESUME) closePauseMenu();
                    }
                    moveInput.setZero();
                }
            } else {
                moveInput.set((byte)input / 127f, (byte)(input >>> 8) / 127f);
                if (moveInput.len2() < DEAD_ZONE * DEAD_ZONE) {
                    moveInput.setZero();
                } else {
                    moveInput.limit(1f);
                }
                boolean mapWasOpen = minimapView.open();
                if ((input & UpstreamSinglePlayerInput.R_PRESSED) != 0) {
                    if (mapWasOpen) minimapView.close(); else minimapView.openView();
                    System.out.println("UPSTREAM_MINIMAP_VIEW open=" + minimapView.open());
                }
                if (minimapView.open()) {
                    if ((input & UpstreamSinglePlayerInput.B_PRESSED) != 0) {
                        minimapView.close();
                        System.out.println("UPSTREAM_MINIMAP_VIEW open=false");
                    }
                    if ((input & UpstreamSinglePlayerInput.X_PRESSED) != 0) minimapView.zoomBy(-0.5f);
                    if ((input & UpstreamSinglePlayerInput.WAVE_PRESSED) != 0) minimapView.zoomBy(0.5f);
                    if ((input & (UpstreamSinglePlayerInput.X_PRESSED
                            | UpstreamSinglePlayerInput.WAVE_PRESSED)) != 0) {
                        System.out.println("UPSTREAM_MINIMAP_VIEW zoom=" + minimapView.zoom());
                    }
                    if (!moveInput.isZero()) {
                        minimapView.move(-moveInput.x * 4f, moveInput.y * 4f);
                    }
                    moveInput.setZero();
                } else {
                    boolean aimHeld = (input & UpstreamSinglePlayerInput.X_HELD) != 0;
                    boolean fireHeld = (input & UpstreamSinglePlayerInput.L_HELD) != 0;
                    if (weaponInput.releasePauses((input & UpstreamSinglePlayerInput.X_PRESSED) != 0,
                            aimHeld, !moveInput.isZero(), fireHeld, now)) {
                        openPauseMenu();
                    }
                    if ((input & UpstreamSinglePlayerInput.WAVE_PRESSED) != 0) {
                        int wave = session.triggerWave();
                        int enemies = Groups.unit.count(u -> u.team == Vars.state.rules.waveTeam && !u.dead);
                        System.out.println("UPSTREAM_SP_WAVE wave=" + wave + " enemies=" + enemies);
                    }
                    if ((input & UpstreamSinglePlayerInput.SAVE_PRESSED) != 0) {
                        session.save(saveFile);
                        if (unit != null) {
                            int shots = UpstreamOfflineWeaponInput.totalShots(unit);
                            if (shots > 0) System.out.println("UPSTREAM_SP_WEAPON_SAVED shots=" + shots);
                        }
                    }

                    unit = session.ensurePlayerSpawn();
                    if (unit != null) {
                        buildingControls.update(unit, input, touch);
                        unit = session.ensurePlayerSpawn();
                    }
                    if (unit != null) {
                        boolean anchored = unit instanceof BlockUnitc;
                        boolean overlay = buildingControls.blocksWeaponInput();
                        weaponInput.update(unit, moveInput, (anchored || weaponInput.aimMode()) && !moveInput.isZero(),
                            fireHeld && !overlay && !Vars.state.isPaused());
                        if (anchored) moveInput.setZero();
                    }
                    if (weaponInput.aimMode()) moveInput.setZero();
                }
            }
            long elapsed = Math.min(Math.max(0L, now - previousTime), FRAME_NS * 4L);
            previousTime = now;
            accumulator += elapsed;

            if (Vars.state.isPaused()) {
                accumulator = 0L;
            } else {
                int steps = 0;
                while (accumulator >= FRAME_NS && steps < 2) {
                    session.stepSimulation(moveInput.x, moveInput.y);
                    if (campaignSlot != null && Vars.state.gameOver) return true;
                    accumulator -= FRAME_NS;
                    steps++;
                }
                if (steps == 2 && accumulator >= FRAME_NS * 2L) {
                    accumulator = 0L;
                }
            }

            unit = session.ensurePlayerSpawn();
            sessionHooks.tick();
            updateTelemetry(unit, input);
            render(unit);
            Thread.yield();
        }
    }

    private void updateTelemetry(Unit unit, int input) {
        if (unit != null) {
            if (reportedControl != unit) {
                reportedControl = unit;
                System.out.println("UPSTREAM_CONTROL_FOCUS target=" + UpstreamPlayerControl.name(unit));
            }
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

    private void uploadMinimap() {
        byte[] pixels = UpstreamMinimap.sample(64);
        if (pixels == null) return;
        target.minimapUpload(pixels, 64, 64);
        if (!reportedMinimap) {
            reportedMinimap = true;
            int marker = UpstreamMinimap.playerIndex(64);
            System.out.println("UPSTREAM_MINIMAP size=64 hash="
                + Long.toUnsignedString(UpstreamMinimap.hash(pixels), 16)
                + (marker < 0 ? "" : " cell=" + (marker % 64) + "," + (marker / 64)));
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
            Core.bundle.get("category.items"), "Give", Core.bundle.get("command"), "Type", "Stance",
            "Ctrl", Core.bundle.get("back"), "Schem") + "\0";
        target.buildLabels(labels.getBytes(StandardCharsets.UTF_8));
        lastPrimaryShortcut = primary;
        System.out.println("UPSTREAM_BUILD_SHORTCUTS primary=" + primary.name + " secondary=" + secondary.name);
    }

    private void render(Unit unit) {
        session.advanceRenderFrame();
        updateBuildLabels();
        if (++minimapFrame >= 60) {
            minimapFrame = 0;
            uploadMinimap();
        }
        controlled = unit;
        if (buildingControls != null) {
            int zoom = buildingControls.consumeZoom();
            if (zoom != 0) {
                viewport.zoomBy(zoom * UpstreamTerrainDrawProbe.ZOOM_STEP);
                System.out.println("UPSTREAM_CAMERA_ZOOM zoom=" + viewport.zoom()
                    + " span=" + viewport.width() + "x" + viewport.height());
            }
        }
        float x = unit == null ? Vars.player.x() : unit.x();
        float y = unit == null ? Vars.player.y() : unit.y();
        viewport.moveTo(World.toTile(x) - viewport.width() / 2,
                        World.toTile(y) - viewport.height() / 2);
        Core.camera.width = viewport.width() * Vars.tilesize;
        Core.camera.height = viewport.height() * Vars.tilesize;
        Core.camera.position.set((viewport.left - 0.5f) * Vars.tilesize + Core.camera.width / 2f,
            (viewport.bottom - 0.5f) * Vars.tilesize + Core.camera.height / 2f);
        advanceVisualState();
        draws = 0;
        viewport.draw(drawWeatherUnder, drawEntities);
        buildingControls.viewport(viewport.left, viewport.bottom, viewport.width(), viewport.height());
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
        float delta = Math.min(frameSeconds * 60f, Vars.maxDeltaClient);
        Time.delta = delta;
        try {
            audio.update(now);
            weatherLayers.advance();
            if (Vars.state.isPaused()) return;
            Time.setInternalTime(Time.getInternalTime() + delta);
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
                if (unit instanceof BlockUnitc) continue;
                int x = World.toTile(unit.x()), y = World.toTile(unit.y());
                if (x < viewport.left || x >= viewport.left + viewport.width()
                        || y < viewport.bottom || y >= viewport.bottom + viewport.height()) continue;
                loadUnitGraphics(unit);
                unit.draw();
                if (unit == controlled) draws++;
                if (unit.team == Vars.state.rules.waveTeam && reportedEnemies.add(unit.id)) {
                    System.out.println("UPSTREAM_SP_ENEMY type=" + unit.type.name + " id=" + unit.id
                        + " x=" + unit.x() + " y=" + unit.y());
                }
            }
            for (var bullet : Groups.bullet) {
                int x = World.toTile(bullet.x()), y = World.toTile(bullet.y());
                if (x < viewport.left || x >= viewport.left + viewport.width()
                        || y < viewport.bottom || y >= viewport.bottom + viewport.height()) continue;
                loadBulletGraphics(bullet.type());
                bullet.draw();
            }
        } finally {
            Draw.sort(false);
        }
        UpstreamObjectiveMarkers.draw(Vars.state.rules, 240f / (viewport.height() * Vars.tilesize),
            worldLabel);
        if (Core.settings.getBool("showweather", true)) weatherLayers.drawOver();
        var orders = buildingControls.orders();
        orders.draw(viewport);
        var schematics = buildingControls.schematics();
        schematics.draw(viewport);
        drawCoreItems();
        drawPositionReadout();
        float hudY = drawStatusHud();
        // Base Block.setBars order: health, power, items, then liquids.
        hudY = buildingBasicBars.drawHealth(target, buildingControls, Vars.player.team(), hudY);
        hudY = buildingPowerHud.draw(target, buildingControls, Vars.player.team(), hudY);
        hudY = buildingBasicBars.drawItems(target, buildingControls, Vars.player.team(), hudY);
        hudY = buildingLiquidHud.draw(target, buildingControls, Vars.player.team(), hudY);
        drawObjectives(hudY);
        drawBossBar();
        if (pauseOpen) drawPauseMenu();
        else drawFullscreenMinimap();
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
            encodedCharacters = UpstreamHudLabels.encodeLabel(text);
            encodedLabel = text;
        }
        target.drawWorldLabel(text.plain(), encodedCharacters, x, y, flags, size, align, lineAlign);
    }

    private float drawStatusHud() {
        var rows = new java.util.ArrayList<>(UpstreamStatusHud.collect());
        if (Core.settings.getBool("showperformance", false)) {
            int fps = frameSeconds > 0f ? Math.round(1f / frameSeconds) : 0;
            rows.add(new UpstreamObjectiveHud.Row("FPS " + fps, "FPS " + fps, "none", null));
        }
        if (!rows.equals(lastStatus)) {
            statusLabels = new byte[rows.size()][];
            statusCharacters = new int[rows.size()][];
            for (int i = 0; i < rows.size(); i++) {
                statusLabels[i] = (rows.get(i).text() + "\0").getBytes(StandardCharsets.UTF_8);
                statusCharacters[i] = UpstreamHudLabels.encodeLabel(UpstreamLabelText.parse(UI.formatIcons(rows.get(i).markup())));
            }
            lastStatus = rows;
        }
        float y = 4f;
        int start = 0;
        if (!rows.isEmpty() && UpstreamNotification.current() != null) {
            y = target.drawObjective(statusLabels[0], statusCharacters[0], rows.get(0).icon(), y);
            start = 1;
        }
        if (rows.size() > start) {
            float panelHeight = (rows.size() - start) * 35f + 4f;
            target.hudPanel(2f, y - 1f, 164f, panelHeight, 0xE0201B18);
        }
        for (int i = start; i < rows.size() && y < 240f; i++) {
            y = target.drawObjectivePlain(statusLabels[i], statusCharacters[i], rows.get(i).icon(), y);
        }
        return y;
    }

    private void drawFullscreenMinimap() {
        if (!minimapView.open()) return;
        target.hudPanel(0f, 0f, UpstreamMinimapView.screenWidth, UpstreamMinimapView.screenHeight, 0xF0080A0C);
        target.drawMinimapAt(minimapView.x(), minimapView.y(), minimapView.size());
        if (minimapViewTitle == null) {
            String title = Core.bundle.get("minimap");
            minimapViewTitle = (title + "\0").getBytes(StandardCharsets.UTF_8);
            minimapViewTitleCharacters = UpstreamHudLabels.encodeLabel(UpstreamLabelText.parse(UI.formatIcons(title)));
            String back = Core.bundle.get("back") + "  R/B";
            minimapViewBack = (back + "\0").getBytes(StandardCharsets.UTF_8);
            minimapViewBackCharacters = UpstreamHudLabels.encodeLabel(UpstreamLabelText.parse(UI.formatIcons(back)));
        }
        target.drawObjectiveAt(minimapViewTitle, minimapViewTitleCharacters, null, 8f, 4f);
        target.drawObjectiveAt(minimapViewBack, minimapViewBackCharacters, null, 8f, 196f);
    }

    private void drawPositionReadout() {
        if (!Core.settings.getBool("position", false)) return;
        var unit = Vars.player == null ? null : Vars.player.unit();
        if (unit == null) return;
        String text = unit.tileX() + "," + unit.tileY();
        if (!text.equals(lastPositionText)) {
            positionLabel = (text + "\0").getBytes(StandardCharsets.UTF_8);
            positionCharacters = UpstreamHudLabels.encodeLabel(UpstreamLabelText.parse(text));
            lastPositionText = text;
        }
        target.drawObjectiveAt(positionLabel, positionCharacters, null, 336f, 68f);
    }

    private void drawCoreItems() {
        if (!Core.settings.getBool("coreitems", true)) return;
        var items = coreItemTracker.live();
        float x = 168f;
        float y = 4f;
        int drawn = 0;
        for (var entry : items) {
            var item = entry.item();
            if (item.uiIcon == null) item.loadIcon();
            var icon = item.uiIcon != null && item.uiIcon.found() ? item.uiIcon : null;
            String value = entry.formatted();
            byte[] label = (value + "\0").getBytes(StandardCharsets.UTF_8);
            int[] characters = UpstreamHudLabels.encodeLabel(UpstreamLabelText.parse(value));
            float next = target.drawObjectiveAt(label, characters, icon, x, y);
            if (next >= 240f) {
                x += 56f;
                if (x > 280f) break;
                y = 4f;
                next = target.drawObjectiveAt(label, characters, icon, x, y);
            }
            if (next >= 240f) break;
            y = next;
            drawn++;
        }
        int columns = items.isEmpty() ? 0 : (int) ((x - 168f) / 56f) + 1;
        if (items.size() != lastCoreItemCount || drawn != lastCoreItemDrawn) {
            lastCoreItemCount = items.size();
            lastCoreItemDrawn = drawn;
            System.out.println("UPSTREAM_ITEM_ROWS items=" + items.size()
                + " drawn=" + drawn + " columns=" + columns);
        }
    }

    private void openPauseMenu() {
        var sector = Vars.state.rules.sector;
        boolean research = sector != null && sector.planet != null && sector.planet.techTree != null;
        pauseMenu.openWorld(research);
        openHudSettings(Core.settings);
        settingsOpen = false;
        pauseOpen = true;
        if (!Vars.state.isPaused() && !sessionHooks.hosting()) session.togglePause();
        System.out.println("UPSTREAM_PAUSE_MENU open=true selected=0 options=" + pauseMenu.options());
    }

    private void closePauseMenu() {
        pauseOpen = false;
        if (Vars.state.isPaused()) session.togglePause();
        System.out.println("UPSTREAM_PAUSE_MENU open=false");
    }

    private void drawPauseMenu() {
        drawPauseRow(Core.bundle.get(Vars.state.isPaused() ? "paused" : "menu"), 170f, 24f, false);
        var sector = Vars.state.rules.sector;
        if (sector != null) {
            drawPauseRow(sector.planet.localizedName + " / " + sector.name(), 160f, 48f, false);
        }
        if (sector != null) {
            var objectives = UpstreamObjectiveHud.collect(Vars.state.rules);
            if (!objectives.isEmpty()) {
                drawPauseRow(objectives.get(0).text(), 100f, 72f, false);
            }
        }
        if (settingsOpen) {
            drawPauseRow(Core.bundle.get("settings"), 170f, 100f, false);
            var rows = hudSettingsRows();
            float y = 116f;
            for (int i = 0; i < rows.length; i++) {
                boolean selected = settingsMenu.selected() == i;
                drawPauseRow((selected ? "> " : "  ") + rows[i], 92f, y, selected);
                y += 18f;
            }
            return;
        }
        float y = 80f;
        for (int i = 0; i < pauseMenu.options(); i++) {
            boolean selected = i == pauseMenu.selected();
            String label = Core.bundle.get(pauseMenu.actionAt(i).labelKey);
            drawPauseRow((selected ? "> " : "  ") + label, 170f, y, selected);
            y += 24f;
        }
        if (pauseMenu.confirmingQuit()) {
            drawPauseRow(Core.bundle.get("quit.confirm"), 40f, 210f, false);
        } else {
            drawPauseRow("Y  " + Core.bundle.get("database"), 44f, 210f, false);
            if (sessionHooks.canHost()) {
                String action = Core.bundle.get(sessionHooks.hosting() ? "close" : "hostserver.mobile");
                drawPauseRow("R  " + Strings.stripColors(action), 214f, 210f, false);
            }
        }
    }

    private void openHudSettings(Settings settings) {
        settingsMenu.open(settings.getBool("coreitems", true), settings.getBool("minimap", true),
            settings.getBool("showweather", true), settings.getBool("showperformance", false), settings.getBool("position", false));
    }

    private void toggleHudSetting(Settings settings) {
        int index = settingsMenu.selected();
        boolean enabled = settingsMenu.toggleSelected();
        switch (index) {
            case 0 -> {
                settings.put("coreitems", enabled);
                lastCoreItemCount = -1;
                lastCoreItemDrawn = -1;
            }
            case 1 -> settings.put("minimap", enabled);
            case 2 -> settings.put("showweather", enabled);
            case 3 -> settings.put("showperformance", enabled);
            case 4 -> settings.put("position", enabled);
        }
        System.out.println("UPSTREAM_SETTINGS index=" + index + " value=" + enabled);
    }

    private String[] hudSettingsRows() {
        return new String[]{
            hudSettingRow("setting.coreitems.name", settingsMenu.coreItems()),
            hudSettingRow("setting.minimap.name", settingsMenu.minimap()),
            hudSettingRow("setting.showweather.name", settingsMenu.weather()),
            hudSettingRow("setting.showperformance.name", settingsMenu.performance()),
            hudSettingRow("setting.position.name", settingsMenu.position()),
            Core.bundle.get("settings.sound"),
            Core.bundle.get("back")
        };
    }

    private boolean chooseSoundSettings(Settings settings) {
        String[] keys = {"musicvol", "sfxvol", "ambientvol"};
        int[] volume = new int[keys.length];
        for (int i = 0; i < keys.length; i++) volume[i] = Mathf.clamp(settings.getInt(keys[i], 100), 0, 100);
        byte[] help = ("Left   -\0Right   +\0B   " + Core.bundle.get("back") + "\0")
            .getBytes(StandardCharsets.UTF_8);
        int selected = 0, lastY = 0, lastDirection = 0;
        long repeatAt = 0;
        boolean changed = false;
        MenuRows rows = null;
        UpstreamSinglePlayerInput.poll();
        while (true) {
            long started = System.nanoTime();
            int input = UpstreamSinglePlayerInput.poll();
            int touch = UpstreamTerrainRenderProbe.menuHelpAction(UpstreamSinglePlayerInput.touch(), 3);
            if (input < 0 || touch == 2 || (input & UpstreamSinglePlayerInput.B_PRESSED) != 0) {
                if (changed) settings.manualSave();
                return input >= 0;
            }
            int y = (byte)(input >> 8);
            if (y > 60 && lastY <= 20) {
                selected = Math.floorMod(selected - 1, keys.length);
                rows = null;
            }
            if (y < -60 && lastY >= -20) {
                selected = (selected + 1) % keys.length;
                rows = null;
            }
            lastY = y;
            int x = (byte)input;
            int direction = x < -60 ? -1 : x > 60 ? 1 : 0;
            int delta = touch == 0 ? -1 : touch == 1 ? 1 : 0;
            if (direction != lastDirection) {
                repeatAt = started + 350_000_000L;
                if (delta == 0) delta = direction;
            } else if (direction != 0 && started >= repeatAt && delta == 0) {
                delta = direction;
                repeatAt = started + 65_000_000L;
            }
            lastDirection = direction;
            int next = Mathf.clamp(volume[selected] + delta, 0, 100);
            if (next != volume[selected]) {
                volume[selected] = next;
                settings.put(keys[selected], next);
                changed = true;
                rows = null;
            }
            if (rows == null) {
                var captions = new String[keys.length + 1];
                captions[0] = Core.bundle.get("settings.sound");
                for (int i = 0; i < keys.length; i++) {
                    captions[i + 1] = (i == selected ? "> " : "  ") + Core.bundle.get("setting." + keys[i] + ".name")
                        + "  " + volume[i] + "%";
                }
                rows = encodeMenuRows(captions);
            }
            drawMenuRows(rows, help, 3);
            while (System.nanoTime() < started + FRAME_NS) Thread.yield();
        }
    }

    private static String hudSettingRow(String label, boolean enabled) {
        return Core.bundle.get(label) + ": " + Core.bundle.get(enabled ? "on" : "off");
    }

    private void drawPauseRow(String text, float x, float y, boolean selected) {
        String markup = (selected ? "[accent]" : "[lightgray]") + text;
        byte[] label = (arc.util.Strings.stripColors(text) + "\0").getBytes(StandardCharsets.UTF_8);
        int[] characters = UpstreamHudLabels.encodeLabel(UpstreamLabelText.parse(UI.formatIcons(markup)));
        target.drawObjectiveAt(label, characters, null, x, y);
    }

    private void drawBossBar() {
        var boss = UpstreamBossHud.collect();
        if (boss == null) return;
        if (!boss.markup().equals(lastBossText)) {
            bossLabel = (arc.util.Strings.stripColors(boss.markup()) + "\0").getBytes(StandardCharsets.UTF_8);
            bossCharacters = UpstreamHudLabels.encodeLabel(UpstreamLabelText.parse(UI.formatIcons(boss.markup())));
            lastBossText = boss.markup();
        }
        float next = target.drawObjectiveAt(bossLabel, bossCharacters, null, 40f, 184f);
        target.hudPanel(40f, next - 2f, 320f, 12f, 0xE0201B18);
        float fill = 318f * boss.fraction();
        if (fill > 0f) target.hudPanel(41f, next - 1f, fill, 10f, 0xE0D23C3C);
    }

    private void drawObjectives(float y) {
        var rows = UpstreamObjectiveHud.collect(Vars.state.rules);
        if (!rows.equals(lastObjectives)) {
            objectiveLabels = new byte[rows.size()][];
            objectiveCharacters = new int[rows.size()][];
            for (int i = 0; i < rows.size(); i++) {
                objectiveLabels[i] = (rows.get(i).text() + "\0").getBytes(StandardCharsets.UTF_8);
                objectiveCharacters[i] = UpstreamHudLabels.encodeLabel(UpstreamLabelText.parse(UI.formatIcons(rows.get(i).markup())));
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
        return build instanceof CanvasBuild || build instanceof LogicDisplayBuild
            || UpstreamBuildingLayers.supportsPowerDraw(block) || UpstreamBuildingLayers.supportsDistributionDraw(block)
            || block.category == Category.liquid || block instanceof UnitFactory
            || UpstreamBuildingLayers.supportsMaterialDraw(block) || block instanceof Wall
            || UpstreamBuildingLayers.supportsExtractorDraw(block)
            || block instanceof BaseTurret
            || block == Blocks.overdriveProjector
            || block == Blocks.overdriveDome || block == Blocks.shockwaveTower;
    }

    private void drawMenu(byte[] labels, int[] icons, int selected,
                            boolean previous, boolean next, int catalog) {
        target.buildCatalog(labels, icons, selected, previous, next, catalog);
        lastMenu = labels;
    }
}
