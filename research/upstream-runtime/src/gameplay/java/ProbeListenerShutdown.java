import arc.ApplicationListener;
import arc.Core;

/** Disposes registered listener fibers for one-shot probes that exit without app teardown. */
final class ProbeListenerShutdown {
    private ProbeListenerShutdown() {
    }

    static void dispose() {
        if (Core.app == null) return;
        for (ApplicationListener listener : Core.app.getListeners().copy()) {
            try {
                listener.dispose();
            } catch (Throwable ignored) {
            }
        }
    }
}
