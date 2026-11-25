import arc.Core;
import arc.Events;
import arc.Settings;
import arc.assets.AssetManager;
import arc.audio.Audio;
import arc.files.Fi;
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.mock.MockAudio;
import arc.mock.MockFiles;
import arc.mock.MockGraphics;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.struct.StringMap;
import arc.util.ArcRuntimeException;
import arc.util.Time;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.ai.BlockIndexer;
import mindustry.ai.ControlPathfinder;
import mindustry.ai.Pathfinder;
import mindustry.async.AvoidanceProcess;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Planets;
import mindustry.content.UnitTypes;
import mindustry.core.GameState.State;
import mindustry.ctype.ContentType;
import mindustry.entities.EntityCollisions;
import mindustry.entities.Units;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.RulesLoadEvent;
import mindustry.game.EventType.SectorLaunchEvent;
import mindustry.game.EventType.SectorLaunchLoadoutEvent;
import mindustry.game.EventType.Trigger;
import mindustry.game.FogControl;
import mindustry.game.Gamemode;
import mindustry.game.Saves;
import mindustry.game.Team;
import mindustry.game.Universe;
import mindustry.game.Waves;
import mindustry.gen.Groups;
import mindustry.gen.BlockUnitc;
import mindustry.gen.Player;
import mindustry.gen.Unit;
import mindustry.graphics.CacheLayer;
import mindustry.io.JsonIO;
import mindustry.io.MapIO;
import mindustry.io.SaveIO;
import mindustry.io.SaveMeta;
import mindustry.io.SaveOptions;
import mindustry.maps.Map;
import mindustry.type.Planet;
import mindustry.type.Sector;
import mindustry.type.UnitType;
import mindustry.logic.GlobalVars;
import mindustry.mod.Mods;
import mindustry.world.Tile;
import mindustry.world.WorldParams;
import mindustry.world.blocks.ControlBlock;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/**
 * Self-contained offline single-player Mindustry v160.8 session lifecycle and
 * deterministic Ground Zero parity verifier (`singleplayer-sim`).
 */
public final class UpstreamSinglePlayerSession {
    private static final float FIXED_STEP_SECONDS = 1f / 60f;
    private final UpstreamWorldLoadProbe.ProbeApplication app;
    private final Vec2 tmpMove = new Vec2();
    private Saves campaignSaves;
    private float deltaSeconds = FIXED_STEP_SECONDS;
    private long playtimeMillis, playtimeRemainderNanos, playtimeFrameNanos;
    private UnitType savedControlledType;
    private String savedControlGroups = "null";

    private UpstreamSinglePlayerSession(UpstreamWorldLoadProbe.ProbeApplication app) {
        this.app = app;
    }

    static UpstreamSinglePlayerSession create(Fi mapDirectory, String locale) {
        return create(mapDirectory, locale, null);
    }

    static UpstreamSinglePlayerSession create(Fi mapDirectory, String locale, Fi planetData) {
        return create(mapDirectory, locale, planetData, new Settings());
    }

    static Settings loadSettings(Fi directory) {
        var settings = new Settings();
        settings.setDataDirectory(directory);
        settings.setJson(JsonIO.json);
        settings.load();
        return settings;
    }

    static UpstreamSinglePlayerSession create(Fi mapDirectory, String locale, Fi planetData, Settings settings) {
        return create(mapDirectory, locale, planetData, settings, null);
    }

    static UpstreamSinglePlayerSession create(Fi mapDirectory, String locale, Fi planetData, Settings settings,
                                             Fi campaignMaps) {
        return create(mapDirectory, locale, planetData, settings, campaignMaps, new MockAudio());
    }

    static UpstreamSinglePlayerSession create(Fi mapDirectory, String locale, Fi planetData, Settings settings,
                                             Fi campaignMaps, Audio audio) {
        if (planetData != null && planetData.isDirectory()) {
            for (Fi file : planetData.list()) {
                if (!file.isDirectory() && file.extension().equals("json")) {
                    Vars.tree.addFile("planets/" + file.name(), file);
                }
            }
        }
        if (campaignMaps != null) {
            for (String directory : new String[]{"serpulo", "serpulo/hidden", "erekir"}) {
                for (Fi file : campaignMaps.child(directory).list()) {
                    if (!file.isDirectory() && file.extension().equals(Vars.mapExtension)) {
                        Vars.tree.addFile("maps/" + directory + "/" + file.name(), file);
                    }
                }
            }
        }
        Vars.saveDirectory = mapDirectory.child("saves");
        Vars.schematicDirectory = mapDirectory.child("schematics");
        // Blocks capture their default cache layer while the content registry is created.
        CacheLayer.init();
        settings.defaults("locale", locale == null || locale.isEmpty() ? "en" : locale);
        var app = UpstreamWorldLoadProbe.initialize(mapDirectory, settings, campaignMaps != null);
        Core.audio = audio;
        Core.files = new MockFiles();
        final UpstreamSinglePlayerSession[] holder = new UpstreamSinglePlayerSession[1];
        Core.graphics = new MockGraphics() {
            @Override public int getWidth() { return 400; }
            @Override public int getHeight() { return 240; }
            @Override public float getDensity() { return 1f; }
            @Override public long getFrameId() {
                return holder[0] == null ? 0 : holder[0].renderFrame;
            }
            @Override public float getDeltaTime() {
                return holder[0] == null ? FIXED_STEP_SECONDS : holder[0].deltaSeconds;
            }
        };
        Time.setDeltaProvider(() -> 1f);
        Vars.mods = new Mods();
        Vars.indexer = new BlockIndexer();
        Vars.spawner = new WaveSpawnerProxy().create();
        Vars.universe = new Universe();
        Vars.waves = new Waves();
        Vars.logicVars = new GlobalVars();
        Vars.logicVars.init();
        Vars.collisions = new EntityCollisions();
        Vars.bases = new mindustry.ai.BaseRegistry();
        Vars.bases.load();
        Vars.fogControl = new FogControl();
        Vars.avoidance = new AvoidanceProcess();
        Vars.pathfinder = new Pathfinder();
        Vars.controlPath = new ControlPathfinder();
        var session = new UpstreamSinglePlayerSession(app);
        holder[0] = session;
        return session;
    }

    private static final class WaveSpawnerProxy {
        mindustry.ai.WaveSpawner create() {
            return new mindustry.ai.WaveSpawner();
        }
    }

    UpstreamWorldLoadProbe.ProbeApplication application() {
        return app;
    }

    private long renderFrame;

    void advanceRenderFrame() {
        renderFrame++;
    }

    void setDeltaSeconds(float seconds) {
        this.deltaSeconds = seconds;
    }

    boolean start(Fi mapFile, Fi saveFile, String sourceTagOverride) throws IOException {
        return start(mapFile, saveFile, sourceTagOverride, null);
    }

    boolean start(Fi mapFile, Fi saveFile, String sourceTagOverride, Sector sector) throws IOException {
        String source;
        Fi sourceSave = sector != null && saveFile != null ? UpstreamCampaignSave.readableFile(saveFile) : saveFile;
        boolean loadedFromSave = sourceSave != null && sourceSave.exists() && SaveIO.isSaveValid(sourceSave);
        if (loadedFromSave) {
            var playerTags = new StringMap();
            var context = sector == null ? Vars.world.context : Vars.world.makeSectorContext(sector);
            SaveIO.load(sourceSave, context, playerTags);
            resetPlaytime(playerTags.getLong("playtime"));
            restorePlayerMetadata(playerTags);
            app.runPosted();
            Vars.avoidance.init();
            Vars.state.set(State.playing);
            if (sector != null) sector.planet.setLastSector(sector);
            source = sourceTagOverride != null ? sourceTagOverride : "save";
        } else {
            if (mapFile == null || !mapFile.exists()) {
                throw new IOException("Missing map file: " + (mapFile == null ? "null" : mapFile.path()));
            }
            var map = MapIO.createMap(mapFile, false);
            Mathf.rand.setSeed(160_008L);
            Vars.world.loadMap(map);
            resetPlaytime(0);
            restorePlayerMetadata(null);
            app.runPosted();
            Vars.avoidance.init();
            Vars.logic.play();
            app.runPosted();
            source = sourceTagOverride != null ? sourceTagOverride : "map";
        }
        reportLoadedWorld(source);
        return loadedFromSave;
    }

    void launchNewSector(Sector origin, Sector sector, Fi saveFile) throws IOException {
        launchNewSector(origin, sector, saveFile, null);
    }

    void launchNewSector(Sector origin, Sector sector, Fi saveFile, UpstreamCampaignLoadout loadout) throws IOException {
        if (saveFile.exists()) {
            throw new IOException("Campaign slot already exists: " + saveFile.path());
        }
        launchSector(origin, sector, saveFile, loadout, new WorldParams(), null);
    }

    void retryCampaignSector(SectorSlot slot, UpstreamCampaignLoadout loadout) throws IOException {
        Sector sector = slot.sector;
        if (!needsCampaignRetry(slot) || slot.origin == null || !slot.file.equals(sector.save.file)) {
            throw new IOException("Campaign slot is not eligible for retry: " + slot.file.name());
        }
        var params = new WorldParams();
        UpstreamCampaignRecovery recovery = null;
        if (!sector.planet.clearSectorOnLose && !sector.planet.campaignRules.clearSectorOnLose) {
            Vars.logic.reset();
            try {
                SaveIO.load(UpstreamCampaignSave.readableFile(slot.file), Vars.world.makeSectorContext(sector));
                app.runPosted();
                recovery = UpstreamCampaignRecovery.prepare(sector, params);
            } finally {
                // Logic's SaveLoadEvent provisionally marks the base active. Restore the
                // recorded loss until the replacement world is successfully saved.
                sector.info.hasCore = false;
                sector.saveInfo();
            }
        }
        launchSector(slot.origin, sector, slot.file, loadout, params, recovery);
    }

    private void launchSector(Sector origin, Sector sector, Fi saveFile, UpstreamCampaignLoadout loadout,
                              WorldParams params, UpstreamCampaignRecovery recovery) throws IOException {
        if (loadout != null && !origin.items().has(loadout.cost())) {
            throw new IOException("Insufficient launch resources in " + origin.name());
        }
        // Offline portion of v160.8 Control.playSector and Control.playNewSector.
        if (origin == sector) Vars.universe.clearLoadoutInfo();
        Vars.logic.reset(); // WorldReloader.begin() when neither a server nor a multiplayer client.
        if (loadout != null) {
            if (sector.allowLaunchLoadout()) Core.settings.put("maxresources", loadout.maximumCargo());
            Vars.universe.updateLoadout(loadout.core(), loadout.schematic());
            Vars.universe.updateLaunchResources(loadout.resources());
        }
        Vars.world.loadSector(sector, params);
        resetPlaytime(0);
        restorePlayerMetadata(null);
        if (loadout != null) {
            origin.removeItems(loadout.cost());
            Events.fire(new SectorLaunchLoadoutEvent(sector, origin, loadout.schematic()));
        }
        Vars.state.rules.sector = sector;
        sector.info.origin = origin;
        sector.info.destination = origin;
        sector.info.attempts++;
        if (sector.preset != null) sector.preset.quietUnlock();
        sector.planet.setLastSector(sector);
        if (recovery != null) recovery.run();

        app.runPosted();
        Events.fire(new RulesLoadEvent(Vars.state.rules));
        Vars.avoidance.init();
        Vars.logic.play();
        save(saveFile);
        Events.fire(new SectorLaunchEvent(sector));
        Events.fire(Trigger.newGame);
        Vars.state.set(State.playing);
        app.runPosted();
        reportLoadedWorld("campaign-new");
    }

    Gamemode startCustomGame(Map map, Gamemode requested) throws IOException {
        // Control.playMap wraps these operations in the stock desktop loading UI.
        var rules = map.applyRules(requested);
        Vars.logic.reset();
        Vars.world.loadMap(map, rules);
        if (Vars.state.map != map || Vars.world.isInvalidMap()) {
            throw new IOException("Could not load map " + map.file.name());
        }
        resetPlaytime(0);
        app.runPosted();
        map.spawns = Vars.spawner.countSpawns();
        map.teams.clear();
        for (var team : Vars.state.teams.getActive()) {
            if (team.hasCore()) map.teams.add(team.team.id);
        }
        Gamemode mode = requested;
        if (!mode.valid(map)) {
            // MapPlayDialog defaults to the first valid upstream mode.
            for (Gamemode candidate : Gamemode.all) {
                if (!candidate.hidden && candidate.valid(map)) {
                    mode = candidate;
                    rules = map.applyRules(mode);
                    break;
                }
            }
        }
        rules.retainContentFields(Vars.state.rules);
        Vars.state.rules = rules;
        rules.sector = null;
        restorePlayerMetadata(null);
        rules.editor = false;
        Events.fire(new RulesLoadEvent(rules));
        Vars.avoidance.init();
        Vars.logic.play();
        Events.fire(Trigger.newGame);
        app.runPosted();
        reportLoadedWorld("customgame");
        return mode;
    }

    private void reportLoadedWorld(String source) {
        ensurePlayerSpawn();
        var core = core();
        int copper = core == null ? 0 : core.items.get(Items.copper);
        System.out.println("UPSTREAM_SP_LOADED source=" + source
            + " map=" + (Vars.state.map == null ? "unknown" : Vars.state.map.name().replace(' ', '_'))
            + " size=" + Vars.world.width() + "x" + Vars.world.height()
            + " wave=" + Vars.state.wave + " copper=" + copper);
    }

    /** A discovered campaign slot and the upstream sector context decoded from its metadata. */
    static final class SectorSlot {
        final Fi file;
        final Sector sector;
        final long timestamp;
        final Sector origin;

        SectorSlot(Fi file, Sector sector, long timestamp) {
            this(file, sector, timestamp, null);
        }

        SectorSlot(Fi file, Sector sector, long timestamp, Sector origin) {
            this.file = file;
            this.sector = sector;
            this.timestamp = timestamp;
            this.origin = origin;
        }
    }

    List<SectorSlot> sectorSaves() {
        Fi directory = Vars.customMapDirectory;
        var result = new Seq<SectorSlot>();
        var found = new ObjectSet<Sector>();
        readSectorSaves(directory.child("saves"), result, found);
        readSectorSaves(directory, result, found);
        result.sort((a, b) -> {
            int order = Long.compare(b.timestamp, a.timestamp);
            return order == 0 ? a.file.name().compareTo(b.file.name()) : order;
        });
        var slots = new ArrayList<SectorSlot>(result.size);
        for (SectorSlot slot : result) slots.add(slot);
        return slots;
    }

    List<SectorSlot> campaignSlots(List<SectorSlot> saves) {
        return campaignSlots(saves, null);
    }

    List<SectorSlot> campaignSlots(List<SectorSlot> saves, Sector selectedOrigin) {
        var slots = new ArrayList<SectorSlot>(saves.size());
        for (SectorSlot slot : saves) {
            if (!needsCampaignRetry(slot)) {
                slots.add(slot);
            } else {
                Sector origin = landingOrigin(slot.sector, selectedOrigin);
                if (origin != null) slots.add(new SectorSlot(slot.file, slot.sector, slot.timestamp, origin));
            }
        }
        for (var planet : new Planet[]{Planets.serpulo, Planets.erekir}) {
            addLaunchSlot(slots, planet.getStartSector(), selectedOrigin);
        }
        for (var planet : new Planet[]{Planets.serpulo, Planets.erekir}) {
            for (var sector : planet.sectors) {
                if (sector != planet.getStartSector()) addLaunchSlot(slots, sector, selectedOrigin);
            }
        }
        return slots;
    }

    static boolean needsCampaignRetry(SectorSlot slot) {
        var sector = slot.sector;
        // Imported .msav files can lack their original settings. Keep the normal resume path
        // unless the campaign metadata actually records a lost base.
        return sector.save != null && !sector.info.hasCore
            && Core.settings.has(sector.planet.name + "-s-" + sector.id + "-info");
    }

    private void addLaunchSlot(List<SectorSlot> slots, Sector sector, Sector selectedOrigin) {
        if (sector.save != null) return;
        Sector origin = landingOrigin(sector, selectedOrigin);
        if (origin == null) return;

        var file = sectorFile(sector);
        if (!file.exists()) slots.add(new SectorSlot(file, sector, -1, origin));
    }

    private Sector landingOrigin(Sector sector, Sector selectedOrigin) {
        if (sector.isShielded()) return null;
        // Normal campaign view eligibility and launch-candidate check from v160.8 PlanetDialog.
        boolean starter = sector == sector.planet.getStartSector();
        var generator = sector.planet.generator;
        if (generator == null) return null;
        var preset = sector.preset;
        if (preset != null && (preset.generator.map == null
                || preset.requireUnlock && preset.locked() && preset.techNode != null)) return null;
        if (!starter && (preset == null || !preset.requireUnlock) && !generator.allowLanding(sector)) return null;
        Sector adjacentOrigin = selectedOrigin != null && selectedOrigin.planet == sector.planet ? selectedOrigin : null;
        Sector origin = generator.findLaunchCandidate(sector, adjacentOrigin);
        if (origin == null && adjacentOrigin != null && adjacentOrigin.hasBase() && !adjacentOrigin.isAttacked()) {
            origin = adjacentOrigin;
        }
        return origin == null && starter ? sector : origin;
    }

    static int lastSectorRow(List<SectorSlot> slots) {
        int planetMatch = 0;
        String lastSave = Core.settings.getString("last-sector-save", "");
        for (int i = 0; i < slots.size(); i++) {
            var slot = slots.get(i);
            String name = Core.settings.getString(slotNameKey(slot.file), slot.file.nameWithoutExtension());
            if (lastSave.equals(name)) return i;
            if (Core.settings.getInt(slot.sector.planet.name + "-last-sector", -1) == slot.sector.id) {
                planetMatch = i;
            }
        }
        return planetMatch;
    }

    private static String slotNameKey(Fi file) {
        return "save-" + file.nameWithoutExtension() + "-name";
    }

    private void readSectorSaves(Fi directory, Seq<SectorSlot> result, ObjectSet<Sector> found) {
        if (!directory.isDirectory()) return;
        // Scan only the sector paths accepted by the launcher. Saves.load() also remaps campaign files.
        for (Fi entry : directory.list()) {
            String name = entry.name();
            if (entry.isDirectory() || !name.startsWith("sector-") || !name.endsWith(".msav")) continue;
            Fi file = entry;
            if (name.endsWith("-backup.msav")) {
                String primary = name.substring(0, name.length() - "-backup.msav".length());
                if (!primary.endsWith(".msav")) continue;
                file = entry.sibling(primary);
                if (file.exists()) continue;
            }
            if (!SaveIO.isSaveValid(entry)) continue;
            SaveMeta meta;
            try {
                meta = SaveIO.getMeta(entry);
            } catch (ArcRuntimeException ignored) {
                continue;
            }
            Sector sector = meta.rules == null ? null : meta.rules.sector;
            if (sector == null) sector = sectorFor(file);
            var preset = Vars.content.sector(meta.tags.get("sectorPreset", ""));
            if (preset != null && preset.requireUnlock) sector = preset.sector;
            if (sector != null && found.add(sector)) {
                bindSectorSave(sector, file, meta);
                result.add(new SectorSlot(file, sector, meta.timestamp));
            }
        }
    }

    private Saves campaignSaves() {
        if (campaignSaves == null) {
            if (Core.assets == null) Core.assets = new AssetManager(Fi::new, false);
            campaignSaves = new Saves();
        }
        return campaignSaves;
    }

    Fi sectorFile(Sector sector) {
        return campaignSaves().getSectorFile(sector);
    }

    private void bindSectorSave(Sector sector, Fi file, SaveMeta meta) {
        if (sector.save == null || !sector.save.file.equals(file)) {
            var saves = campaignSaves();
            if (sector.save != null) saves.getSaveSlots().remove(sector.save);
            sector.save = saves.new SaveSlot(file);
            saves.getSaveSlots().add(sector.save);
        }
        sector.save.meta = meta;
    }

    /** Fallback for saves without a decoded sector: match planet and id from sector-&lt;planet&gt;-&lt;id&gt;.msav. */
    Sector sectorFor(Fi file) {
        return sectorFor(file, Vars.content.planets());
    }

    static Sector sectorFor(Fi file, Iterable<Planet> planets) {
        String name = file.name();
        if (!name.startsWith("sector-") || !name.endsWith(".msav")) return null;
        String stem = name.substring("sector-".length(), name.length() - ".msav".length());
        int dash = stem.lastIndexOf('-');
        if (dash <= 0 || dash + 1 >= stem.length()) return null;
        String planetName = stem.substring(0, dash);
        int id;
        try {
            id = Integer.parseInt(stem.substring(dash + 1));
        } catch (NumberFormatException ignored) {
            return null;
        }
        for (var planet : planets) {
            if (!planet.name.equals(planetName)) continue;
            for (var sector : planet.sectors) {
                if (sector.id == id) return sector;
            }
        }
        return null;
    }

    private void ensurePlayerObject() {
        if (Vars.player == null) {
            Vars.player = Player.create();
            Vars.player.team(Vars.state.rules.defaultTeam);
            Vars.player.name = "Min3DStry";
            Vars.player.add();
        } else if (!Vars.player.isAdded()) {
            Vars.player.add();
        }
    }

    private void restorePlayerMetadata(StringMap tags) {
        ensurePlayerObject();
        Vars.player.clearUnit();
        Vars.player.reset();
        savedControlGroups = "null";
        savedControlledType = null;
        if (tags == null) return;
        var position = new Vec2().tryFromString(tags.get("viewpos", "(0,0)"));
        if (Float.isFinite(position.x) && Float.isFinite(position.y)) Vars.player.set(position);
        Team team = Team.get(tags.getInt("playerteam", Vars.state.rules.defaultTeam.id));
        if (team != Team.derelict) Vars.player.team(team);
        savedControlledType = Vars.content.getByName(ContentType.unit, tags.get("controlledType", "<none>"));
        savedControlGroups = tags.get("controlGroups", "null");
    }

    Unit ensurePlayerSpawn() {
        ensurePlayerObject();
        var unit = Vars.player.unit();
        if (unit != null && !Vars.player.dead() && unit.isAdded()) {
            return unit;
        }
        var type = savedControlledType;
        savedControlledType = null;
        if (type != null && type.playerControllable) {
            // Offline InputHandler.checkUnit() restores the nearest unit of the saved type.
            var player = Vars.player;
            var saved = Units.closest(player.team(), player.x, player.y, other -> !other.isPlayer()
                && other.type == type && !other.dead);
            if (saved == null && type == UnitTypes.block
                    && Vars.world.buildWorld(player.x, player.y) instanceof ControlBlock control && control.canControl()) {
                saved = control.unit();
            }
            if (saved != null) {
                saved.controller(player);
                return player.unit();
            }
        }
        for (var existing : Groups.unit) {
            if (existing.team == Vars.player.team() && !existing.dead && existing.isAdded()
                    && existing.spawnedByCore()) {
                existing.controller(Vars.player);
                Vars.player.unit(existing);
                return existing;
            }
        }
        var core = Vars.player.bestCore();
        if (core != null) {
            CoreBlock.playerSpawn(core.tile, Vars.player);
        }
        return Vars.player.unit();
    }

    CoreBuild core() {
        return (Vars.player == null ? Vars.state.rules.defaultTeam : Vars.player.team()).core();
    }

    void stepSimulation(float moveX, float moveY) {
        if (Vars.state.isPaused()) return;
        float prevDelta = deltaSeconds;
        deltaSeconds = FIXED_STEP_SECONDS;
        try {
            var unit = ensurePlayerSpawn();
                if (unit != null && !(unit instanceof BlockUnitc)) {
                tmpMove.set(moveX, moveY).limit(1f).scl(unit.speed());
                boolean faceAim = Vars.player.shooting && unit.type().omniMovement && unit.type().faceTarget;
                unit.lookAt(faceAim ? unit.angleTo(unit.aimX(), unit.aimY()) : unit.prefRotation());
                unit.movePref(tmpMove);
                if (unit.mineTile != null && !unit.validMine(unit.mineTile)) {
                    unit.mineTile = null;
                }
                unit.validatePlans();
            }
            Vars.logic.update();
            app.runPosted();
        } finally {
            deltaSeconds = prevDelta;
        }
    }

    boolean togglePause() {
        boolean paused = Vars.state.isPaused();
        if (Vars.state.rules.pauseDisabled) {
            System.out.println("UPSTREAM_SP_PAUSE disabled=true");
            return paused;
        }
        Vars.state.set(paused ? State.playing : State.paused);
        System.out.println("UPSTREAM_SP_PAUSE paused=" + !paused);
        return !paused;
    }

    int triggerWave() {
        Vars.logic.runWave();
        app.runPosted();
        return Vars.state.wave;
    }

    boolean saveCampaignDefeat(Fi slot) throws IOException {
        var sector = Vars.state.rules.sector;
        if (sector == null || !Vars.state.gameOver || Vars.net.client()) return false;

        // Offline portion of v160.8 Control's GameOverEvent handlers.
        Vars.state.stats.wavesLasted = Vars.state.wave;
        save(slot);
        System.out.println("UPSTREAM_CAMPAIGN_DEFEAT sector=" + sector.id + " wave=" + Vars.state.wave);
        return true;
    }

    private void resetPlaytime(long savedMillis) {
        playtimeMillis = Math.max(0, savedMillis);
        playtimeRemainderNanos = 0;
    }

    void beginPlaytimeInterval(long now) {
        playtimeFrameNanos = now;
    }

    void updatePlaytime(long now) {
        long elapsed = Math.max(0, now - playtimeFrameNanos);
        playtimeFrameNanos = now;
        long partial = playtimeRemainderNanos + elapsed % 1_000_000L;
        long millis = elapsed / 1_000_000L + partial / 1_000_000L;
        playtimeRemainderNanos = partial % 1_000_000L;
        playtimeMillis = Math.min(playtimeMillis, Long.MAX_VALUE - millis) + millis;
    }

    long save(Fi saveFile) throws IOException {
        if (saveFile.parent() != null) {
            saveFile.parent().mkdirs();
        }
        if (Vars.state.rules.sector != null) {
            UpstreamSectorCapture.persist(Vars.state.rules.sector);
        }
        var options = new SaveOptions();
        var type = Vars.player == null || Vars.player.dead() ? savedControlledType : Vars.player.unit().type;
        options.extraTags = StringMap.of("playtime", playtimeMillis, "controlGroups", savedControlGroups,
            "controlledType", type == null ? "null" : type.name);
        SaveMeta written;
        if (Vars.state.rules.sector != null) {
            written = UpstreamCampaignSave.write(saveFile, options);
        } else {
            SaveIO.save(saveFile, options);
            try (var input = SaveIO.getStream(saveFile)) {
                written = SaveIO.getMeta(input);
            } catch (RuntimeException failure) {
                throw new IOException("Written offline save failed validation: " + saveFile.path(), failure);
            }
        }
        if (Vars.state.rules.sector != null) {
            bindSectorSave(Vars.state.rules.sector, saveFile, written);
            String index = saveFile.nameWithoutExtension();
            String key = slotNameKey(saveFile);
            if (!Core.settings.has(key)) Core.settings.put(key, index);
            Core.settings.put("last-sector-save", Core.settings.getString(key));
        }
        Core.settings.autosave();
        long bytes = saveFile.length();
        var core = core();
        int copper = core == null ? 0 : core.items.get(Items.copper);
        System.out.println("UPSTREAM_SP_SAVED path=" + saveFile.path() + " bytes=" + bytes
            + " wave=" + Vars.state.wave + " copper=" + copper);
        return bytes;
    }

    void shutdown() {
        Events.fire(new ResetEvent());
        app.runPosted();
    }

    private static long waitForGroundField(UpstreamWorldLoadProbe.ProbeApplication app, Team enemy)
            throws InterruptedException {
        var field = Vars.pathfinder.getField(enemy, Pathfinder.costGround, Pathfinder.fieldCore);
        for (int poll = 0; poll < 250 && !field.hasCompleteWeights(); poll++) {
            app.runPosted();
            Thread.sleep(16);
        }
        if (!field.hasCompleteWeights()) {
            throw new IllegalStateException("Ground Zero flowfield did not finish");
        }
        app.runPosted();
        long hash = 0xcbf29ce484222325L;
        var weights = field.completeWeights;
        for (int index = 0; index < weights.length; index++) {
            int weight = weights.get(index);
            hash = (hash ^ Integer.toUnsignedLong(weight)) * 0x100000001b3L;
        }
        return hash;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Fi mapFile = new Fi(args.length >= 1 ? args[0] : "/3ds/min3dstry/groundZero.msav");
        Fi saveFile = new Fi(args.length >= 2 ? args[1] : mapFile.sibling("sp-sim-save.msav").path());
        UpstreamSinglePlayerSession session = null;
        try {
            saveFile.delete();
            session = create(saveFile.parent() != null ? saveFile.parent() : mapFile.parent(), "en");
            session.start(mapFile, null, null);
            var currentRules = mindustry.io.JsonIO.read(mindustry.game.Rules.class, "{\"modeName\":\"current\"}");
            var legacyRules = mindustry.io.JsonIO.read(new mindustry.game.Rules(), "{\"modeName\":\"io.anuke.legacy\"}");
            if (!"current".equals(currentRules.modeName) || !"legacy".equals(legacyRules.modeName)) {
                throw new IllegalStateException("Stock save rules JSON compatibility changed");
            }

            var serpulo15 = sectorFor(new Fi("sector-serpulo-15.msav"), Vars.content.planets());
            if (serpulo15 == null || serpulo15.id != 15 || !serpulo15.planet.name.equals("serpulo")) {
                throw new IllegalStateException("Serpulo sector 15 did not resolve: " + serpulo15);
            }
            var erekirPlanet = Vars.content.planet("erekir");
            if (erekirPlanet == null || erekirPlanet.sectors.isEmpty()) {
                throw new IllegalStateException("Erekir sectors did not initialize");
            }
            var erekirSector = erekirPlanet.sectors.first();
            var erekirByName = sectorFor(new Fi("sector-erekir-" + erekirSector.id + ".msav"),
                Vars.content.planets());
            if (erekirByName != erekirSector) {
                throw new IllegalStateException("sector-erekir-" + erekirSector.id
                    + ".msav resolved to " + erekirByName + " instead of " + erekirSector);
            }
            System.out.println("UPSTREAM_SP_SECTOR_RESOLVE serpulo=15 erekir=" + erekirSector.id
                + " planet=ok");

            if (Vars.world.width() != 256 || Vars.world.height() != 256) {
                throw new IllegalStateException("Unexpected Ground Zero dimensions: "
                    + Vars.world.width() + "x" + Vars.world.height());
            }
            var core = session.core();
            if (core == null || core.block != Blocks.coreShard
                    || core.tile.x != 129 || core.tile.y != 55) {
                throw new IllegalStateException("Unexpected Ground Zero core location");
            }
            var unit = Vars.player.unit();
            if (unit == null || unit.type != UnitTypes.alpha) {
                throw new IllegalStateException("Alpha player unit did not spawn");
            }

            byte[] minimap = UpstreamMinimap.sample(32);
            if (minimap == null || minimap.length != 32 * 32 * 4) {
                throw new IllegalStateException("Minimap sampler returned an invalid grid");
            }
            int marker = UpstreamMinimap.playerIndex(32);
            if (marker < 0) {
                throw new IllegalStateException("Minimap sampler has no player marker");
            }
            int markerOffset = marker * 4;
            if ((minimap[markerOffset] & 0xff) != 255
                    || (minimap[markerOffset + 1] & 0xff) != 255
                    || (minimap[markerOffset + 2] & 0xff) != 255) {
                throw new IllegalStateException("Minimap player marker is not white");
            }
            System.out.println("UPSTREAM_MINIMAP sample=32 hash="
                + Long.toUnsignedString(UpstreamMinimap.hash(minimap), 16)
                + " cell=" + (marker % 32) + "," + (marker / 32));
            int px = marker % 32;
            int py = marker / 32;
            int tipX = (int)Math.round(px + 0.5 + Math.cos(Math.toRadians(unit.rotation())) * 2.5);
            int tipY = (int)Math.round(py + 0.5 + Math.sin(Math.toRadians(unit.rotation())) * 2.5);
            boolean arrowChecked = false;
            if (tipX < 0 || tipY < 0 || tipX >= 32 || tipY >= 32) {
                throw new IllegalStateException("Minimap direction tip falls outside the grid");
            }
            arrowChecked = true;
            if (tipX == px && tipY == py) {
                throw new IllegalStateException("Minimap direction tip overlaps the base");
            }
            int tipOffset = (tipY * 32 + tipX) * 4;
            if ((minimap[tipOffset] & 0xff) != 255
                    || (minimap[tipOffset + 1] & 0xff) != 255
                    || (minimap[tipOffset + 2] & 0xff) != 255) {
                throw new IllegalStateException("Minimap player direction tip is not white");
            }
            var localCore = Vars.player.team().core();
            int coreChecked = 0;
            if (localCore != null) {
                byte[] coreMap = UpstreamMinimap.sample(64, false);
                int coreCell = UpstreamMinimap.cellIndex(64, Vars.world.width(), Vars.world.height(),
                    localCore.tileX(), localCore.tileY());
                int playerCell64 = UpstreamMinimap.playerIndex(64);
                int coreOffset = coreCell * 4;
                var teamColor = localCore.team.color;
                if ((coreMap[coreOffset] & 0xff) != Math.round(teamColor.r * 255f)
                        || (coreMap[coreOffset + 1] & 0xff) != Math.round(teamColor.g * 255f)
                        || (coreMap[coreOffset + 2] & 0xff) != Math.round(teamColor.b * 255f)) {
                    throw new IllegalStateException("Minimap core marker color mismatch");
                }
                coreChecked = 1;
            }
            System.out.println("UPSTREAM_MINIMAP_MARKERS arrow=" + (arrowChecked ? 1 : 0) + " core=" + coreChecked + " ok");

            // 1. Movement check along Y-axis.
            float startY = unit.y();
            for (int tick = 1; tick <= 30; tick++) {
                session.stepSimulation(0f, -1f);
            }
            if (!(unit.y() < startY - 10f)) {
                throw new IllegalStateException("Alpha movement failed: startY=" + startY + " endY=" + unit.y());
            }

            // 2. Mine copper from nearby ore tile (129, 49) into Core: Shard.
            Tile ore = Vars.world.tile(129, 49);
            if (ore == null || ore.overlay() != Blocks.oreCopper || ore.block() != Blocks.air) {
                throw new IllegalStateException("Expected copper ore at Ground Zero (129, 49)");
            }
            unit.set(ore.worldx(), ore.worldy());
            unit.mineTile = ore;
            int mineTicks = 0;
            for (int tick = 1; tick <= 480; tick++) {
                session.stepSimulation(0f, 0f);
                if (core.items.get(Items.copper) >= 20) {
                    mineTicks = tick;
                    break;
                }
            }
            unit.mineTile = null;
            int minedCopper = core.items.get(Items.copper);
            if (mineTicks == 0 || minedCopper < 20) {
                throw new IllegalStateException("Copper mining failed: copper=" + minedCopper);
            }

            // 3. Build conveyor @(131,55) and copper-wall @(131,56) via BuildPlan.
            unit.set(core.x, core.y);
            int convX = 131, convY = 55;
            int wallX = 131, wallY = 56;
            unit.addBuild(new BuildPlan(convX, convY, 0, Blocks.conveyor));
            unit.addBuild(new BuildPlan(wallX, wallY, 0, Blocks.copperWall));
            boolean built = false;
            for (int tick = 1; tick <= 120; tick++) {
                session.stepSimulation(0f, 0f);
                if (Vars.world.tile(convX, convY).block() == Blocks.conveyor
                        && Vars.world.tile(wallX, wallY).block() == Blocks.copperWall) {
                    built = true;
                    break;
                }
            }
            if (!built || core.items.get(Items.copper) != minedCopper - 7) {
                throw new IllegalStateException("Build plan failed: copper=" + core.items.get(Items.copper));
            }

            // 4. Deconstruct copper-wall @(131,56).
            unit.addBuild(new BuildPlan(wallX, wallY));
            boolean removed = false;
            for (int tick = 1; tick <= 120; tick++) {
                session.stepSimulation(0f, 0f);
                if (Vars.world.tile(wallX, wallY).block() == Blocks.air) {
                    removed = true;
                    break;
                }
            }
            if (!removed) {
                throw new IllegalStateException("Deconstruct failed at (131, 56)");
            }

            // 5. Pause & resume check.
            long pausedBefore = Vars.state.updateId;
            if (!session.togglePause()) {
                throw new IllegalStateException("Failed to enter pause state");
            }
            for (int tick = 1; tick <= 30; tick++) {
                session.stepSimulation(1f, 0f);
            }
            if (Vars.state.updateId != pausedBefore) {
                throw new IllegalStateException("Simulation advanced while paused");
            }
            if (session.togglePause()) {
                throw new IllegalStateException("Failed to resume from pause");
            }
            Vars.state.rules.pauseDisabled = true;
            if (session.togglePause() || Vars.state.isPaused()) {
                throw new IllegalStateException("A world with the stock pause-disabled rule paused");
            }
            session.stepSimulation(0f, 0f);
            if (Vars.state.updateId <= pausedBefore) {
                throw new IllegalStateException("The simulation froze with the pause-disabled rule");
            }
            Vars.state.rules.pauseDisabled = false;
            System.out.println("UPSTREAM_SP_NO_PAUSE simulation=running");

            // 6. Wait for deterministic flowfield, trigger Wave 1, verify Dagger advance.
            waitForGroundField(session.application(), Vars.state.rules.waveTeam);
            session.triggerWave();
            Unit enemy = Groups.unit.find(u -> u.team == Vars.state.rules.waveTeam && !u.dead);
            if (enemy == null) {
                throw new IllegalStateException("Ground Zero Wave 1 did not spawn an enemy");
            }
            float enemyStartY = enemy.y();
            for (int tick = 1; tick <= 180; tick++) {
                session.stepSimulation(0f, 0f);
            }
            if (Vars.state.wave != 2 || !(enemy.y() < enemyStartY)) {
                throw new IllegalStateException("Enemy wave unit did not advance: wave=" + Vars.state.wave
                    + " y=" + enemyStartY + "->" + enemy.y());
            }
            byte[] unitMap = UpstreamMinimap.sample(64);
            int enemyCell = UpstreamMinimap.cellIndex(64, Vars.world.width(), Vars.world.height(),
                enemy.tileX(), enemy.tileY());
            int playerCell = UpstreamMinimap.playerIndex(64);
            if (enemyCell < 0 || enemyCell == playerCell) {
                throw new IllegalStateException("Enemy minimap cell is invalid");
            }
            var enemyColor = enemy.team.color;
            int enemyOffset = enemyCell * 4;
            if ((unitMap[enemyOffset] & 0xff) != Math.round(enemyColor.r * 255f)
                    || (unitMap[enemyOffset + 1] & 0xff) != Math.round(enemyColor.g * 255f)
                    || (unitMap[enemyOffset + 2] & 0xff) != Math.round(enemyColor.b * 255f)) {
                throw new IllegalStateException("Enemy minimap blip color mismatch");
            }
            System.out.println("UPSTREAM_MINIMAP_UNITS enemy=1 ok");

            // 7. Save .msav, reset world, reload, and verify exact restored state.
            int preReloadCopper = session.core().items.get(Items.copper);
            int preReloadEnemies = Groups.unit.count(u -> u.team == Vars.state.rules.waveTeam && !u.dead);
            session.save(saveFile);
            Vars.logic.reset();
            session.application().runPosted();
            session.start(mapFile, saveFile, null);
            if (Vars.world.tile(convX, convY).block() != Blocks.conveyor
                    || Vars.world.tile(wallX, wallY).block() != Blocks.air
                    || Vars.state.wave != 2
                    || session.core() == null
                    || session.core().items.get(Items.copper) != preReloadCopper
                    || Groups.unit.count(u -> u.team == Vars.state.rules.waveTeam && !u.dead) != preReloadEnemies) {
                throw new IllegalStateException("Save/reload mismatch after Ground Zero .msav load");
            }
            String fingerprint = UpstreamWorldLoadProbe.reportTerrain(false);
            System.out.println("UPSTREAM_SP_SIM_PASS world=256x256 mine=" + mineTicks + ":" + minedCopper
                + " build=conveyor+wall deconstruct=air pause=" + pausedBefore
                + " wave=2 enemies=" + preReloadEnemies + " copper=" + preReloadCopper
                + " reload=ok fingerprint=" + fingerprint);
        } catch (Throwable failure) {
            System.out.println("UPSTREAM_SP_SIM_ERROR " + failure.getClass().getName() + ": " + failure.getMessage());
            failure.printStackTrace(System.out);
            System.out.flush();
            throw failure;
        } finally {
            saveFile.delete();
            if (session != null) {
                session.shutdown();
            }
        }
    }
}
