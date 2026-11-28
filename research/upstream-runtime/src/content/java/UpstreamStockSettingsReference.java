import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.util.I18NBundle;
import java.util.Locale;

/**
 * Checks the mobile-applicable stock settings table against the pinned bundle
 * and arc.Settings: every entry has a stock label, checks toggle and restore,
 * sliders hit both bounds through the stock step without leaving the range, and
 * the pinned SettingsMenuDialog defaults/ranges still match the table.
 */
public final class UpstreamStockSettingsReference {
    public static void main(String[] args) {
        if (args.length < 1) {
            throw new IllegalArgumentException("Expected the stock bundle base path, e.g. assets/bundles/bundle");
        }
        Core.bundle = I18NBundle.createBundle(new Fi(args[0]), Locale.ROOT);
        var settings = new Settings() {
            @Override
            public Fi getDataDirectory() {
                return new Fi("stock-settings-reference");
            }
        };

        var expectedDefaults = ObjectMap.of(
            "alwaysmusic", 0, "musicvol", 100, "sfxvol", 100, "ambientvol", 100,
            "saveinterval", 60, "autotarget", 1, "communityservers", 1, "savecreate", 1,
            "blockreplace", 1, "conveyorpathfinding", 1, "hints", 1, "doubletapmine", 0,
            "commandmodehold", 1, "screenshake", 4, "chatopacity", 100, "lasersopacity", 100,
            "unitlaseropacity", 100, "bridgeopacity", 100, "effects", 1, "atmosphere", 1,
            "drawlight", 1, "destroyedblocks", 1, "blockstatus", 0, "playerchat", 1,
            "coreitems", 1, "minimap", 1, "smoothcamera", 1, "position", 0, "fps", 0,
            "playerindicators", 1, "showpings", 1, "showotherbuildplans", 1, "indicators", 1,
            "showweather", 1, "animatedwater", 1
        );
        var expectedBounds = ObjectMap.of(
            "musicvol", "0:100:1", "sfxvol", "0:100:1", "ambientvol", "0:100:1",
            "saveinterval", "10:600:10", "screenshake", "0:8:1", "chatopacity", "0:100:5",
            "lasersopacity", "0:100:5", "unitlaseropacity", "0:100:5", "bridgeopacity", "0:100:5"
        );

        int entries = 0, checks = 0, sliders = 0;
        for (String category : UpstreamStockSettings.categories()) {
            var list = UpstreamStockSettings.entries(category);
            if (list.isEmpty()) throw new AssertionError("Stock category has no settings: " + category);
            String categoryLabel = Core.bundle.get("settings." + category);
            if (categoryLabel == null || categoryLabel.equals("settings." + category)) {
                throw new AssertionError("Missing stock category label: settings." + category);
            }
            for (var entry : list) {
                entries++;
                String label = UpstreamStockSettings.label(entry);
                if (label == null || label.isEmpty() || label.equals(entry.key())) {
                    throw new AssertionError("Missing stock label for setting." + entry.key() + ".name");
                }
                Integer pinnedDefault = (Integer)expectedDefaults.get(entry.key());
                if (pinnedDefault == null || pinnedDefault != entry.def()) {
                    throw new AssertionError("Default diverged from SettingsMenuDialog: " + entry.key()
                        + " table=" + entry.def() + " pinned=" + pinnedDefault);
                }
                if (entry.kind() == UpstreamStockSettings.Kind.CHECK) {
                    boolean initial = settings.getBool(entry.key(), entry.def() != 0);
                    UpstreamStockSettings.toggle(settings, entry);
                    boolean toggled = settings.getBool(entry.key(), initial);
                    UpstreamStockSettings.toggle(settings, entry);
                    boolean restored = settings.getBool(entry.key(), !initial);
                    if (toggled == initial || restored != initial) {
                        throw new AssertionError("Stock check did not toggle and restore: " + entry.key());
                    }
                    checks++;
                } else {
                    String bounds = entry.min() + ":" + entry.max() + ":" + entry.step();
                    if (!bounds.equals((String)expectedBounds.get(entry.key()))) {
                        throw new AssertionError("Range diverged from SettingsMenuDialog: " + entry.key()
                            + " table=" + bounds + " pinned=" + expectedBounds.get(entry.key()));
                    }
                    int initial = settings.getInt(entry.key(), entry.def());
                    if (initial != entry.def()) {
                        throw new AssertionError("Stock slider default changed: " + entry.key());
                    }
                    UpstreamStockSettings.adjust(settings, entry, -1000);
                    int low = UpstreamStockSettings.number(settings, entry);
                    UpstreamStockSettings.adjust(settings, entry, 1000);
                    int high = UpstreamStockSettings.number(settings, entry);
                    if (low != entry.min() || high != entry.max()) {
                        throw new AssertionError("Stock slider clamp changed: " + entry.key()
                            + " low=" + low + " high=" + high
                            + " expected=" + entry.min() + ".." + entry.max());
                    }
                    settings.put(entry.key(), initial);
                    sliders++;
                }
            }
        }
        System.out.println("UPSTREAM_STOCK_SETTINGS entries=" + entries + " checks=" + checks
            + " sliders=" + sliders + " labels=ok pinned=ok clamp=ok toggle=ok");
    }
}
