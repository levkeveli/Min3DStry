package mindustry.ai;

public final class UpstreamPathfinderSearchReference {
    private UpstreamPathfinderSearchReference() {}

    public static void verify() {
        verifyFrontier();
        verifySearchVisits();
        verifyCompletedWeights();
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

    private static void verifyCompletedWeights() {
        var working = new Pathfinder.CostGrid(24_579, true);
        var completed = new Pathfinder.CostGrid(24_579, true);
        working.fill(-1);
        working.set(0, 7);
        working.set(24_578, 21);
        working.copyTo(completed);
        working.set(0, 18);
        working.set(64, 45);
        if (completed.get(0) != 7 || completed.get(64) != -1 || completed.get(24_578) != 21
                || working.get(0) != 18 || working.get(64) != 45) {
            throw new AssertionError("Stock completed path weights changed during a working write");
        }
        working.fill(-5);
        if (completed.get(24_578) != 21 || working.get(24_578) != -5) {
            throw new AssertionError("Resetting working weights changed the completed path");
        }
        working.set(24_576, 12);
        working.copyTo(completed);
        if (completed.get(0) != -5 || completed.get(24_576) != 12 || completed.get(24_578) != -5) {
            throw new AssertionError("Publishing a new field lost its default or boundary weights");
        }
        System.out.println("UPSTREAM_PATH_SNAPSHOT working-write=ok reset=ok republish=ok");
    }
}
