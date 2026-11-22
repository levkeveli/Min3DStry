import arc.Core;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Planets;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Unit;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.Sorter;

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
    private final UpstreamBuildMenu catalog = new UpstreamBuildMenu();
    private final UpstreamItemConfig itemConfig = new UpstreamItemConfig();
    private boolean configuring;
    private boolean selectingMine;

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
        itemConfig.validate();
        if (itemConfig.isOpen()) {
            if (touch >= 0) itemConfig.tap(touch & 0xffff, touch >>> 16);
            return;
        }
        if (catalog.isOpen()) {
            if (touch >= 0) {
                Block entry = catalog.tap(touch & 0xffff, touch >>> 16);
                if (entry != null) {
                    select(entry);
                    System.out.println("UPSTREAM_CATALOG_SELECTED " + entry.name);
                }
            }
            return;
        }
        if ((buttons & ROTATE_PRESSED) != 0) rotate();
        if ((buttons & REMOVE_PRESSED) != 0) toggleRemoval();
        boolean submit = !selectingMine && (buttons & PLACE_PRESSED) != 0;
        if (touch >= 0) {
            int x = touch & 0xffff, y = touch >>> 16;
            System.out.println("UPSTREAM_BUILD_TOUCH x=" + x + " y=" + y
                + " mine=" + selectingMine + " viewport=" + left + "," + bottom);
            if (x >= 288 && y >= GRID_Y && y < 80) {
                selectingMine = false;
                configuring = !configuring;
                return;
            } else if (x >= 288 && y >= 80 && y < 112) {
                selectingMine = true;
                System.out.println("UPSTREAM_MINE_MODE active=true");
                configuring = false;
                removing = false;
                return;
            } else if (x < 32 && y >= GRID_Y && y < 80) {
                configuring = false;
                selectingMine = false;
                catalog.open(block());
                return;
            } else if (y < GRID_Y && x < 320) {
                switch (x / 80) {
                    case 0, 1 -> select(shortcut(x / 80));
                    case 2 -> rotate();
                    case 3 -> toggleRemoval();
                }
            } else if (viewportReady && x >= GRID_X && x < GRID_X + WIDTH * CELL
                    && y >= GRID_Y && y < GRID_Y + HEIGHT * CELL) {
                tileX = left + (x - GRID_X) / CELL;
                tileY = bottom + HEIGHT - 1 - (y - GRID_Y) / CELL;
                if (selectingMine) {
                    selectMine(unit, Vars.world.tile(tileX, tileY));
                    return;
                }
                if (configuring) {
                    configuring = false;
                    if (Vars.world.build(tileX, tileY) instanceof Sorter.SorterBuild sorter) itemConfig.open(sorter);
                    return;
                }
                submit = true;
            }
        }
        if (submit && !configuring && Vars.player.isBuilder()) queue(unit);
    }

    private void select(Block block) {
        selected = block;
        removing = false;
        configuring = false;
        selectingMine = false;
        rotation = block.planRotation(rotation);
    }

    private void toggleRemoval() {
        selectingMine = false;
        removing = !removing;
    }

    private void selectMine(Unit unit, Tile tile) {
        if (tile == null) {
            logMineRejected("outside-world");
            return;
        }
        if (unit.mineTile == tile) {
            unit.mineTile = null;
            System.out.println("UPSTREAM_MINE_CANCEL x=" + tile.x + " y=" + tile.y);
            return;
        }
        // Match v160.8 InputHandler.canMine after the 3DS hit test has selected a world tile.
        if (Vars.player.dead()) {
            logMineRejected("player-dead");
            return;
        }
        if (!unit.validMine(tile)) {
            logMineRejected("invalid-tile");
            return;
        }
        if (!unit.acceptsItem(unit.getMineResult(tile))) {
            logMineRejected("inventory held=" + unit.stack.item.name + ":" + unit.stack.amount
                + " capacity=" + unit.itemCapacity());
            return;
        }
        var oreLayer = tile.overlay().itemDrop == null ? tile.floor() : tile.overlay();
        if (oreLayer.playerUnmineable && !Core.settings.getBool("doubletapmine")) {
            logMineRejected("doubletapmine-disabled");
            return;
        }
        unit.mineTile = tile;
        System.out.println("UPSTREAM_MINE_TARGET x=" + tile.x + " y=" + tile.y
            + " item=" + unit.getMineResult(tile).name);
    }

    private void logMineRejected(String reason) {
        System.out.println("UPSTREAM_MINE_REJECT x=" + tileX + " y=" + tileY + " reason=" + reason);
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

    Block shortcut(int slot) {
        if (Vars.state.rules.planet == Planets.erekir) {
            return slot == 0 ? Blocks.duct : Blocks.berylliumWall;
        }
        return slot == 0 ? Blocks.conveyor : Blocks.copperWall;
    }

    Block block() { return selected == null ? shortcut(0) : selected; }
    int cursorX() { return tileX; }
    int cursorY() { return tileY; }
    int rotation() { return rotation; }
    boolean removing() { return removing; }
    UpstreamBuildMenu catalog() { return catalog; }
    UpstreamItemConfig itemConfig() { return itemConfig; }
    boolean configuring() { return configuring; }
    boolean selectingMine() { return selectingMine; }
}
