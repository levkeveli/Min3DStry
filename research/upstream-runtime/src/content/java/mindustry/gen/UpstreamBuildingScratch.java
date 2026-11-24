package mindustry.gen;

/** Releases the scratch references held by the stock generated Building class. */
public final class UpstreamBuildingScratch {
    private UpstreamBuildingScratch() {
    }

    public static void clear() {
        java.util.Arrays.fill(Building.tempBuilds.items, null);
        Building.tempBuilds.clear();
        Building.tmpTiles.clear();
    }
}
