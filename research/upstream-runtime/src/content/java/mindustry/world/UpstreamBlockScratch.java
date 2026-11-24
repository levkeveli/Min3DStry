package mindustry.world;

/** Releases the stock Block scratch references after leaving a world. */
public final class UpstreamBlockScratch {
    private UpstreamBlockScratch() {
    }

    public static int tileReferences() {
        int count = 0;
        for (Object tile : Block.tempTiles.items) if (tile != null) count++;
        return count;
    }

    public static void clear() {
        // Seq.clear only clears slots below size. A reused scratch array can retain older entries.
        java.util.Arrays.fill(Block.tempTiles.items, null);
        java.util.Arrays.fill(Block.tempBuilds.items, null);
        Block.tempTiles.clear();
        Block.tempBuilds.clear();
    }
}
