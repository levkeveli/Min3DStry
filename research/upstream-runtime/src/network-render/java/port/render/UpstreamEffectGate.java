package port.render;

/**
 * Enables the pinned {@code Effect} pipeline on the graphical client. The port
 * renders without the desktop {@code Renderer}, so upstream's
 * {@code !headless && Vars.renderer.enableEffects} gate would discard every
 * stock effect, including turret lightning arcs and bullet impact visuals.
 * The upstream source is adapted to consult this gate first; when it is
 * disabled the original v160.8 behavior is unchanged.
 */
public final class UpstreamEffectGate {
    private static boolean enabled;

    private UpstreamEffectGate() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void enable() {
        enabled = true;
    }

    public static void disable() {
        enabled = false;
    }
}
