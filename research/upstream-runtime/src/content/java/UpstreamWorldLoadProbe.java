import arc.Application;
import arc.ApplicationListener;
import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.math.Mathf;
import arc.struct.Seq;
import java.io.IOException;
import java.util.ArrayDeque;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.core.Logic;
import mindustry.entities.EntityCollisions;
import mindustry.game.MapObjectives.PointMarker;
import mindustry.gen.Groups;
import mindustry.io.MapIO;
import mindustry.io.SaveIO;
import mindustry.maps.Maps;
import mindustry.net.Net;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

public final class UpstreamWorldLoadProbe {
    public static void main(String[] args) throws IOException {
        load(args);
    }

    static ProbeApplication load(String[] args) throws IOException {
        return load(args, () -> {});
    }

    static ProbeApplication load(String[] args, Runnable beforeMap) throws IOException {
        Fi file = new Fi(args.length == 0 ? "/3ds/min3dstry/glacier.msav" : args[0]);
        var app = initialize(file.parent());
        beforeMap.run();
        var map = MapIO.createMap(file, false);
        // Glacier uses randomized upstream map filters. A fixed seed allows a
        // direct comparison of the resulting terrain on each platform.
        Mathf.rand.setSeed(160_008L);
        Vars.world.loadMap(map);
        app.runPosted();
        if (Vars.state.map != map) {
            throw new IllegalStateException("Glacier did not load");
        }
        verifyWorld();
        if (args.length > 1) {
            loadCurrentSave(app, new Fi(args[1]));
        }
        return app;
    }

    static ProbeApplication initialize(Fi mapDirectory) {
        return initialize(mapDirectory, new Settings());
    }

    static ProbeApplication initialize(Fi mapDirectory, Settings settings) {
        return initialize(mapDirectory, settings, false);
    }

    static ProbeApplication initialize(Fi mapDirectory, Settings settings, boolean loadPresetMaps) {
        var app = new ProbeApplication();
        Core.app = app;
        Core.settings = settings;
        Vars.headless = true;
        Vars.state = new GameState();
        // SectorPreset creates its FileMapGenerator while loading base content.
        Vars.maps = loadPresetMaps ? new Maps() : null;
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        Vars.emptyTile = new Tile(Short.MAX_VALUE - 20, Short.MAX_VALUE - 20);
        Groups.init();
        Vars.world = new UpstreamWorld();
        Vars.collisions = new EntityCollisions();
        Vars.net = new Net(null);
        Vars.logic = new Logic() {
            @Override
            public void reset() {
                long retiredTiles = (long)Vars.world.width() * Vars.world.height();
                super.reset();
                // A scratch tile can keep its building and the previous power network alive.
                mindustry.gen.UpstreamBuildingScratch.clear();
                mindustry.world.UpstreamBlockScratch.clear();
                if (retiredTiles >= 360_000) {
                    // Return the retired world's managed space before the replacement decode
                    // starts interleaving new tiles and buildings with remaining references.
                    System.gc();
                    long live = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                    System.out.println("UPSTREAM_DENSE_RESET collected=true tiles=" + retiredTiles + " used=" + live);
                }
            }
        };

        Vars.customMapDirectory = mapDirectory;
        if (!loadPresetMaps) Vars.maps = new Maps();
        return app;
    }

    static void loadCurrentSave(ProbeApplication app, Fi file) throws IOException {
        try (var input = SaveIO.getStream(file)) {
            SaveIO.readHeader(input);
            if (input.readInt() != 13) {
                throw new IllegalStateException("Expected a v13 save");
            }
        }
        SaveIO.load(file);
        app.runPosted();
        verifyWorld();
        if (!(Vars.state.markers.get(37) instanceof PointMarker marker)
                || marker.pos.x != 80 || marker.pos.y != 152) {
            throw new IllegalStateException("Current-format marker differs after reload");
        }
        reportTileState(true);
        reportSaveState(true);
        System.out.println("UPSTREAM_SAVE version=13 marker=ok");
    }

    /**
     * Hashes the decoded building fields that the native Min3DStry reader
     * exposes as SECTOR_PARITY. Mirrors MSAV_HASH_SEED and mix_hash in
     * src/platform/msav_world.c so both loaders must agree on the same save.
     */
    static long reportSaveState(boolean verifyFixture) {
        final long seed = 1469598103934665603L;
        long aggregate = seed;
        long firstBuilding = 0L;
        long secondBuilding = 0L;
        int buildings = 0;
        for (var tile : Vars.world.tiles) {
            var build = tile.build;
            if (build == null || build.tile != tile) {
                continue;
            }
            buildings++;

            long hash = seed;
            hash = mix(hash, tile.x);
            hash = mix(hash, tile.y);
            hash = mix(hash, tile.block().id);
            hash = mix(hash, build.team.id);
            hash = mix(hash, build.rotation);
            hash = mix(hash, Float.floatToIntBits(build.health));
            hash = mix(hash, build.enabled ? 1 : 0);

            int itemCount = 0;
            int itemTotal = 0;
            long itemHash = seed;
            int firstItem = -1;
            int firstItemAmount = 0;
            for (var item : Vars.content.items()) {
                int amount = build.items == null ? 0 : build.items.get(item);
                if (amount > 0) {
                    itemCount++;
                    itemTotal += amount;
                    itemHash = mix(itemHash, item.id);
                    itemHash = mix(itemHash, amount);
                    if (firstItem < 0) {
                        firstItem = item.id;
                        firstItemAmount = amount;
                    }
                }
            }
            hash = mix(hash, itemCount);
            hash = mix(hash, itemTotal);
            hash = mix64(hash, itemHash);

            int links = build.power == null ? 0 : build.power.links.size;
            long linkHash = seed;
            for (int i = 0; i < links; i++) {
                linkHash = mix(linkHash, build.power.links.get(i));
            }
            hash = mix(hash, links);
            hash = mix64(hash, linkHash);
            float status = build.power == null ? 0f : build.power.status;
            hash = mix(hash, Float.floatToIntBits(status));

            int liquidCount = 0;
            long liquidHash = seed;
            int firstLiquid = -1;
            float firstLiquidAmount = 0f;
            for (var liquid : Vars.content.liquids()) {
                float amount = build.liquids == null ? 0f : build.liquids.get(liquid);
                if (amount > 0f) {
                    liquidCount++;
                    liquidHash = mix(liquidHash, liquid.id);
                    liquidHash = mix(liquidHash, Float.floatToIntBits(amount));
                    if (firstLiquid < 0) {
                        firstLiquid = liquid.id;
                        firstLiquidAmount = amount;
                    }
                }
            }
            hash = mix(hash, liquidCount);
            hash = mix64(hash, liquidHash);

            if (buildings == 1) {
                firstBuilding = hash;
            } else if (buildings == 2) {
                secondBuilding = hash;
            }
            aggregate = mix64(aggregate, hash);
            System.out.println("UPSTREAM_SAVE_BUILDING x=" + tile.x
                + " y=" + tile.y + " block=" + tile.block().id
                + " team=" + build.team.id + " rot=" + build.rotation
                + " health=" + (int)build.health
                + " enabled=" + (build.enabled ? 1 : 0)
                + " items=" + itemCount + " item0=" + firstItem + ":"
                + firstItemAmount + " links=" + links + " status=" + status
                + " liquids=" + liquidCount + " liquid0="
                + (firstLiquid < 0 ? 0 : firstLiquid) + ":" + firstLiquidAmount
                + " hash=" + hex16(hash));
        }
        System.out.println("UPSTREAM_SAVE_STATE buildings=" + buildings
            + " hash=" + hex16(aggregate));
        if (verifyFixture && (buildings != 2
                || firstBuilding != 0xb529451197a6e504L
                || secondBuilding != 0xebc4778e833eb8e5L
                || aggregate != 0x3a375fd4f81c5680L)) {
            throw new IllegalStateException("Save state parity differs: buildings="
                + buildings + " first=" + hex16(firstBuilding)
                + " second=" + hex16(secondBuilding)
                + " aggregate=" + hex16(aggregate));
        }
        return aggregate;
    }

    /**
     * Mirrors the native M3dsTileState byte layout and FNV-1a hash in
     * src/platform/msav_world.c. Used to prove the upstream loader sees the
     * same floor/overlay/block/position/flags/building-index rows.
     */
    static long reportTileState(boolean verifyFixture) {
        final long seed = 1469598103934665603L;
        long hash = seed;
        int tiles = 0;
        int buildings = 0;
        byte[] row = new byte[24];
        for (var tile : Vars.world.tiles) {
            if (tile == null) {
                throw new IllegalStateException("Missing tile");
            }
            boolean origin = tile.build != null && tile.build.tile == tile;
            int flags = origin ? 4 : 0;
            int buildingIndex = origin ? buildings : -1;
            if (origin) {
                buildings++;
            }
            put16(row, 0, tile.floor().id);
            put16(row, 2, tile.overlay().id);
            put16(row, 4, tile.block().id);
            put32(row, 8, tile.x);
            put32(row, 12, tile.y);
            row[16] = (byte)flags;
            row[17] = 0;
            row[18] = 0;
            row[19] = 0;
            put32(row, 20, buildingIndex);
            for (byte value : row) {
                hash = (hash ^ (value & 0xff)) * 0x100000001b3L;
            }
            tiles++;
        }
        System.out.println("UPSTREAM_TILE_STATE tiles=" + tiles
            + " buildings=" + buildings + " hash=" + hex16(hash));
        if (verifyFixture && (tiles != 37500 || buildings != 2
                || hash != 0xe35397041df8e5feL)) {
            throw new IllegalStateException("Tile state parity differs: tiles="
                + tiles + " buildings=" + buildings + " hash=" + hex16(hash));
        }
        return hash;
    }

    private static void put16(byte[] out, int offset, int value) {
        out[offset] = (byte)value;
        out[offset + 1] = (byte)(value >>> 8);
    }

    private static void put32(byte[] out, int offset, int value) {
        out[offset] = (byte)value;
        out[offset + 1] = (byte)(value >>> 8);
        out[offset + 2] = (byte)(value >>> 16);
        out[offset + 3] = (byte)(value >>> 24);
    }

    private static String hex16(long value) {
        var out = new StringBuilder(16);
        for (int shift = 60; shift >= 0; shift -= 4) {
            out.append(Character.forDigit((int)(value >>> shift) & 15, 16));
        }
        return out.toString();
    }

    private static long mix64(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }

    private static void verifyWorld() {
        if (Vars.world.width() != 150 || Vars.world.height() != 250) {
            throw new IllegalStateException("Glacier dimensions differ");
        }
        var stats = Vars.state.stats;
        var rules = Vars.state.rules;
        if (stats.buildingsBuilt != 9 || stats.buildingsDeconstructed != 9
                || rules.enemyCoreBuildRadius != 832 || rules.spawns.isEmpty()
                || rules.spawns.first().type != UnitTypes.dagger || rules.spawns.first().end != 10) {
            throw new IllegalStateException("Glacier save metadata differs");
        }
        String fingerprint = reportTerrain(true);
        if (!fingerprint.equals("2e7b9f883994de6c")) {
            System.out.println("UPSTREAM_WORLD_MISMATCH " + fingerprint);
            throw new IllegalStateException("Glacier content fingerprint differs: " + fingerprint);
        }
        System.out.println("UPSTREAM_WORLD_FINGERPRINT " + fingerprint);
    }

    static String reportTerrain(boolean verifyFixture) {
        int tiles = 0;
        int buildings = 0;
        long fingerprint = 0xcbf29ce484222325L;
        for (var tile : Vars.world.tiles) {
            if (tile == null) {
                throw new IllegalStateException("Missing tile");
            }
            tiles++;
            fingerprint = mix(fingerprint, tile.floor().id);
            fingerprint = mix(fingerprint, tile.overlay().id);
            fingerprint = mix(fingerprint, tile.block().id);
            fingerprint = mix(fingerprint, tile.build == null ? -1 : tile.build.pos());
            if (tile.build != null && tile.build.tile == tile) {
                if (verifyFixture && (tile.x != 81 || (tile.y != 26 && tile.y != 224)
                        || !tile.block().name.equals("core-nucleus") || !(tile.build instanceof CoreBuild))) {
                    throw new IllegalStateException("Unexpected Glacier building origin or type");
                }
                buildings++;
                fingerprint = mix(fingerprint, tile.build.team.id);
                fingerprint = mix(fingerprint, tile.build.rotation);
                System.out.println("UPSTREAM_WORLD_BUILDING " + tile.x + " " + tile.y + " "
                    + tile.block().name + " " + tile.build.getClass().getName());
            }
        }
        if (verifyFixture && (tiles != 37_500 || buildings != 2)) {
            throw new IllegalStateException("Glacier terrain or buildings differ");
        }
        var observed = new StringBuilder(16);
        for (int shift = 60; shift >= 0; shift -= 4) {
            observed.append(Character.forDigit((int)(fingerprint >>> shift) & 15, 16));
        }
        System.out.println("UPSTREAM_WORLD width=" + Vars.world.width() + " height=" + Vars.world.height()
            + " tiles=" + tiles + " buildings=" + buildings);
        return observed.toString();
    }

    private static long mix(long hash, int value) {
        return (hash ^ Integer.toUnsignedLong(value)) * 0x100000001b3L;
    }

    static final class ProbeApplication implements Application {
        private final Seq<ApplicationListener> listeners = new Seq<>();
        private final ArrayDeque<Runnable> posted = new ArrayDeque<>();

        @Override public Seq<ApplicationListener> getListeners() { return listeners; }
        @Override public ApplicationType getType() { return ApplicationType.headless; }
        @Override public String getClipboardText() { return ""; }
        @Override public void setClipboardText(String text) {}
        @Override public void post(Runnable job) { posted.add(job); }
        @Override public void exit() {}

        void runPosted() {
            while (!posted.isEmpty()) {
                posted.remove().run();
            }
        }
    }
}
