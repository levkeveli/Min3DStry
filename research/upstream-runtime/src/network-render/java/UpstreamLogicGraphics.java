import arc.files.Fi;
import mindustry.content.Blocks;
import port.render.UpstreamLogicAtlas;
import port.render.UpstreamLogicFont;
import port.render.UpstreamLogicFramebuffer;

/** Connects the stock display commands to the loaded world atlas and font. */
final class UpstreamLogicGraphics {
    static void load(Fi assets) {
        UpstreamLogicFont.load(assets.child("logic-font.dat"));
        var atlas = new UpstreamLogicAtlas(assets.child("world-atlas"));
        UpstreamLogicFramebuffer.atlasPixels((texture, x, y) ->
            atlas.sample(UpstreamTerrainDrawProbe.pageIndex(texture), x, y));
        Blocks.logicDisplay.load();
        Blocks.largeLogicDisplay.load();
        Blocks.tileLogicDisplay.load();
    }
}
