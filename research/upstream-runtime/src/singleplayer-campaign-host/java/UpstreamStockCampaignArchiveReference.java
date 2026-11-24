import arc.Core;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.I18NBundle;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Planets;
import mindustry.content.SectorPresets;
import mindustry.game.Team;
import mindustry.game.Teams;
import mindustry.gen.Fire;
import mindustry.gen.Puddle;
import mindustry.io.MapIO;
import mindustry.io.SaveIO;
import mindustry.type.Sector;
import mindustry.world.Tiles;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.meta.BlockFlag;

public final class UpstreamStockCampaignArchiveReference {
    private static void verifyOreIndex(Tiles tiles) {
        // Pinned findClosestOre/findClosestWallOre loop over all qx, then all qy.
        // Each nonempty quadrant contributes its first insertion as a distance candidate.
        int width = (tiles.width + 19) / 20, height = (tiles.height + 19) / 20;
        int items = Vars.content.items().size;
        var floor = new mindustry.world.Tile[items][width][height];
        var wall = new mindustry.world.Tile[items][width][height];
        for (var tile : tiles) {
            boolean open = tile.block() == Blocks.air;
            var drop = open ? tile.drop() : tile.wallDrop();
            if (drop == null || drop.id >= items) continue;
            var first = open ? floor : wall;
            int qx = tile.x / 20, qy = tile.y / 20;
            if (first[drop.id][qx][qy] == null) first[drop.id][qx][qy] = tile;
        }
        for (var item : Vars.content.items()) {
            for (int coordinate : new int[]{0, 100, 300, 599}) {
                float point = coordinate * Vars.tilesize;
                if (Vars.indexer.findClosestOre(point, point, item) != nearestQuadrantOre(floor[item.id], point)
                        || Vars.indexer.findClosestWallOre(point, point, item) != nearestQuadrantOre(wall[item.id], point)) {
                    throw new AssertionError("Stock floor or wall ore selection changed for " + item.name);
                }
            }
        }
        System.out.println("UPSTREAM_QUADRANT_ORE_INDEX floor=stock wall=stock");
    }

    private static mindustry.world.Tile nearestQuadrantOre(mindustry.world.Tile[][] quadrants, float point) {
        mindustry.world.Tile closest = null;
        float minDistance = Float.POSITIVE_INFINITY;
        for (var column : quadrants) {
            for (var tile : column) {
                if (tile == null) continue;
                float distance = arc.math.Mathf.dst2(point, point, tile.worldx(), tile.worldy());
                if (distance < minDistance) {
                    closest = tile;
                    minDistance = distance;
                }
            }
        }
        return closest;
    }

    private static void verifyTeamStats() {
        var teams = new Teams();
        var unused = Team.get(254);
        var waveTeam = Team.get(255);
        var rules = Vars.state.rules;
        boolean previousWaves = rules.waves;
        var previousWaveTeam = rules.waveTeam;
        try {
            rules.waveTeam = waveTeam;
            rules.waves = false;
            teams.updateTeamStats();
            if (teams.getOrNull(unused) != null || teams.getOrNull(waveTeam) != null) {
                throw new AssertionError("Team stats created data for absent teams");
            }
            rules.waves = true;
            teams.updateTeamStats();
            var activeWaveTeam = teams.getOrNull(waveTeam);
            if (activeWaveTeam == null || !teams.present.contains(activeWaveTeam, true)
                    || teams.getOrNull(unused) != null) {
                throw new AssertionError("Team stats lost the stock empty wave team");
            }
            rules.waves = false;
            teams.updateTeamStats();
            if (teams.present.contains(activeWaveTeam, true)) {
                throw new AssertionError("Team stats retained an inactive empty wave team");
            }
            System.out.println("UPSTREAM_TEAM_STATS absent=unallocated wave-team=stock");
        } finally {
            rules.waves = previousWaves;
            rules.waveTeam = previousWaveTeam;
        }
    }

    @SuppressWarnings("unchecked")
    private static void verifyUnitBinding() {
        var type = mindustry.content.UnitTypes.dagger;
        var target = new mindustry.logic.LVar("test-unit-type");
        target.setconst(type);
        var instruction = new mindustry.logic.LExecutor.UnitBindI(target);
        var executor = new mindustry.logic.LExecutor();
        executor.unit = new mindustry.logic.LVar("test-bound-unit");
        executor.privileged = true;
        executor.team = Team.sharded;
        var team = executor.team.data();
        var previous = team.unitsByType;
        try {
            team.unitsByType = null;
            instruction.run(executor);
            if (executor.binds != null || executor.unit.obj() != null) {
                throw new AssertionError("Missing stock units allocated a binding cursor");
            }

            var first = type.create(executor.team);
            var second = type.create(executor.team);
            team.unitsByType = new Seq[Vars.content.units().size];
            team.unitsByType[type.id] = Seq.with(first, second);
            for (var expected : Seq.with(first, second, first)) {
                instruction.run(executor);
                if (executor.unit.obj() != expected) throw new AssertionError("Stock unit-binding order changed");
            }

            team.unitsByType[type.id].clear();
            executor.binds = new int[1];
            instruction.run(executor);
            if (executor.binds != null || executor.unit.obj() != null) {
                throw new AssertionError("Stale binding cursor survived a catalog-size change");
            }
            team.unitsByType[type.id].add(first);
            team.unitsByType[type.id].add(second);
            instruction.run(executor);
            if (executor.unit.obj() != first) throw new AssertionError("Reset binding did not restart from the first unit");
            System.out.println("UPSTREAM_LOGIC_UNIT_BINDING missing=unallocated rotation=stock reset=stock");
        } finally {
            team.unitsByType = previous;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected staged assets and a new save directory");
        var assets = Fi.get(args[0]);
        var output = Fi.get(args[1]);
        if (output.exists()) throw new IllegalArgumentException("Use a new scratch campaign directory");
        output.mkdirs();
        Core.bundle = I18NBundle.createBundle(assets.child("bundle"), Locale.ENGLISH);
        var maps = assets.child("maps");
        var session = UpstreamSinglePlayerSession.create(output, "en", assets.child("planets"),
            UpstreamSinglePlayerSession.loadSettings(output), maps);
        try {
            verifyTeamStats();
            verifyUnitBinding();
            verifyTileEffects();
            verifyTileContentIds();
            verifyEmptyLogicProgram();
            mindustry.ai.UpstreamPathfinderSearchReference.verify();
            verifySaveBuffer();
            int total = 0;
            int hidden = 0;
            for (String directory : new String[]{"serpulo", "serpulo/hidden", "erekir"}) {
                for (var file : maps.child(directory).list()) {
                    if (!file.extension().equals("msav")) continue;
                    var registered = Vars.tree.get("maps/" + directory + "/" + file.name());
                    var map = MapIO.createMap(registered, false);
                    if (!registered.equals(file) || map.width <= 0 || map.height <= 0) {
                        throw new AssertionError("Missing stock map registration or dimensions for " + file);
                    }
                    if (directory.equals("serpulo/hidden")) {
                        var sector = Planets.serpulo.sectors.get(Integer.parseInt(file.nameWithoutExtension()));
                        if (sector.preset == null || sector.preset.requireUnlock
                                || !file.equals(sector.preset.generator.map.file)) {
                            throw new AssertionError("Numbered sector has the wrong original preset map: " + file);
                        }
                        hidden++;
                    }
                    total++;
                }
            }
            int bound = 0;
            long decodedTiles = 0;
            for (var preset : Vars.content.sectors()) {
                if (preset.sector == null) continue;
                if (preset.generator.map == null || !preset.generator.map.file.exists()) {
                    throw new AssertionError("Active preset has no original map: " + preset.name);
                }
                Vars.logic.reset();
                Vars.world.loadSector(preset.sector);
                if (preset.sector.planet == Planets.serpulo && preset.sector.id == 111) {
                    verifyOreIndex(Vars.world.tiles);
                }
                session.application().runPosted();
                if (preset.planet == Planets.serpulo && preset.sector.id == 111) {
                    verifyPackedPathCosts();
                    verifyStockDarkness();
                }
                verifyCoreFlagOrder();
                var original = preset.generator.map;
                if (Vars.state.rules.sector != preset.sector || Vars.world.width() != original.width
                        || Vars.world.height() != original.height) {
                    throw new AssertionError("Could not decode the original world for " + preset.name);
                }
                decodedTiles += (long)Vars.world.width() * Vars.world.height();
                System.out.println("UPSTREAM_STOCK_CAMPAIGN_WORLD planet=" + preset.sector.planet.name
                    + " sector=" + preset.sector.id + " map=" + preset.name
                    + " width=" + Vars.world.width() + " height=" + Vars.world.height());
                bound++;
            }
            if (total != 95 || bound != 95 || hidden != 46) throw new AssertionError("Expected all original campaign maps");
            System.out.println("UPSTREAM_STOCK_CAMPAIGN_DECODE presets=" + bound + " tiles=" + decodedTiles);
            int patches = verifyPatchedWorld(session, SectorPresets.littoralShipyard.sector, 204, 2)
                + verifyPatchedWorld(session, Planets.serpulo.sectors.get(242), 242, 1);
            verifyRetiredScratch();
            System.out.println("UPSTREAM_STOCK_CAMPAIGN_ARCHIVE maps=" + total + " presets=" + bound
                + " hidden=" + hidden + " patches=" + patches);
        } finally {
            session.shutdown();
        }
    }

    private static void verifyPackedPathCosts() {
        var tiles = Vars.world.tiles;
        int area = tiles.width * tiles.height;
        for (int index = 0; index < area; index++) {
            var tile = tiles.geti(index);
            if (Vars.pathfinder.get(tile.x, tile.y) != Vars.pathfinder.packTile(tile)) {
                throw new AssertionError("Stock packed path cost differs at tile " + index);
            }
        }
        System.out.println("UPSTREAM_PATH_COSTS sector=111 packing=stock");
    }

    private static void verifyStockDarkness() {
        var tiles = Vars.world.tiles;
        int area = tiles.width * tiles.height;
        byte[] handheld = new byte[area];
        for (int i = 0; i < area; i++) handheld[i] = tiles.geti(i).data;
        new mindustry.core.World().addDarkness(tiles);
        for (int i = 0; i < area; i++) {
            if (tiles.geti(i).data != handheld[i]) throw new AssertionError("Stock darkness differs at tile " + i);
        }
        System.out.println("UPSTREAM_CAMPAIGN_DARKNESS_REFERENCE sector=111 tiles=" + area);
    }

    private static void verifyRetiredScratch() throws ReflectiveOperationException {
        var build = Vars.state.rules.defaultTeam.core();
        var buildings = scratch(mindustry.gen.Building.class, "tempBuilds");
        var tiles = scratch(mindustry.world.Block.class, "tempTiles");
        buildings.items[0] = build;
        buildings.size = 0; // A stale backing slot survives an ordinary Seq.clear().
        tiles.items[0] = build.tile;
        tiles.size = 1;
        Vars.logic.reset();
        if (java.util.Arrays.stream(buildings.items).anyMatch(java.util.Objects::nonNull) || !tiles.isEmpty()
                || tiles.items[0] != null || Vars.world.width() != 0) {
            throw new AssertionError("Stock world reset retained building or tile scratch references");
        }
        System.out.println("UPSTREAM_CAMPAIGN_RESET_SCRATCH building=cleared tile=cleared");
    }

    @SuppressWarnings("unchecked")
    private static arc.struct.Seq<Object> scratch(Class<?> owner, String name) throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return (arc.struct.Seq<Object>) field.get(null);
    }

    private static void verifySaveBuffer() throws IOException {
        var writer = SaveIO.getVersion();
        byte[] payload = new byte[10_001];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        writer.writeChunk(output, outer -> {
            outer.output.writeByte(127);
            writer.writeChunk(outer.output, inner -> inner.output.write(payload, 1, payload.length - 1));
            outer.output.writeByte(-128);
        });
        writer.writeChunk(output, empty -> {});
        var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
        if (input.readInt() != payload.length + 5 || input.readByte() != 127
                || input.readInt() != payload.length - 1) {
            throw new AssertionError("Stock save-region length or prefix changed");
        }
        for (int i = 1; i < payload.length; i++) {
            if (input.readByte() != payload[i]) throw new AssertionError("Stock save-region payload changed at " + i);
        }
        if (input.readByte() != -128 || input.readInt() != 0 || input.read() != -1) {
            throw new AssertionError("Stock save-region suffix or empty chunk changed");
        }
        System.out.println("UPSTREAM_SAVE_BUFFER length=ok payload=ok nested=ok empty=ok");
    }

    private static void verifyEmptyLogicProgram() throws IOException {
        var links = Seq.with(new LogicBlock.LogicLink(-2, 7, "switch1", true));
        var bytes = LogicBlock.compress(new byte[0], links);
        try (var decoded = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(bytes)))) {
            if (decoded.readUnsignedByte() != 1 || decoded.readInt() != 0
                    || decoded.readInt() != 1 || !decoded.readUTF().equals("switch1")
                    || decoded.readShort() != -2 || decoded.readShort() != 7 || decoded.read() != -1) {
                throw new AssertionError("An empty logic program changed the stock configuration format");
            }
        }
    }

    private static void verifyCoreFlagOrder() {
        int[] expected = new int[Team.all.length];
        for (var tile : Vars.world.tiles) {
            if (!tile.isCenter() || tile.build == null || !tile.block().flags.contains(BlockFlag.core)) continue;
            var team = tile.team();
            if (Vars.indexer.getFlagged(team, BlockFlag.core).get(expected[team.id]++) != tile.build) {
                throw new AssertionError("The stock core index lost its world order");
            }
        }
        for (var team : Team.all) {
            if (Vars.indexer.getFlagged(team, BlockFlag.core).size != expected[team.id]) {
                throw new AssertionError("The stock core index retained entries from another sector");
            }
        }
    }

    private static void verifyTileContentIds() {
        var tile = new mindustry.world.Tile(3, 7, Blocks.stone, Blocks.oreCopper, Blocks.stoneWall);
        if (tile.floor() != Blocks.stone || tile.floorID() != Blocks.stone.id
                || tile.overlay() != Blocks.oreCopper || tile.overlayID() != Blocks.oreCopper.id
                || tile.block() != Blocks.stoneWall || tile.blockID() != Blocks.stoneWall.id) {
            throw new AssertionError("Tile decoded incorrect stock content IDs");
        }

        var highest = Vars.content.blocks().peek();
        tile.updateBlockReference(highest);
        tile.setOverlayQuiet(Blocks.oreLead);
        if (tile.block() != highest || tile.blockID() != highest.id
                || tile.overlay() != Blocks.oreLead || tile.overlayID() != Blocks.oreLead.id
                || tile.floor() != Blocks.stone || tile.x != 3 || tile.y != 7) {
            throw new AssertionError("Updating a tile content ID changed another field");
        }

        tile.updateBlockReference(null);
        if (tile.block() != null || tile.blockID() != -1 || tile.overlay() != Blocks.oreLead) {
            throw new AssertionError("Tile lost the missing content ID sentinel");
        }
        short wallId = Blocks.stoneWall.id;
        short overlayId = Blocks.oreCopper.id;
        try {
            Blocks.stoneWall.id = Short.MAX_VALUE;
            Blocks.oreCopper.id = 1023;
            tile.updateBlockReference(Blocks.stoneWall);
            tile.setOverlayQuiet(Blocks.oreCopper);
            if (tile.blockID() != Short.MAX_VALUE || tile.overlayID() != 1023
                    || tile.floorID() != Blocks.stone.id) {
                throw new AssertionError("Wide stock type IDs did not round-trip");
            }
            tile.updateBlockReference(null);
            if (tile.blockID() != -1 || tile.overlayID() != 1023) {
                throw new AssertionError("Clearing a wide ID also cleared its neighbor");
            }
        } finally {
            Blocks.stoneWall.id = wallId;
            Blocks.oreCopper.id = overlayId;
        }
        tile.setOverlayQuiet(Blocks.oreCopper);
        if (tile.overlayID() != overlayId || tile.blockID() != -1 || tile.floor() != Blocks.stone) {
            throw new AssertionError("Compact IDs were not restored after wide IDs were removed");
        }
        tile.setExtraData(0x89abcdef);
        tile.data = (byte) 0x98;
        tile.floorData = 77;
        tile.overlayData = (byte) 0xfe;
        var copy = new mindustry.world.Tile(tile.x, tile.y);
        if (copy.extraData() != 0) throw new AssertionError("Tile metadata leaked to another tile at the same coordinates");
        copy.setPackedData(tile.getPackedData());
        if (copy.extraData() != 0x89abcdef || copy.data != (byte) 0x98
                || copy.floorData != 77 || copy.overlayData != (byte) 0xfe) {
            throw new AssertionError("Stock packed tile metadata did not round-trip");
        }
        tile.setExtraData(0);
        if (tile.extraData() != 0 || copy.extraData() != 0x89abcdef || tile.floorID() != Blocks.stone.id) {
            throw new AssertionError("Clearing metadata changed another tile or content ID");
        }
        System.out.println("UPSTREAM_TILE_CONTENT_IDS catalog=stock mutation=isolated missing=ok wide=ok metadata=stock");
    }

    private static void verifyTileEffects() {
        var tiles = new Tiles(2, 2);
        var other = new Tiles(2, 2);
        var first = Fire.create();
        var second = Fire.create();
        var puddle = Puddle.create();
        tiles.setFire(0, first);
        tiles.setPuddle(0, puddle);
        tiles.setFire(3, second);
        if (tiles.getFire(0) != first || tiles.getPuddle(0) != puddle || tiles.getFire(3) != second
                || tiles.getPuddle(3) != null || other.getFire(0) != null || other.getPuddle(0) != null) {
            throw new AssertionError("Stock tile effect lookups must be independent across tiles and worlds");
        }
        tiles.setFire(0, second);
        tiles.setPuddle(0, null);
        if (tiles.getFire(0) != second || tiles.getPuddle(0) != null) {
            throw new AssertionError("Stock tile effects must support replacement and removal");
        }
        tiles.setFire(0, null);
        if (tiles.getFire(0) != null || tiles.getFire(3) != second) {
            throw new AssertionError("Removing one fire changed the other tile");
        }
        try {
            tiles.getFire(-1);
            throw new AssertionError("Tile effect lookup accepted an out-of-range index");
        } catch (ArrayIndexOutOfBoundsException expected) {
            // Preserve the original bounds check.
        }
        System.out.println("UPSTREAM_TILE_EFFECT_STORAGE lookup=ok replace=ok clear=ok worlds=ok bounds=ok");
    }

    private static int verifyPatchedWorld(UpstreamSinglePlayerSession session, Sector sector, int expectedId, int count)
            throws Exception {
        var original = sector.preset.generator.map;
        if (sector.planet != Planets.serpulo || sector.id != expectedId
                || original.width != 600 || original.height != 600) {
            throw new AssertionError("The stock Java readback expects a 600x600 Serpulo sector " + expectedId);
        }
        Vars.logic.reset();
        Vars.world.loadSector(sector);
        session.application().runPosted();
        if (Vars.state.rules.sector != sector || Vars.state.rules.defaultTeam.core() == null
                || Vars.world.width() != original.width || Vars.world.height() != original.height
                || Vars.state.data.getPatches().size != count) {
            throw new AssertionError("Stock world or embedded data patches differ for " + sector.id);
        }
        var patches = Vars.state.data.getPatches();
        var paths = new String[count];
        var sources = new String[count];
        for (int i = 0; i < count; i++) {
            paths[i] = patches.get(i).path;
            sources[i] = patches.get(i).patch;
        }
        var slot = session.sectorFile(sector);
        session.save(slot);
        if (!session.start(null, slot, "stock-campaign-archive", sector)
                || Vars.state.rules.sector != sector || Vars.world.width() != original.width
                || Vars.world.height() != original.height || Vars.state.data.getPatches().size != count) {
            throw new AssertionError("Stock sector or embedded patch count changed after reload for " + sector.id);
        }
        var restored = Vars.state.data.getPatches();
        for (int i = 0; i < count; i++) {
            var patch = restored.get(i);
            if (!paths[i].equals(patch.path) || !sources[i].equals(patch.patch)) {
                throw new AssertionError("Stock embedded patch changed after reload for " + sector.id);
            }
        }
        System.out.println("UPSTREAM_STOCK_CAMPAIGN_PATCH sector=" + sector.id + " patches=" + count);
        return restored.size;
    }
}
