import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Unit;
import mindustry.world.Block;
import mindustry.world.Build;

/** Maps the 3DS touch grid to the last presented viewport and queues upstream plans. */
final class UpstreamLiveBuildControls {
    static final int GRID_X = 32;
    static final int GRID_Y = 48;
    static final int CELL = 16;
    static final int WIDTH = 16;
    static final int HEIGHT = 12;
    private static final int PLACE_PRESSED = 1 << 17;
    private static final int ROTATE_PRESSED = 1 << 18;
    private static final int REMOVE_PRESSED = 1 << 19;

    private int left, bottom;
    private boolean viewportReady;
    private int tileX = -1, tileY = -1;
    private Block selected;
    private int rotation;
    private boolean removing;

    void viewport(int left, int bottom) {
        this.left = left;
        this.bottom = bottom;
        viewportReady = true;
        if (!contains(tileX, tileY)) {
            tileX = -1;
            tileY = -1;
        }
    }

    void update(Unit unit, int buttons, int touch) {
        if ((buttons & ROTATE_PRESSED) != 0) rotate();
        if ((buttons & REMOVE_PRESSED) != 0) removing = !removing;
        boolean submit = (buttons & PLACE_PRESSED) != 0;
        if (touch >= 0) {
            int x = touch & 0xffff, y = touch >>> 16;
            if (y < GRID_Y && x < 320) {
                switch (x / 80) {
                    case 0 -> select(Blocks.conveyor);
                    case 1 -> select(Blocks.copperWall);
                    case 2 -> rotate();
                    case 3 -> removing = !removing;
                }
            } else if (viewportReady && x >= GRID_X && x < GRID_X + WIDTH * CELL
                    && y >= GRID_Y && y < GRID_Y + HEIGHT * CELL) {
                tileX = left + (x - GRID_X) / CELL;
                tileY = bottom + HEIGHT - 1 - (y - GRID_Y) / CELL;
                submit = true;
            }
        }
        if (submit && Vars.player.isBuilder()) queue(unit);
    }

    private void select(Block block) {
        selected = block;
        removing = false;
        rotation = block.planRotation(rotation);
    }

    private void rotate() {
        if (block().rotate) rotation = (rotation + 1) % 4;
    }

    private void queue(Unit unit) {
        int x = tileX < 0 ? unit.tileX() + 4 : tileX;
        int y = tileY < 0 ? unit.tileY() : tileY;
        var tile = Vars.world.tile(x, y);
        if (tile == null) return;
        BuildPlan plan;
        if (removing) {
            if (tile.block() == Blocks.air || !Build.validBreak(unit.team(), x, y)) return;
            plan = new BuildPlan(x, y);
        } else {
            plan = new BuildPlan(x, y, rotation, block());
            if (!plan.placeable(unit.team())) return;
        }
        unit.addBuild(plan);
        Vars.player.selectedBlock = block();
        Vars.player.selectedRotation = rotation;
        System.out.println("UPSTREAM_LIVE_BUILD_PLAN x=" + plan.x + " y=" + plan.y + " rotation=" + plan.rotation);
        System.out.println("UPSTREAM_TOUCH_PLAN block=" + (plan.breaking ? "break" : plan.block.name)
            + " x=" + x + " y=" + y + " rotation=" + plan.rotation);
    }

    private boolean contains(int x, int y) {
        return x >= left && x < left + WIDTH && y >= bottom && y < bottom + HEIGHT;
    }

    Block block() { return selected == null ? Blocks.conveyor : selected; }
    int cursorX() { return tileX; }
    int cursorY() { return tileY; }
    int rotation() { return rotation; }
    boolean removing() { return removing; }
}
