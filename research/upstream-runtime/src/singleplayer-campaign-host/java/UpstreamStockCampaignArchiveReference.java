import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.content.SectorPresets;
import mindustry.game.Team;
import mindustry.gen.Fire;
import mindustry.gen.Puddle;
import mindustry.io.MapIO;
import mindustry.type.Sector;
import mindustry.world.Tiles;
import mindustry.world.meta.BlockFlag;

public final class UpstreamStockCampaignArchiveReference {
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
            verifyTileEffects();
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
                session.application().runPosted();
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
            System.out.println("UPSTREAM_STOCK_CAMPAIGN_ARCHIVE maps=" + total + " presets=" + bound
                + " hidden=" + hidden + " patches=" + patches);
        } finally {
            session.shutdown();
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
