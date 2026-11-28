/** Stock offline pause-menu selection and quit confirmation state. */
public final class UpstreamPauseMenu {
    public enum Action {
        NONE(""), RESUME("back"), RESEARCH("research"), LOAD("loadgame"),
        SAVE("savegame"), SETTINGS("settings"), QUIT("quit");

        final String labelKey;

        Action(String labelKey) {
            this.labelKey = labelKey;
        }
    }

    private static final Action[] standalone = {Action.RESUME, Action.SAVE, Action.QUIT};
    // Stock mobile row order: back, settings, save, load (hostserver is not wired yet).
    private static final Action[] standaloneSettings = {
        Action.RESUME, Action.SETTINGS, Action.SAVE, Action.LOAD, Action.QUIT
    };
    private static final Action[] campaign = {
        Action.RESUME, Action.RESEARCH, Action.LOAD, Action.SAVE, Action.SETTINGS, Action.QUIT
    };

    private Action[] actions = standalone;
    private int selected;
    private boolean confirmingQuit;

    public void open() {
        open(3);
    }

    public void open(int optionCount) {
        open(switch (Math.max(1, Math.min(6, optionCount))) {
            case 1 -> new Action[]{Action.RESUME};
            case 2 -> new Action[]{Action.RESUME, Action.SAVE};
            case 3 -> standalone;
            case 4 -> new Action[]{Action.RESUME, Action.RESEARCH, Action.SAVE, Action.QUIT};
            case 5 -> new Action[]{Action.RESUME, Action.RESEARCH, Action.LOAD, Action.SAVE, Action.QUIT};
            default -> campaign;
        });
    }

    void openWorld(boolean hasResearch) {
        open(hasResearch ? campaign : standaloneSettings);
    }

    private void open(Action[] layout) {
        actions = layout;
        selected = 0;
        confirmingQuit = false;
    }

    public int options() {
        return actions.length;
    }

    Action actionAt(int row) {
        return actions[row];
    }

    public int selected() {
        return selected;
    }

    public boolean confirmingQuit() {
        return confirmingQuit;
    }

    public void move(int direction) {
        if (direction == 0 || confirmingQuit) return;
        selected = Math.floorMod(selected + direction, actions.length);
    }

    public Action activate() {
        if (confirmingQuit) {
            confirmingQuit = false;
            return Action.QUIT;
        }
        Action action = actionAt(selected);
        if (action != Action.QUIT) return action;
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
