import java.io.IOException;
import mindustry.Vars;
import mindustry.content.Items;

public final class UpstreamLiveSessionReference {
    public static void main(String[] args) throws IOException {
        var target = new ItemTarget();
        var session = new UpstreamLiveSession(args, target);
        session.draw();
        session.advance(17, UpstreamLiveSession.SEND);
        session.draw();
        float initialItem = target.itemX;
        for (int frame = 0; frame < 9; frame++) session.advance(17, 0);
        session.draw();
        float movingItem = target.itemX;
        if (!(movingItem > initialItem)) throw new IllegalStateException("Copper sprite did not move");

        long tick = Vars.state.updateId;
        session.advance(17, UpstreamLiveSession.PAUSE);
        for (int frame = 0; frame < 30; frame++) session.advance(17, 0);
        session.draw();
        if (Vars.state.updateId != tick || session.received() != 0 || target.itemX != movingItem) {
            throw new IllegalStateException("The upstream simulation changed while paused");
        }
        session.advance(17, UpstreamLiveSession.PAUSE);
        for (int frame = 0; frame < 70 && session.received() == 0; frame++) session.advance(17, 0);
        if (session.received() != 1) throw new IllegalStateException("Copper never reached the router");
        session.advance(0, UpstreamLiveSession.COLLECT);
        if (session.received() != 0) throw new IllegalStateException("Copper collection failed");
        session.draw();
        System.out.println("UPSTREAM_LIVE_REFERENCE pausedAt=" + tick + " delivery=" + Vars.state.updateId);
    }

    private static final class ItemTarget implements UpstreamTerrainDrawProbe.TerrainTarget {
        float itemX;

        @Override
        public void begin(int left, int bottom, int width, int height) {
            itemX = Float.NaN;
        }

        @Override
        public void draw(int page, int left, int top, int width, int height, float x, float y,
                         float originX, float originY, float drawWidth, float drawHeight,
                         float rotation, int color) {
            var icon = Items.copper.fullIcon;
            if (left == icon.getX() && top == icon.getY()
                    && width == icon.width && height == icon.height) {
                itemX = x;
            }
        }

        @Override
        public void end() {}
    }
}
