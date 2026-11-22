/** A short-lived HUD message for stock campaign events such as sector capture. */
public final class UpstreamNotification {
    private static String text;
    private static float remaining;

    private UpstreamNotification() {
    }

    public static void show(String message, float seconds) {
        text = message;
        remaining = Math.max(0f, seconds);
    }

    public static void update(float delta) {
        if (remaining <= 0f) {
            text = null;
            return;
        }
        remaining -= delta;
        if (remaining <= 0f) {
            text = null;
            remaining = 0f;
        }
    }

    public static String current() {
        return remaining > 0f ? text : null;
    }

    public static float remaining() {
        return remaining;
    }

    public static void clear() {
        text = null;
        remaining = 0f;
    }
}
