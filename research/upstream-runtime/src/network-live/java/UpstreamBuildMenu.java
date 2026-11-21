import arc.struct.Seq;
import java.nio.charset.StandardCharsets;
import mindustry.type.Category;
import mindustry.world.Block;

/** Touch navigation for the catalog supplied by upstream PlacementFragment. */
final class UpstreamBuildMenu {
    static final int ROWS = 6;
    static final int ROW_TOP = 44;
    static final int ROW_HEIGHT = 25;
    static final int FOOTER_TOP = 200;

    private final UpstreamPlacementCatalog source = new UpstreamPlacementCatalog();
    private Category category = Category.distribution;
    private Seq<Block> entries;
    private boolean open;
    private int page;
    private byte[] labels;

    void open(Block selected) {
        open = true;
        category = selected.category;
        entries = source.getUnlockedByCategory(category);
        page = Math.max(0, entries.indexOf(selected, true)) / ROWS;
        refreshLabels();
    }

    Block tap(int x, int y) {
        if (y < ROW_TOP) {
            if (x < 64 || x >= 256) {
                int advance = x < 64 ? -1 : 1;
                for (int attempt = 0; attempt < Category.all.length; attempt++) {
                    category = advance < 0 ? category.prev() : category.next();
                    entries = source.getUnlockedByCategory(category);
                    if (!entries.isEmpty()) break;
                }
                page = 0;
                refreshLabels();
            }
        } else if (y >= FOOTER_TOP) {
            if (x < 100 && previousPage()) {
                page--;
                refreshLabels();
            } else if (x >= 220 && nextPage()) {
                page++;
                refreshLabels();
            } else if (x >= 100 && x < 220) {
                open = false;
            }
        } else if (y < ROW_TOP + ROWS * ROW_HEIGHT && x >= 8 && x < 312) {
            int slot = page * ROWS + (y - ROW_TOP) / ROW_HEIGHT;
            if (slot < entries.size) {
                open = false;
                return entries.get(slot);
            }
        }
        return null;
    }

    private void refreshLabels() {
        int pages = Math.max(1, (entries.size + ROWS - 1) / ROWS);
        var pageLabels = new StringBuilder(category.name()).append("  ")
            .append(page + 1).append('/').append(pages).append('\0');
        for (int row = 0; row < ROWS; row++) {
            int index = page * ROWS + row;
            if (index < entries.size) {
                var block = entries.get(index);
                pageLabels.append(block.localizedName == null ? block.name : block.localizedName);
            }
            pageLabels.append('\0');
        }
        labels = pageLabels.toString().getBytes(StandardCharsets.UTF_8);
        System.out.println("UPSTREAM_CATALOG_PAGE category=" + category.name()
            + " page=" + (page + 1) + " pages=" + pages + " entries=" + entries.size);
    }

    boolean isOpen() { return open; }
    byte[] labels() { return labels; }
    boolean previousPage() { return page > 0; }
    boolean nextPage() { return (page + 1) * ROWS < entries.size; }
    int selectedRow(Block block) { return entries.indexOf(block, true) - page * ROWS; }
}
