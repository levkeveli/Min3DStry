import java.util.List;

/**
 * Stock LoadDialog paging adapted to the handheld row list.
 *
 * v160.8 shows every save slot newest first with a scroll pane; the two-screen
 * port shows one page of seven rows with "<" and ">" page rows. The page,
 * highlight, row text and selection maths live here so the title Load Game and
 * the in-game pause Load route share exactly the same behaviour.
 */
public final class UpstreamSavePicker {
    public static final int PAGE_SIZE = 7;

    public record Page(int first, int end, boolean previous, boolean next) {
        public int rows() {
            return end - first + (previous ? 1 : 0) + (next ? 1 : 0);
        }
    }

    private UpstreamSavePicker() {
    }

    public static Page page(int count, int selected) {
        if (count <= 0) return new Page(0, 0, false, false);
        int index = Math.max(0, Math.min(count - 1, selected));
        int page = index / PAGE_SIZE;
        int first = page * PAGE_SIZE;
        int end = Math.min(first + PAGE_SIZE, count);
        return new Page(first, end, first > 0, end < count);
    }

    /** Highlighted row inside the page, accounting for a leading page row. */
    public static int highlight(Page page, int selected) {
        int index = Math.max(page.first(), Math.min(page.end() - 1, selected));
        return (page.previous() ? 1 : 0) + index - page.first();
    }

    /** Stock row text: "<" when paged back, the save names, ">" when paged forward. */
    public static String[] rows(List<String> labels, Page page) {
        var rows = new String[page.rows()];
        int offset = page.previous() ? 1 : 0;
        if (page.previous()) rows[0] = "<";
        for (int i = page.first(); i < page.end(); i++) {
            rows[offset + i - page.first()] = labels.get(i);
        }
        if (page.next()) rows[rows.length - 1] = ">";
        return rows;
    }

    /** Slot index chosen by a row, or -1 when the row is a page arrow. */
    public static int choose(Page page, int row) {
        int offset = page.previous() ? 1 : 0;
        int count = page.end() - page.first();
        if (row < offset || row >= offset + count) return -1;
        return page.first() + row - offset;
    }

    /** Selection after choosing a page arrow, or -1 when the row is a slot. */
    public static int next(Page page, int row) {
        if (page.previous() && row == 0) return page.first() - 1;
        if (page.next() && row == page.rows() - 1) return page.end();
        return -1;
    }
}
