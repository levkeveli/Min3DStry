import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the mobile-applicable stock settings table against the pinned bundle,
 * the pinned SettingsMenuDialog source and arc.Settings: every entry has a
 * stock label and category header, the table's defaults/ranges match upstream,
 * checks toggle and restore, sliders reach both bounds, and the stock value
 * formatting produces text.
 */
public final class UpstreamStockSettingsReference {
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                "Expected the stock bundle base and SettingsMenuDialog.java path");
        }
        Core.bundle = I18NBundle.createBundle(new Fi(args[0]), Locale.ROOT);
        var settings = new Settings() {
            @Override
            public Fi getDataDirectory() {
                return new Fi("stock-settings-reference");
            }
        };

        Map<String, Integer> defaults = new LinkedHashMap<>();
        Map<String, String> bounds = new LinkedHashMap<>();
        parseUpstream(Path.of(args[1]), defaults, bounds);

        int entries = 0, checks = 0, sliders = 0;
        for (String category : UpstreamStockSettings.categories()) {
            var list = UpstreamStockSettings.entries(category);
            if (list.isEmpty()) throw new AssertionError("Stock category has no settings: " + category);
            String header = Core.bundle.get("settings." + category);
            if (header == null || header.equals("settings." + category) || header.startsWith("???")) {
                throw new AssertionError("Missing stock category label: settings." + category);
            }
            for (var entry : list) {
                entries++;
                String label = UpstreamStockSettings.label(entry);
                if (label == null || label.isEmpty() || label.equals(entry.key())) {
                    throw new AssertionError("Missing stock label for setting." + entry.key() + ".name");
                }
                Integer pinnedDefault = defaults.get(entry.key());
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
                    String range = entry.min() + ":" + entry.max() + ":" + entry.step();
                    if (!range.equals(bounds.get(entry.key()))) {
                        throw new AssertionError("Range diverged from SettingsMenuDialog: " + entry.key()
                            + " table=" + range + " pinned=" + bounds.get(entry.key()));
                    }
                    UpstreamStockSettings.adjust(settings, entry, -100000);
                    int low = UpstreamStockSettings.number(settings, entry);
                    UpstreamStockSettings.adjust(settings, entry, 100000);
                    int high = UpstreamStockSettings.number(settings, entry);
                    if (low != entry.min() || high != entry.max()) {
                        throw new AssertionError("Stock slider clamp changed: " + entry.key()
                            + " low=" + low + " high=" + high
                            + " expected=" + entry.min() + ".." + entry.max());
                    }
                    settings.put(entry.key(), entry.def());
                    sliders++;
                }
                String display = UpstreamStockSettings.display(settings, entry);
                String row = UpstreamStockSettings.row(settings, entry);
                if (display == null || display.isEmpty() || row == null || !row.contains(display)) {
                    throw new AssertionError("Stock value formatting failed: " + entry.key());
                }
                if (entry.format() == UpstreamStockSettings.Format.PERCENT && !display.endsWith("%")) {
                    throw new AssertionError("Stock percent formatting changed: " + entry.key());
                }
                if (entry.format() == UpstreamStockSettings.Format.SHAKE && !display.endsWith("x")) {
                    throw new AssertionError("Stock shake formatting changed: " + entry.key());
                }
            }
        }
        System.out.println("UPSTREAM_STOCK_SETTINGS entries=" + entries + " checks=" + checks
            + " sliders=" + sliders + " labels=ok source=ok format=ok clamp=ok toggle=ok");
    }

    /** Reads checkPref/sliderPref defaults and ranges from the pinned upstream dialog. */
    private static void parseUpstream(Path source, Map<String, Integer> defaults,
                                      Map<String, String> bounds) throws IOException {
        Pattern check = Pattern.compile("checkPref\\(\"([A-Za-z0-9-]+)\", (true|false|!mobile|mobile)");
        Pattern slider = Pattern.compile(
            "sliderPref\\(\"([A-Za-z0-9-]+)\", ([0-9]+), ([0-9* ]+), ([0-9* ]+)(?:, ([0-9]+))?");
        for (String line : Files.readAllLines(source, StandardCharsets.UTF_8)) {
            Matcher c = check.matcher(line);
            if (c.find()) {
                defaults.put(c.group(1), "true".equals(c.group(2)) || "!mobile".equals(c.group(2)) ? 1 : 0);
                continue;
            }
            Matcher s = slider.matcher(line);
            if (s.find()) {
                defaults.put(s.group(1), Integer.parseInt(s.group(2)));
                int min = eval(s.group(3)), max = eval(s.group(4));
                int step = s.group(5) == null ? 1 : Integer.parseInt(s.group(5));
                bounds.put(s.group(1), min + ":" + max + ":" + step);
            }
        }
    }

    private static int eval(String value) {
        String[] parts = value.split("\\*");
        int result = 1;
        for (String part : parts) result *= Integer.parseInt(part.trim());
        return result;
    }
}
