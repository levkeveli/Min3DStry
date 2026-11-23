import arc.Core;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Planets;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Unit;
import mindustry.world.Block;
import mindustry.world.Build;
import mindustry.world.Tile;

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

    private enum InventoryAction { none, withdraw, deposit }

    private int left, bottom;
    private boolean viewportReady;
    private int tileX = -1, tileY = -1;
    private Block selected;
    private int rotation;
    private boolean removing;
    private final UpstreamBuildMenu catalog = new UpstreamBuildMenu();
    private final UpstreamBuildingConfig buildingConfig = new UpstreamBuildingConfig();
    private final UpstreamInventoryMenu inventory = new UpstreamInventoryMenu();
    private final UpstreamCommandOrders orders = new UpstreamCommandOrders();
    private final UpstreamPlayerControl playerControl = new UpstreamPlayerControl();
    private InventoryAction inventoryAction = InventoryAction.none;
    private boolean configuring;
    private boolean selectingMine;
    private boolean selectingControl;
    private final UpstreamSchematicPlacement schematics;

    UpstreamLiveBuildControls() {
        this(new Fi("/3ds/min3dstry/schematics"));
    }

    UpstreamLiveBuildControls(Fi schematicDirectory) {
        schematics = new UpstreamSchematicPlacement(schematicDirectory);
    }

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
        if (schematics.active()) {
            if ((buttons & REMOVE_PRESSED) != 0) {
                schematics.cancel();
                return;
            }

            int x = touch & 0xffff, y = touch >>> 16;
            if (schematics.isOpen()) {
                if (touch >= 0) schematics.tap(x, y);
            } else {
                if (touch >= 0 && selectGridCell(x, y)) schematics.position(unit, tileX, tileY);
                if ((buttons & ROTATE_PRESSED) != 0) schematics.rotate(unit);
                if ((buttons & PLACE_PRESSED) != 0) schematics.submit(unit);
            }
            return;
        }
        if (inventory.isOpen()) {
            if (touch >= 0) inventory.tap(touch & 0xffff, touch >>> 16);
            else inventory.refresh();
            return;
        }
        buildingConfig.validate();
        if (buildingConfig.isOpen()) {
            if (touch >= 0) buildingConfig.tap(touch & 0xffff, touch >>> 16);
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
        int touchX = touch & 0xffff, touchY = touch >>> 16;
        if (buildingConfig.isLinking()) {
            if ((buttons & REMOVE_PRESSED) != 0
                    || touch >= 0 && touchX >= 288 && touchY >= GRID_Y && touchY < 80) {
                buildingConfig.close();
            } else if (touch >= 0 && selectGridCell(touchX, touchY)) {
                buildingConfig.tapLink(Vars.world.build(tileX, tileY));
            }
            return;
        }
        boolean rightRail = touch >= 0 && touchX >= 292 && touchX < 318;
        if (rightRail && touchY >= 144 && touchY < 175) {
            if (orders.active()) orders.toggle();
            selectingControl = !selectingControl;
            configuring = selectingMine = removing = false;
            inventoryAction = InventoryAction.none;
            return;
        }
        if (selectingControl) {
            if ((buttons & REMOVE_PRESSED) != 0) {
                selectingControl = false;
            } else if ((buttons & PLACE_PRESSED) != 0 || rightRail && touchY >= 176 && touchY < 207) {
                playerControl.release();
                selectingControl = false;
            } else if (touch >= 0 && selectGridCell(touchX, touchY) && playerControl.take(tileX, tileY)) {
                selectingControl = false;
            }
            return;
        }
        if (touch >= 0 && touchX >= 292 && touchX < 318 && touchY >= 112 && touchY < 143) {
            orders.toggle();
            configuring = selectingMine = removing = false;
            inventoryAction = InventoryAction.none;
            return;
        }
        if (orders.active()) {
            if (orders.menu().isOpen()) {
                if ((buttons & REMOVE_PRESSED) != 0) orders.menu().close();
                else if (touch >= 0) orders.tapMenu(touchX, touchY);
                return;
            }
            if ((buttons & REMOVE_PRESSED) != 0) orders.toggle();
            else if ((buttons & ROTATE_PRESSED) != 0) orders.toggleQueue();
            if (orders.active() && touch >= 0) {
                if (touchX >= 2 && touchX < 28 && touchY >= 48 && touchY < 111) {
                    orders.openMenu(touchY >= 80);
                } else if (selectGridCell(touchX, touchY)) {
                    orders.tap(tileX, tileY);
                }
            }
            return;
        }
        if ((buttons & ROTATE_PRESSED) != 0) rotate();
        if ((buttons & REMOVE_PRESSED) != 0) toggleRemoval();
        boolean submit = !selectingMine && inventoryAction == InventoryAction.none && (buttons & PLACE_PRESSED) != 0;
        if (touch >= 0) {
            int x = touchX, y = touchY;
            if (x >= 288 && y >= GRID_Y && y < 80) {
                inventoryAction = InventoryAction.none;
                selectingMine = false;
                configuring = !configuring;
                return;
            } else if (x >= 288 && y >= 80 && y < 112) {
                inventoryAction = InventoryAction.none;
                selectingMine = true;
                configuring = false;
                removing = false;
                return;
            } else if (x < 32 && y >= GRID_Y && y < 80) {
                inventoryAction = InventoryAction.none;
                configuring = false;
                selectingMine = false;
                catalog.open(block());
                return;
            } else if (x < 32 && y >= 80 && y < 144) {
                var action = y < 112 ? InventoryAction.withdraw : InventoryAction.deposit;
                inventoryAction = inventoryAction == action ? InventoryAction.none : action;
                removing = false;
                selectingMine = false;
                configuring = false;
                return;
            } else if (x >= 2 && x < 28 && y >= 144 && y < 175) {
                inventoryAction = InventoryAction.none;
                configuring = selectingMine = removing = false;
                schematics.open();
                return;
            } else if (y < GRID_Y && x < 320) {
                switch (x / 80) {
                    case 0, 1 -> select(shortcut(x / 80));
                    case 2 -> rotate();
                    case 3 -> toggleRemoval();
                }
            } else if (selectGridCell(x, y)) {
                if (inventoryAction != InventoryAction.none) {
                    var build = Vars.world.build(tileX, tileY);
                    if (inventoryAction == InventoryAction.withdraw) inventory.open(build);
                    else inventory.deposit(build, System.nanoTime());
                    inventoryAction = InventoryAction.none;
                    return;
                }
                if (selectingMine) {
                    selectMine(unit, Vars.world.tile(tileX, tileY));
                    return;
                }
                if (configuring) {
                    configuring = false;
                    buildingConfig.open(Vars.world.build(tileX, tileY));
                    return;
                }
                submit = true;
            }
        }
        if (submit && !configuring && Vars.player.isBuilder()) queue(unit);
    }

    private boolean selectGridCell(int x, int y) {
        if (!viewportReady || x < GRID_X || x >= GRID_X + WIDTH * CELL
                || y < GRID_Y || y >= GRID_Y + HEIGHT * CELL) return false;
        tileX = left + (x - GRID_X) / CELL;
        tileY = bottom + HEIGHT - 1 - (y - GRID_Y) / CELL;
        return true;
    }

    private void select(Block block) {
        selected = block;
        removing = false;
        configuring = false;
        selectingMine = false;
        inventoryAction = InventoryAction.none;
        rotation = block.planRotation(rotation);
    }

    private void toggleRemoval() {
        selectingMine = false;
        inventoryAction = InventoryAction.none;
        removing = !removing;
    }

    private void selectMine(Unit unit, Tile tile) {
        if (tile == null) return;
        if (unit.mineTile == tile) {
            unit.mineTile = null;
            System.out.println("UPSTREAM_MINE_CANCEL x=" + tile.x + " y=" + tile.y);
            return;
        }
        // Match v160.8 InputHandler.canMine after the 3DS hit test has selected a world tile.
        if (Vars.player.dead() || !unit.validMine(tile) || !unit.acceptsItem(unit.getMineResult(tile))) return;
        var oreLayer = tile.overlay().itemDrop == null ? tile.floor() : tile.overlay();
        if (oreLayer.playerUnmineable && !Core.settings.getBool("doubletapmine")) return;
        unit.mineTile = tile;
        System.out.println("UPSTREAM_MINE_TARGET x=" + tile.x + " y=" + tile.y
            + " item=" + unit.getMineResult(tile).name);
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
    int cursorX() {
        var source = buildingConfig.linkSource();
        return source == null ? tileX : source.tileX();
    }
    int cursorY() {
        var source = buildingConfig.linkSource();
        return source == null ? tileY : source.tileY();
    }
    int rotation() { return rotation; }
    boolean removing() { return removing; }
    UpstreamSchematicPlacement schematics() { return schematics; }
    UpstreamBuildMenu catalog() { return catalog; }
    UpstreamBuildingConfig buildingConfig() { return buildingConfig; }
    UpstreamInventoryMenu inventory() { return inventory; }
    UpstreamCommandOrders orders() { return orders; }
    int inventoryAction() { return inventoryAction.ordinal(); }
    boolean configuring() { return configuring || buildingConfig.isLinking(); }
    boolean selectingMine() { return selectingMine; }
    boolean selectingControl() { return selectingControl; }
}
