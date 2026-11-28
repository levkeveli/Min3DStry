import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads every stock v160.8 locale bundle through the port's interface loader.
 *
 * The graphical client resolves its labels with UpstreamInterfaceLocale, so a
 * bundle that fails to parse, or a locale that loses the shared block/item keys,
 * would silently fall back to raw keys on the handheld. This reference checks
 * each shipped bundle and the keys the HUD and objective labels use.
 */
public final class UpstreamStockLocaleReference {
    private static final String[] required = {
        "block.conveyor.name",
        "block.duo.name",
        "item.copper.name",
        "liquid.water.name",
        "unit.dagger.name",
        "unit.thousands",
        "unit.millions",
        "unit.billions"
    };

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the stock bundle base path, e.g. assets/bundles/bundle");
        }
        Fi base = new Fi(args[0]);
        Fi englishFile = base.exists() ? base : new Fi(args[0] + ".properties");
        if (!englishFile.exists()) throw new IllegalArgumentException("Missing stock bundle base: " + base);

        List<Fi> bundles = new ArrayList<>();
        for (Fi file : base.parent().list()) {
            String name = file.name();
            if (name.startsWith("bundle") && name.endsWith(".properties")) bundles.add(file);
        }
        Collections.sort(bundles, (a, b) -> a.name().compareTo(b.name()));
        if (bundles.size() < 30) {
            throw new AssertionError("Expected the stock bundle set, found only " + bundles.size() + " files");
        }

        int localized = 0;
        for (Fi file : bundles) {
            String requested;
            int language;
            if (file.name().equals("bundle.properties")) {
                requested = "en";
                language = 1;
            } else {
                requested = file.name().substring("bundle_".length(), file.name().length() - ".properties".length());
                language = switch (requested) {
                    case "ja" -> 0;
                    case "fr" -> 2;
                    case "de" -> 3;
                    case "it" -> 4;
                    case "es" -> 5;
                    case "zh_CN" -> 6;
                    case "ko" -> 7;
                    case "nl", "nl_BE" -> 8;
                    case "pt_PT", "pt_BR" -> 9;
                    case "ru" -> 10;
                    case "zh_TW" -> 11;
                    default -> 1;
                };
                localized++;
            }

            // The requested tag selects the locale file; the language index is
            // only the handheld fallback when a locale is not on the SD card.
            I18NBundle bundle = UpstreamInterfaceLocale.load(base, language, requested);
            if (!"en".equals(requested)) {
                if (bundle.getProperties().size == 0) {
                    throw new AssertionError("Locale " + file.name() + " parsed empty");
                }
                if (bundle.getParent() == null) {
                    throw new AssertionError("Locale " + file.name() + " lost its English fallback");
                }
            }
            for (String key : required) {
                String value = bundle.getOrNull(key);
                if (value == null || value.isEmpty()) {
                    throw new AssertionError("Locale " + file.name() + " lost key " + key);
                }
            }
        }

        String english = UpstreamInterfaceLocale.load(base, 1, "en").getNotNull("block.conveyor.name");
        String japanese = UpstreamInterfaceLocale.load(base, 0, "ja").getNotNull("block.conveyor.name");
        if (!"Conveyor".equals(english) || japanese.isEmpty() || "Conveyor".equals(japanese)) {
            throw new AssertionError("Stock catalog labels changed: en=" + english + " ja=" + japanese);
        }

        System.out.println("UPSTREAM_STOCK_LOCALES files=" + bundles.size() + " localized=" + localized
            + " parsed=ok keys=ok fallback=ok en=" + english + " ja=ok");
    }
}
