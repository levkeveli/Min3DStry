import java.io.IOException;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.core.GameState.State;
import mindustry.gen.Building;
import mindustry.gen.Groups;
import mindustry.world.Block;
import mindustry.world.Tile;

final class UpstreamLiveSession {
    static final int SEND = 1;
    static final int PAUSE = 2;
    static final int COLLECT = 4;
    static final int LEFT = 8;
    static final int RIGHT = 16;
    static final int DOWN = 32;
    static final int UP = 64;
    static final int RESET = 128;
    private static final int PAN_INTERVAL_MS = 75;

    private final UpstreamWorldLoadProbe.ProbeApplication app;
    private final UpstreamTerrainDrawProbe.Viewport viewport;
    private final Building source, first, receiver;
    private final Runnable drawBuildings = this::drawBuildings;
    private int millisecondsAt60Hz;
    private int received;
    private int panDirection, panRemainingMs;

    UpstreamLiveSession(String[] args, UpstreamTerrainDrawProbe.TerrainTarget target) throws IOException {
        app = UpstreamSimulationWorld.play(UpstreamTerrainDrawProbe.mapArgs(args));
        viewport = UpstreamTerrainDrawProbe.prepare(args, target, false);
        Blocks.junction.load();
        Blocks.conveyor.load();
        Blocks.router.load();
        Items.copper.loadIcon();

        Tile strip = findStrip();
        int x = strip.x, y = strip.y;
        source = place(x, y, Blocks.junction);
        first = place(x + 1, y, Blocks.conveyor);
        place(x + 2, y, Blocks.conveyor);
        receiver = place(x + 3, y, Blocks.router);
    }

    private Tile findStrip() {
        int center = UpstreamTerrainDrawProbe.VIEW_HEIGHT / 2;
        for (int row = 0; row < UpstreamTerrainDrawProbe.VIEW_HEIGHT; row++) {
            int y = viewport.bottom + (center + row) % UpstreamTerrainDrawProbe.VIEW_HEIGHT;
            int available = 0;
            for (int column = 0; column < UpstreamTerrainDrawProbe.VIEW_WIDTH; column++) {
                int x = viewport.left + column;
                Tile tile = Vars.world.tile(x, y);
                available = tile.build == null && tile.floor().hasSurface() ? available + 1 : 0;
                if (available == 4) return Vars.world.tile(x - 3, y);
            }
        }
        throw new IllegalStateException("The Glacier viewport has no surface strip for the fixture");
    }

    private Building place(int x, int y, Block block) {
        Tile tile = Vars.world.tile(x, y);
        tile.setBlock(block, Vars.state.rules.defaultTeam, 0);
        return tile.build;
    }

    void advance(int elapsedMs, int input) {
        elapsedMs = Math.min(250, Math.max(0, elapsedMs));
        pan(elapsedMs, input);
        if ((input & PAUSE) != 0) {
            Vars.state.set(Vars.state.isPaused() ? State.playing : State.paused);
            System.out.println("UPSTREAM_LIVE_PAUSE paused=" + Vars.state.isPaused()
                + " ticks=" + Vars.state.updateId);
        }
        if (!Vars.state.isPaused()) {
            if ((input & COLLECT) != 0 && receiver.removeStack(Items.copper, 1) != 0) {
                received--;
                System.out.println("UPSTREAM_LIVE_COLLECT copper=" + received);
            }
            if ((input & SEND) != 0 && first.acceptItem(source, Items.copper)) {
                first.handleItem(source, Items.copper);
                System.out.println("UPSTREAM_LIVE_SEND tick=" + Vars.state.updateId);
            }
        }
        millisecondsAt60Hz += elapsedMs * 60;
        while (millisecondsAt60Hz >= 1000) {
            millisecondsAt60Hz -= 1000;
            Vars.logic.update();
            app.runPosted();
            int inventory = receiver.items.get(Items.copper);
            if (inventory != received) {
                received = inventory;
                System.out.println("UPSTREAM_LIVE_DELIVERY copper=" + received + " tick=" + Vars.state.updateId);
            }
        }
    }

    private void pan(int elapsedMs, int input) {
        int left = viewport.left, bottom = viewport.bottom;
        int direction = input & (LEFT | RIGHT | DOWN | UP);
        if ((input & RESET) != 0) {
            viewport.reset();
            panRemainingMs = PAN_INTERVAL_MS;
        } else if (direction != 0) {
            panRemainingMs = direction == panDirection ? panRemainingMs - elapsedMs : 0;
            int dx = ((direction & RIGHT) != 0 ? 1 : 0) - ((direction & LEFT) != 0 ? 1 : 0);
            int dy = ((direction & UP) != 0 ? 1 : 0) - ((direction & DOWN) != 0 ? 1 : 0);
            while (panRemainingMs <= 0) {
                viewport.moveTo(viewport.left + dx, viewport.bottom + dy);
                panRemainingMs += PAN_INTERVAL_MS;
            }
        }
        panDirection = direction;
        if (viewport.left != left || viewport.bottom != bottom || (input & RESET) != 0) {
            System.out.println("UPSTREAM_LIVE_CAMERA left=" + viewport.left + " bottom=" + viewport.bottom);
        }
    }

    int received() {
        return received;
    }

    void draw() {
        viewport.draw(drawBuildings);
    }

    private void drawBuildings() {
        for (Building building : Groups.build) {
            if (viewport.contains(building)) building.draw();
        }
    }

    UpstreamTerrainDrawProbe.Viewport viewport() {
        return viewport;
    }
}
