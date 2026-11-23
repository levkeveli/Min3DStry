/** Stock offline pause-menu selection and quit confirmation state. */
public final class UpstreamPauseMenu {
    public enum Action {
        NONE, RESUME, SAVE, QUIT
    }

    private static final int OPTIONS = 3;
    private int selected;
    private boolean confirmingQuit;

    public void open() {
        selected = 0;
        confirmingQuit = false;
    }

    public int selected() {
        return selected;
    }

    public boolean confirmingQuit() {
        return confirmingQuit;
    }

    public void move(int direction) {
        if (direction == 0 || confirmingQuit) return;
        selected = Math.floorMod(selected + direction, OPTIONS);
    }

    public Action activate() {
        if (confirmingQuit) {
            confirmingQuit = false;
            return Action.QUIT;
        }
        if (selected == 0) return Action.RESUME;
        if (selected == 1) return Action.SAVE;
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
