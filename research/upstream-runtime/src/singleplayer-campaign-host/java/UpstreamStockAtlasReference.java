import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData;
import java.util.Arrays;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;

public final class UpstreamStockAtlasReference {
    public static void main(String[] args) {
        Fi file = new Fi(args[0]);
        var stock = new TextureAtlasData(file, file.parent(), false);
        var pages = stock.getPages();
        var expected = stock.getRegions();
        var atlas = UpstreamTerrainDrawProbe.readAtlas(file);
        var actual = atlas.getRegions();
        if (pages.size != atlas.getTextures().size || expected.size != actual.size) {
            throw new AssertionError("Stock atlas page or region count differs");
        }
        for (int index = 0; index < expected.size; index++) {
            var source = expected.get(index);
            var region = actual.get(index);
            if (!source.name.equals(region.name)
                    || pages.indexOf(source.page, true) != UpstreamTerrainDrawProbe.pageIndex(region)
                    || source.page.width != region.texture.width || source.page.height != region.texture.height
                    || source.left != region.getX() || source.top != region.getY()
                    || source.width != region.width || source.height != region.height
                    || source.offsetX != region.offsetX || source.offsetY != region.offsetY
                    || source.originalWidth != region.originalWidth || source.originalHeight != region.originalHeight
                    || !Arrays.equals(source.splits, region.splits) || !Arrays.equals(source.pads, region.pads)) {
                throw new AssertionError("Stock atlas differs for region " + index + " " + source.name);
            }
        }
        System.out.println("UPSTREAM_STOCK_ATLAS pages=" + pages.size + " regions=" + actual.size);
        verifyTeamSprites(file);
    }

    private static void verifyTeamSprites(Fi atlasFile) {
        var session = UpstreamSinglePlayerSession.create(atlasFile.parent(), "en");
        try {
            UpstreamTerrainDrawProbe.loadAtlas(atlasFile, () -> {});
            Object shared = null;
            int fallback = 0;
            int colored = 0;
            for (var block : Vars.content.blocks()) {
                if (block == Blocks.air) continue;
                var base = Core.atlas.find(block.name + "-team");
                if (block.teamRegion != base || block.teamRegions.length != Team.all.length) {
                    throw new AssertionError("Stock team sprite metadata differs for " + block.name);
                }
                for (Team team : Team.all) {
                    var expected = base.found() && team.hasPalette
                        ? Core.atlas.find(block.name + "-team-" + team.name, base) : base;
                    if (block.teamRegions[team.id] != expected) {
                        throw new AssertionError("Stock team sprite differs for " + block.name + " team " + team.id);
                    }
                }
                if (base.found()) {
                    colored++;
                } else {
                    if (shared == null) shared = block.teamRegions;
                    if (block.teamRegions != shared) throw new AssertionError("Fallback table was duplicated");
                    fallback++;
                }
            }
            System.out.println("UPSTREAM_STOCK_TEAM_ATLAS fallback=" + fallback + " colored=" + colored
                + " teams=" + Team.all.length);
        } finally {
            session.shutdown();
        }
    }
}
