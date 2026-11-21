import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Locale;

/** Maps libctru's system language to the original game's Arc bundle. */
final class UpstreamInterfaceLocale {
    static I18NBundle load(Fi path, int language) {
        Locale locale = switch (language) {
            case 0 -> Locale.JAPANESE;
            case 2 -> Locale.FRENCH;
            case 3 -> Locale.GERMAN;
            case 4 -> Locale.ITALIAN;
            case 5 -> new Locale("es");
            case 6 -> Locale.SIMPLIFIED_CHINESE;
            case 7 -> Locale.KOREAN;
            case 8 -> new Locale("nl");
            case 9 -> new Locale("pt", "PT");
            case 10 -> new Locale("ru");
            case 11 -> Locale.TRADITIONAL_CHINESE;
            default -> Locale.ROOT;
        };
        return I18NBundle.createBundle(path, locale);
    }
}
