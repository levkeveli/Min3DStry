package mindustry.ai;

public final class UpstreamPathfinderSearchReference {
    private UpstreamPathfinderSearchReference() {}

    public static void verify() {
        verifyFrontier();
        verifySearchVisits();
    }

    private static void verifyFrontier() {
        var stock = new arc.struct.IntQueue();
        var frontier = new Pathfinder.FlowFrontier();
        for (int round = 0; round < 4; round++) {
            for (int index = 0; index < 61; index++) {
                int tile = round * 100 + index;
                stock.addFirst(tile);
                frontier.addFirst(tile);
            }
            for (int index = 0; index < 47; index++) {
                if (frontier.removeLast() != stock.removeLast() || frontier.size != stock.size) {
                    throw new AssertionError("Pathfinder frontier processing order or size changed");
                }
            }
            if (round == 1) {
                frontier.clear();
                stock.clear();
            }
        }
        while (stock.size > 0) {
            if (frontier.removeLast() != stock.removeLast() || frontier.size != stock.size) {
                throw new AssertionError("Pathfinder frontier order changed while draining");
            }
        }
        System.out.println("UPSTREAM_PATH_FRONTIER order=stock clear=stock");
    }

    private static void verifySearchVisits() {
        var stock = new short[12_289];
        var visits = new Pathfinder.SearchGrid(stock.length);
        int[] boundaryTiles = {0, 31, 32, 511, 512, 1023, 1024, 12_287, 12_288};
        int search = Short.MAX_VALUE - 3;

        for (int round = 0; round < boundaryTiles.length; round++) {
            visits.clear();
            if (++search >= Short.MAX_VALUE) {
                java.util.Arrays.fill(stock, (short) 0);
                search = 1;
            }

            for (int index = round % 2; index < boundaryTiles.length; index += 2) {
                int tile = boundaryTiles[index];
                stock[tile] = (short) search;
                visits.visit(tile);
            }
            for (int tile = 0; tile < stock.length; tile++) {
                if (visits.visited(tile) != (stock[tile] == search)) {
                    throw new AssertionError("Flowfield visit differs at tile " + tile + " on search " + search);
                }
            }
        }
        System.out.println("UPSTREAM_PATH_VISITS words=stock pages=stock groups=stock reset=stock overflow=stock");
    }
}
