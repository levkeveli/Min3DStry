import arc.files.Fi;
import arc.graphics.g2d.TextureAtlas.TextureAtlasData;
import java.util.Arrays;

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
    }
}
