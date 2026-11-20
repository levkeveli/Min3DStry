import arc.Core;
import arc.graphics.g2d.TextureAtlas.AtlasRegion;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import mindustry.Vars;
import mindustry.content.Items;

public final class UpstreamLiveSessionReference {
    public static void main(String[] args) throws IOException {
        if (args.length == 3 && args[2].equals("--inventory")) {
            inventory(args);
            return;
        }
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
        int startLeft = session.viewport().left, startBottom = session.viewport().bottom;
        for (int frame = 0; frame < 70 && session.received() == 0; frame++) {
            session.advance(17, UpstreamLiveSession.RIGHT | UpstreamLiveSession.UP);
        }
        long delivery = Vars.state.updateId;
        session.draw();
        if (!Float.isNaN(target.itemX) || session.received() != 1 || delivery != 57
                || target.bottom < startBottom + UpstreamTerrainDrawProbe.VIEW_HEIGHT / 2 + 1) {
            throw new IllegalStateException("The camera did not pan away while the conveyor ran");
        }
        session.advance(0, UpstreamLiveSession.RESET);
        session.draw();
        if (target.left != startLeft || target.bottom != startBottom) {
            throw new IllegalStateException("Camera reset did not return to its initial position");
        }
        session.viewport().moveTo(Integer.MAX_VALUE, Integer.MAX_VALUE);
        session.draw();
        if (target.left + UpstreamTerrainDrawProbe.VIEW_WIDTH != Vars.world.width()
                || target.bottom + UpstreamTerrainDrawProbe.VIEW_HEIGHT != Vars.world.height()) {
            throw new IllegalStateException("The viewport extends beyond the upper map edge");
        }
        session.viewport().moveTo(-1, -1);
        session.draw();
        if (target.left != 0 || target.bottom != 0) {
            throw new IllegalStateException("The viewport extends beyond the lower map edge");
        }
        session.advance(0, UpstreamLiveSession.COLLECT);
        if (session.received() != 0) throw new IllegalStateException("Copper collection failed");
        session.draw();
        System.out.println("UPSTREAM_LIVE_REFERENCE pausedAt=" + tick + " delivery=" + delivery);
    }

    private static final class ItemTarget implements UpstreamTerrainDrawProbe.TerrainTarget {
        float itemX;
        int left, bottom;

        @Override
        public void begin(int left, int bottom, int width, int height) {
            this.left = left;
            this.bottom = bottom;
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

    private static void inventory(String[] args) throws IOException {
        var inventory = new AtlasInventory();
        var session = new UpstreamLiveSession(args, inventory);
        var viewport = session.viewport();
        int width = UpstreamTerrainDrawProbe.VIEW_WIDTH;
        int height = UpstreamTerrainDrawProbe.VIEW_HEIGHT;
        for (int y = 0; y < Vars.world.height(); y += height) {
            for (int x = 0; x < Vars.world.width(); x += width) {
                viewport.moveTo(x, y);
                session.draw();
            }
        }
        for (String region : inventory.used) System.out.println("UPSTREAM_CAMERA_SPRITE " + region);
    }

    private static final class AtlasInventory implements UpstreamTerrainDrawProbe.TerrainTarget {
        final TreeSet<String> used = new TreeSet<>();
        List<AtlasRegion> regions;

        @Override
        public void begin(int left, int bottom, int width, int height) {
            if (regions == null) {
                regions = new ArrayList<>();
                Core.atlas.getRegions().each(regions::add);
                regions.sort(Comparator.comparing(region -> region.name));
            }
        }

        @Override
        public void draw(int page, int left, int top, int width, int height, float x, float y,
                         float originX, float originY, float drawWidth, float drawHeight,
                         float rotation, int color) {
            for (AtlasRegion region : regions) {
                if (UpstreamTerrainDrawProbe.pageIndex(region) == page && left >= region.getX()
                        && top >= region.getY() && left + width <= region.getX() + region.width
                        && top + height <= region.getY() + region.height) {
                    used.add(region.name);
                    return;
                }
            }
            throw new IllegalStateException("Atlas metadata has no parent for page " + page
                + " region " + left + "," + top + "+" + width + "x" + height);
        }

        @Override
        public void end() {}
    }
}
