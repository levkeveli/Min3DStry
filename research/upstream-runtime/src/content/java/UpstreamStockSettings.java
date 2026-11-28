import arc.Core;
import arc.Settings;
import arc.struct.Seq;
import arc.util.Strings;

/**
 * Mobile-applicable subset of the pinned v160.8 settings preferences.
 *
 * The keys, defaults, ranges and value formatting mirror
 * SettingsMenuDialog.addSettings() for a touch/mobile target. The handheld
 * settings routes read and write these through arc.Settings exactly like the
 * desktop dialog, so the same saved values drive the port.
 */
public final class UpstreamStockSettings {
    public enum Kind { CHECK, SLIDER }

    public enum Format { PLAIN, PERCENT, SHAKE, SECONDS }

    public record Entry(String category, String key, Kind kind, int min, int max, int step,
                        int def, Format format) {}

    private static final String[] CATEGORIES = {"game", "graphics", "sound"};

    private static final Seq<Entry> entries = Seq.with(
        // sound
        check("sound", "alwaysmusic", false),
        slider("sound", "musicvol", 100, 0, 100, 1, Format.PERCENT),
        slider("sound", "sfxvol", 100, 0, 100, 1, Format.PERCENT),
        slider("sound", "ambientvol", 100, 0, 100, 1, Format.PERCENT),
        // game
        slider("game", "saveinterval", 60, 10, 600, 10, Format.SECONDS),
        check("game", "autotarget", true),
        check("game", "communityservers", true),
        check("game", "savecreate", true),
        check("game", "blockreplace", true),
        check("game", "conveyorpathfinding", true),
        check("game", "hints", true),
        check("game", "doubletapmine", false),
        check("game", "commandmodehold", true),
        // graphics
        slider("graphics", "screenshake", 4, 0, 8, 1, Format.SHAKE),
        slider("graphics", "chatopacity", 100, 0, 100, 5, Format.PERCENT),
        slider("graphics", "lasersopacity", 100, 0, 100, 5, Format.PERCENT),
        slider("graphics", "unitlaseropacity", 100, 0, 100, 5, Format.PERCENT),
        slider("graphics", "bridgeopacity", 100, 0, 100, 5, Format.PERCENT),
        check("graphics", "effects", true),
        check("graphics", "atmosphere", true),
        check("graphics", "drawlight", true),
        check("graphics", "destroyedblocks", true),
        check("graphics", "blockstatus", false),
        check("graphics", "playerchat", true),
        check("graphics", "coreitems", true),
        check("graphics", "minimap", true),
        check("graphics", "smoothcamera", true),
        check("graphics", "position", false),
        check("graphics", "fps", false),
        check("graphics", "playerindicators", true),
        check("graphics", "showpings", true),
        check("graphics", "showotherbuildplans", true),
        check("graphics", "indicators", true),
        check("graphics", "showweather", true),
        check("graphics", "animatedwater", true)
    );

    private UpstreamStockSettings() {
    }

    private static Entry check(String category, String key, boolean def) {
        return new Entry(category, key, Kind.CHECK, 0, 1, 1, def ? 1 : 0, Format.PLAIN);
    }

    private static Entry slider(String category, String key, int def, int min, int max, int step,
                                Format format) {
        return new Entry(category, key, Kind.SLIDER, min, max, step, def, format);
    }

    public static String[] categories() {
        return CATEGORIES.clone();
    }

    /** Localized stock category name for a category id. */
    public static String categoryName(String category) {
        return Core.bundle.get("settings." + category);
    }

    public static Seq<Entry> entries(String category) {
        return entries.select(entry -> entry.category().equals(category));
    }

    /** Display label from the pinned bundle, defaulting to the raw key. */
    public static String label(Entry entry) {
        return Core.bundle.get("setting." + entry.key() + ".name", entry.key());
    }

    public static boolean value(Settings settings, Entry entry) {
        if (entry.kind() == Kind.CHECK) {
            return settings.getBool(entry.key(), entry.def() != 0);
        }
        return settings.getInt(entry.key(), entry.def()) != 0;
    }

    public static int number(Settings settings, Entry entry) {
        return entry.kind() == Kind.CHECK
            ? settings.getBool(entry.key(), entry.def() != 0) ? 1 : 0
            : Math.max(entry.min(), Math.min(entry.max(), settings.getInt(entry.key(), entry.def())));
    }

    public static void toggle(Settings settings, Entry entry) {
        settings.put(entry.key(), !settings.getBool(entry.key(), entry.def() != 0));
    }

    /** Steps a slider by one increment, clamped to the stock range. */
    public static void adjust(Settings settings, Entry entry, int direction) {
        if (entry.kind() != Kind.SLIDER || direction == 0) return;
        int current = number(settings, entry);
        int next = Math.max(entry.min(), Math.min(entry.max(), current + direction * entry.step()));
        if (next != current) settings.put(entry.key(), next);
    }

    /** Value text formatted like the stock dialog. */
    public static String display(Settings settings, Entry entry) {
        if (entry.kind() == Kind.CHECK) {
            return Core.bundle.get(value(settings, entry) ? "on" : "off");
        }
        int value = number(settings, entry);
        return switch (entry.format()) {
            case PERCENT -> value + "%";
            case SHAKE -> (value / 4f) + "x";
            case SECONDS -> Core.bundle.format("setting.seconds", value);
            case PLAIN -> Integer.toString(value);
        };
    }

    /** Row caption for the handheld list: localized label plus current value. */
    public static String row(Settings settings, Entry entry) {
        return Strings.stripColors(label(entry)) + ": " + display(settings, entry);
    }
}
