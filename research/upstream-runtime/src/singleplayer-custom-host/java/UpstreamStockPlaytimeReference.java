import arc.files.Fi;
import mindustry.game.Gamemode;
import mindustry.io.MapIO;
import mindustry.io.SaveIO;

public final class UpstreamStockPlaytimeReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected the Glacier map and a scratch save directory");
        var map = new Fi(args[0]);
        var directory = new Fi(args[1]);
        var session = UpstreamSinglePlayerSession.create(directory, "en");
        try {
            session.startCustomGame(MapIO.createMap(map, false), Gamemode.sandbox);
            session.beginPlaytimeInterval(100_000_000L);
            session.updatePlaytime(475_700_000L);
            // A modal menu spent several seconds open. Resume without adding them.
            session.beginPlaytimeInterval(5_000_000_000L);
            session.updatePlaytime(5_000_400_000L);
            var first = directory.child("first.msav");
            session.save(first);
            expect(first, 376);

            if (!session.start(null, first, "stock-playtime")) throw new AssertionError("Cannot resume the saved world");
            session.beginPlaytimeInterval(6_000_000_000L);
            session.updatePlaytime(6_250_000_000L);
            var resumed = directory.child("resumed.msav");
            session.save(resumed);
            expect(resumed, 626);

            session.startCustomGame(MapIO.createMap(map, false), Gamemode.sandbox);
            var newGame = directory.child("new.msav");
            session.save(newGame);
            expect(newGame, 0);
            System.out.println("UPSTREAM_STOCK_PLAYTIME first=376 resumed=626 new=0 modal=excluded");
        } finally {
            session.shutdown();
        }
    }

    private static void expect(Fi save, long millis) {
        long actual = SaveIO.getMeta(save).timePlayed;
        if (actual != millis) throw new AssertionError("Expected " + millis + " ms for " + save + ", got " + actual);
    }
}
