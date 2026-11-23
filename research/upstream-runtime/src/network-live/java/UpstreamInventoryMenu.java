import arc.struct.Seq;
import java.nio.charset.StandardCharsets;
import mindustry.Vars;
import mindustry.entities.Units;
import mindustry.gen.Building;
import mindustry.gen.Call;
import mindustry.type.Item;

import static mindustry.Vars.player;

/** Touch adapter for the v160.8 BlockInventoryFragment and InputHandler item transfers. */
final class UpstreamInventoryMenu {
    private final Seq<Item> entries = new Seq<>();
    private final int[] iconIds = new int[UpstreamBuildMenu.ROWS];
    private final int[] amounts = new int[UpstreamBuildMenu.ROWS];
    private Building target;
    private int page, pageCount;
    private byte[] labels;
    private long lastDeposit;
    private boolean hasDeposited;

    void open(Building build) {
        if (!valid(build) || !build.block.isAccessible()) return;
        target = build;
        page = 0;
        labels = null;
        if (Vars.net.client()) Call.requestBlockSnapshot(build.pos());
        refresh();
        System.out.println("UPSTREAM_INVENTORY_OPEN x=" + build.tileX() + " y=" + build.tileY());
    }

    void refresh() {
        if (target == null) return;
        if (!valid(target) || !target.block.isAccessible()) {
            target = null;
            return;
        }
        entries.clear();
        for (var item : Vars.content.items()) {
            if (target.items.has(item)) entries.add(item);
        }
        int pages = Math.max(1, (entries.size + UpstreamBuildMenu.ROWS - 1) / UpstreamBuildMenu.ROWS);
        int current = Math.min(page, pages - 1);
        boolean changed = labels == null || current != page || pageCount != pages;
        page = current;
        pageCount = pages;
        for (int row = 0; row < UpstreamBuildMenu.ROWS; row++) {
            var item = itemAt(row);
            int id = item == null ? -1 : item.id;
            int amount = item == null ? 0 : target.items.get(item);
            changed |= iconIds[row] != id || amounts[row] != amount;
            iconIds[row] = id;
            amounts[row] = amount;
        }
        if (!changed) return;
        var text = new StringBuilder(target.block.localizedName).append("  ")
            .append(page + 1).append('/').append(pages).append('\0');
        for (int row = 0; row < UpstreamBuildMenu.ROWS; row++) {
            var item = itemAt(row);
            if (item != null) text.append(item.localizedName).append("  ").append(amounts[row]);
            text.append('\0');
        }
        labels = text.toString().getBytes(StandardCharsets.UTF_8);
    }

    void tap(int x, int y) {
        if (y >= UpstreamBuildMenu.FOOTER_TOP) {
            if (x < 100 && previousPage()) {
                page--;
                labels = null;
            } else if (x >= 220 && nextPage()) {
                page++;
                labels = null;
            } else if (x >= 100 && x < 220) {
                target = null;
            }
        } else if (y >= UpstreamBuildMenu.ROW_TOP && x >= 8 && x < 312) {
            int row = (y - UpstreamBuildMenu.ROW_TOP) / UpstreamBuildMenu.ROW_HEIGHT;
            if (row < UpstreamBuildMenu.ROWS) withdraw(itemAt(row));
        }
        refresh();
    }

    private void withdraw(Item item) {
        if (item == null || !canTransfer(target) || !target.canWithdraw()) return;
        int amount = Math.min(target.items.get(item), player.unit().maxAccepted(item));
        if (amount <= 0) return;
        Call.requestItem(player, target, item, amount);
        System.out.println("UPSTREAM_INVENTORY_WITHDRAW x=" + target.tileX() + " y=" + target.tileY()
            + " item=" + item.name + " requested=" + amount);
    }

    void deposit(Building build, long now) {
        if (!canTransfer(build) || !build.allowDeposit()) return;
        var unit = player.unit();
        var stack = unit.stack();
        if (stack.amount <= 0 || build.acceptStack(stack.item, stack.amount, unit) <= 0) return;
        float cooldown = build.block.depositCooldown >= 0
            ? build.block.depositCooldown : Vars.state.rules.itemDepositCooldown;
        if (hasDeposited && now - lastDeposit < cooldown * 1_000_000_000d) return;
        var item = stack.item;
        Call.transferInventory(player, build);
        lastDeposit = now;
        hasDeposited = true;
        System.out.println("UPSTREAM_INVENTORY_DEPOSIT x=" + build.tileX() + " y=" + build.tileY()
            + " item=" + item.name);
    }

    private static boolean valid(Building build) {
        return build != null && build.isValid() && build.items != null && !player.dead()
            && Units.canInteract(player, build);
    }

    private static boolean canTransfer(Building build) {
        return valid(build) && !Vars.state.isPaused() && player.within(build, Vars.itemTransferRange);
    }

    private Item itemAt(int row) {
        int index = page * UpstreamBuildMenu.ROWS + row;
        return index < entries.size ? entries.get(index) : null;
    }

    void close() { target = null; }
    boolean isOpen() { return target != null; }
    byte[] labels() { return labels; }
    int[] iconIds() { return iconIds; }
    boolean previousPage() { return page > 0; }
    boolean nextPage() { return (page + 1) * UpstreamBuildMenu.ROWS < entries.size; }
}
