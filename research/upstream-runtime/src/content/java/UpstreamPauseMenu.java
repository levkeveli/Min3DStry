/** Stock offline pause-menu selection and quit confirmation state. */
public final class UpstreamPauseMenu {
    public enum Action {
        NONE, RESUME, RESEARCH, SAVE, QUIT
    }

    private int options = 3;
    private int selected;
    private boolean confirmingQuit;

    public void open() {
        open(3);
    }

    public void open(int optionCount) {
        options = Math.max(1, Math.min(4, optionCount));
        selected = 0;
        confirmingQuit = false;
    }

    public int options() {
        return options;
    }

    public int selected() {
        return selected;
    }

    public boolean confirmingQuit() {
        return confirmingQuit;
    }

    public void move(int direction) {
        if (direction == 0 || confirmingQuit) return;
        selected = Math.floorMod(selected + direction, options);
    }

    public Action activate() {
        if (confirmingQuit) {
            confirmingQuit = false;
            return Action.QUIT;
        }
        if (selected == 0) return Action.RESUME;
        if (options == 4 && selected == 1) return Action.RESEARCH;
        int saveIndex = options == 4 ? 2 : 1;
        if (selected == saveIndex) return Action.SAVE;
        confirmingQuit = true;
        return Action.NONE;
    }

    public Action cancel() {
        if (confirmingQuit) {
            confirmingQuit = false;
            return Action.NONE;
        }
        return Action.RESUME;
    }
}
