/** Stock pause settings submenu for the ported HUD toggles. */
public final class UpstreamSettingsMenu {
    private int selected;
    private boolean coreItems;
    private boolean minimap;
    private boolean weather;
    private boolean performance;
    private boolean position;

    public void open(boolean coreItems, boolean minimap, boolean weather, boolean performance, boolean position) {
        selected = 0;
        this.coreItems = coreItems;
        this.minimap = minimap;
        this.weather = weather;
        this.performance = performance;
        this.position = position;
    }

    public int selected() {
        return selected;
    }

    public boolean coreItems() {
        return coreItems;
    }

    public boolean minimap() {
        return minimap;
    }

    public boolean weather() {
        return weather;
    }

    public boolean performance() {
        return performance;
    }

    public boolean position() {
        return position;
    }

    public void move(int direction) {
        if (direction == 0) return;
        selected = Math.floorMod(selected + direction, 7);
    }

    /** Toggles the selected setting and returns its new value; Back returns false. */
    public boolean toggleSelected() {
        switch (selected) {
            case 0:
                coreItems = !coreItems;
                return coreItems;
            case 1:
                minimap = !minimap;
                return minimap;
            case 2:
                weather = !weather;
                return weather;
            case 3:
                performance = !performance;
                return performance;
            case 4:
                position = !position;
                return position;
            default:
                return false;
        }
    }

    boolean soundSelected() {
        return selected == 5;
    }

    public boolean backSelected() {
        return selected == 6;
    }
}
