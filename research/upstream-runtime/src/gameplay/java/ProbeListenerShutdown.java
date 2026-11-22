import arc.ApplicationListener;
import arc.Core;
import arc.struct.Seq;

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
        Seq<ApplicationListener> listeners;
        try {
            listeners = Core.app.getListeners().copy();
        } catch (Throwable failure) {
            System.err.println("UPSTREAM_PROBE_DISPOSE_ERROR listeners " + failure);
            return;
        }
        for (ApplicationListener listener : listeners) {
            try {
                listener.dispose();
            } catch (Throwable failure) {
                System.err.println("UPSTREAM_PROBE_DISPOSE_ERROR listener="
                    + listener.getClass().getName() + " " + failure);
            }
            try {
                Core.app.removeListener(listener);
            } catch (Throwable failure) {
                System.err.println("UPSTREAM_PROBE_DISPOSE_ERROR remove="
                    + listener.getClass().getName() + " " + failure);
            }
        }
    }
}
