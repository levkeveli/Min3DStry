import arc.Core;
import arc.Events;
import mindustry.game.EventType.SectorCaptureEvent;
import mindustry.type.Sector;

/**
 * Persists campaign capture state through the upstream SectorInfo settings JSON.
 * Installed by both the live single-player app and the capture probe so the
 * event-to-settings path is exercised before hardware handoff.
 */
public final class UpstreamSectorCapture {
    private static boolean installed;

    private UpstreamSectorCapture() {
    }

    public static void install() {
        if (installed) return;
        installed = true;
        Events.on(SectorCaptureEvent.class, event -> {
            if (event.sector == null) return;
            persist(event.sector);
            String message = "Sector Captured";
            if (Core.bundle != null) {
                String value = Core.bundle.get("sector.capture.current");
                if (value != null && !value.startsWith("???")) message = value;
            }
            UpstreamNotification.show(message, 5f);
            System.out.println("UPSTREAM_CAMPAIGN_CAPTURED sector=" + event.sector.id);
        });
    }

    static void persist(Sector sector) {
        if (sector == null) return;
        sector.info.prepare(sector);
        sector.saveInfo();
    }
}
