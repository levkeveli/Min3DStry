package mindustry.world;

/** Releases the stock Block scratch references after leaving a world. */
public final class UpstreamBlockScratch {
    private UpstreamBlockScratch() {
    }

    public static void clear() {
        Block.tempTiles.clear();
        Block.tempBuilds.clear();
    }
}
