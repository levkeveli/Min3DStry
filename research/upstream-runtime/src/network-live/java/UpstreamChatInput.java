import arc.Core;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import mindustry.Vars;

/** Live touch composer. Simulation, packet updates and rendering continue during text entry. */
final class UpstreamChatInput {
    private static final int SUBMIT_PRESSED = 1 << 17;
    private static final int CANCEL_PRESSED = 1 << 19;
    private static final int OPEN_PRESSED = 1 << 22;
    private static final String LETTERS = "1234567890qwertyuiopasdfghjkl-zxcvbnm.,/";
    private static final String SYMBOLS = "!@#$%^&*()[]{}<>+=_:;?\"'~`|\\`-.,/        ";
    private final StringBuilder text = new StringBuilder();
    private boolean open;
    private boolean shift;
    private boolean symbols;
    private byte[] labels;

    static boolean requested(int buttons) {
        return (buttons & OPEN_PRESSED) != 0;
    }

    boolean open() {
        return open;
    }

    String update(int buttons, int touch) {
        if (!open) {
            if (requested(buttons)) {
                open = true;
                System.out.println("UPSTREAM_LIVE_CHAT_INPUT state=open");
            }
            return null;
        }
        if (requested(buttons) || (buttons & CANCEL_PRESSED) != 0) return close(false);
        if ((buttons & SUBMIT_PRESSED) != 0) return close(true);
        if (touch < 0) return null;
        int x = touch & 0xffff, y = touch >>> 16;
        if (x >= 320 || y >= 240) return null;
        if (y >= 195) {
            if (x < 64) {
                shift = !shift;
            } else if (x < 128) {
                symbols = !symbols;
            } else if (x < 256) {
                append(' ');
            } else if (text.length() > 0) {
                text.setLength(text.length() - 1);
            }
        } else if (y >= 36) {
            int index = (y - 36) / 39 * 10 + x / 32;
            var layout = layout();
            if (index < layout.length() && layout.charAt(index) != ' ') append(layout.charAt(index));
        }
        labels = null;
        return null;
    }

    private String close(boolean submit) {
        String message = submit ? text.toString().trim() : "";
        open = false;
        text.setLength(0);
        shift = false;
        symbols = false;
        labels = null;
        System.out.println("UPSTREAM_LIVE_CHAT_INPUT state=closed");
        return message.isEmpty() ? null : message;
    }

    private void append(char value) {
        if (text.length() < Vars.maxTextLength) text.append(value);
    }

    private String layout() {
        return symbols ? SYMBOLS : shift ? LETTERS.toUpperCase(Locale.ROOT) : LETTERS;
    }

    byte[] labels() {
        if (labels == null) {
            var result = new StringBuilder();
            result.append(Core.bundle.get("keybind.chat.name")).append("  A / B\0");
            result.append(text.substring(Math.max(0, text.length() - 26))).append('\0');
            String layout = layout();
            for (int i = 0; i < 40; i++) {
                if (i < layout.length()) result.append(layout.charAt(i));
                result.append('\0');
            }
            labels = result.toString().getBytes(StandardCharsets.UTF_8);
        }
        return labels;
    }
}
