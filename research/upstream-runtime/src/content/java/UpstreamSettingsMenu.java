/** Stock pause settings submenu with the upstream core-items toggle. */
public final class UpstreamSettingsMenu {
    private int selected;
    private boolean coreItems;

    public void open(boolean coreItems) {
        selected = 0;
        this.coreItems = coreItems;
    }

    public int selected() {
        return selected;
    }

    public boolean coreItems() {
        return coreItems;
    }

    public void move(int direction) {
        if (direction == 0) return;
        selected = Math.floorMod(selected + direction, 2);
    }

    public boolean toggleCoreItems() {
        coreItems = !coreItems;
        return coreItems;
    }

    public boolean backSelected() {
        return selected == 1;
    }
}
