import arc.ApplicationListener;
import arc.Core;

/**
 * Disposes registered listener fibers for one-shot probes that exit without
 * app teardown. Currently used by the wave-routing probe, whose AsyncCore
 * creates listener-bearing infrastructure; other one-shot probes do not.
 */
final class ProbeListenerShutdown {
    private ProbeListenerShutdown() {
    }

    static void dispose() {
        if (Core.app == null) return;
        for (ApplicationListener listener : Core.app.getListeners().copy()) {
            try {
                listener.dispose();
            } catch (Throwable failure) {
                System.err.println("UPSTREAM_PROBE_DISPOSE_ERROR listener="
                    + listener.getClass().getName() + " " + failure);
            }
        }
    }
}
