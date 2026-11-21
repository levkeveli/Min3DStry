import arc.struct.Seq;
import java.nio.charset.StandardCharsets;
import mindustry.Vars;
import mindustry.entities.Units;
import mindustry.type.Item;
import mindustry.world.blocks.distribution.Sorter;
import static mindustry.Vars.player;

/** Touch equivalent of the upstream Sorter item selection table. */
final class UpstreamItemConfig {
    private final Seq<Item> items = new Seq<>();
    private Sorter.SorterBuild target;
    private int page;
    private byte[] labels;
    private final int[] iconIds = new int[UpstreamBuildMenu.ROWS];

    void open(Sorter.SorterBuild build) {
        if (!build.isValid() || !build.block.configurable || !Units.canInteract(player, build)
                || !build.configTapped()) return;
        target = build;
        items.selectFrom(Vars.content.items(), UpstreamItemChoices::accepts);
        page = Math.max(0, items.indexOf(build.config(), true)) / UpstreamBuildMenu.ROWS;
        refreshLabels();
        System.out.println("UPSTREAM_CONFIG_OPEN x=" + build.tileX() + " y=" + build.tileY());
    }

    void tap(int x, int y) {
        if (y >= UpstreamBuildMenu.FOOTER_TOP) {
            if (x < 100 && previousPage()) {
                page--;
                refreshLabels();
            } else if (x >= 220 && nextPage()) {
                page++;
                refreshLabels();
            } else if (x >= 100 && x < 220) {
                close();
            }
        } else if (y >= UpstreamBuildMenu.ROW_TOP && x >= 8 && x < 312) {
            int row = (y - UpstreamBuildMenu.ROW_TOP) / UpstreamBuildMenu.ROW_HEIGHT;
            int index = page * UpstreamBuildMenu.ROWS + row;
            if (row >= UpstreamBuildMenu.ROWS || index >= items.size) return;
            var item = items.get(index);
            Item value = item == target.config() ? null : item;
            target.configure(value);
            System.out.println("UPSTREAM_CONFIG_LOCAL x=" + target.tileX() + " y=" + target.tileY()
                + " item=" + (value == null ? "none" : value.name));
            close();
        }
    }

    void validate() {
        if (isOpen() && (!target.isValid() || !Units.canInteract(player, target)
                || target.shouldHideConfigure(player))) close();
    }

    private void close() {
        target.onConfigureClosed();
        target = null;
    }

    private void refreshLabels() {
        int pages = Math.max(1, (items.size + UpstreamBuildMenu.ROWS - 1) / UpstreamBuildMenu.ROWS);
        var result = new StringBuilder(target.block.localizedName).append("  ")
            .append(page + 1).append('/').append(pages).append('\0');
        for (int row = 0; row < UpstreamBuildMenu.ROWS; row++) {
            int index = page * UpstreamBuildMenu.ROWS + row;
            if (index < items.size) {
                var item = items.get(index);
                result.append(item.localizedName);
                iconIds[row] = item.id;
            } else {
                iconIds[row] = -1;
            }
            result.append('\0');
        }
        labels = result.toString().getBytes(StandardCharsets.UTF_8);
    }

    boolean isOpen() { return target != null; }
    byte[] labels() { return labels; }
    int[] iconIds() { return iconIds; }
    boolean previousPage() { return page > 0; }
    boolean nextPage() { return (page + 1) * UpstreamBuildMenu.ROWS < items.size; }
    int selectedRow() { return items.indexOf(target.config(), true) - page * UpstreamBuildMenu.ROWS; }
}
