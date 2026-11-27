import arc.util.Time;
import mindustry.Vars;
import mindustry.content.StatusEffects;
import mindustry.gen.Groups;

/**
 * Advances status timers for the graphical client. The port does not run the
 * stock unit simulation, so a status applied by a client-side bullet hit would
 * otherwise never expire. This uses the public Statusc API and the pinned
 * StatusEffect data; periodic StatusEffect.update (damage and its Fx) stays
 * server-authoritative and is not simulated here.
 */
final class UpstreamClientStatus {
    private UpstreamClientStatus() {
    }

    static void update() {
        float delta = Time.delta;
        for (var unit : Groups.unit) {
            if (unit.dead()) continue;

            // StatusComp.update applies the current floor status while grounded.
            var floor = unit.floorOn();
            if (floor != null && unit.isGrounded() && !unit.type.hovering) {
                unit.apply(floor.status, floor.statusDuration);
            }

            // StatusComp.update rebuilds the applied bits every tick.
            var applied = unit.statusBits();
            applied.clear();

            for (var effect : Vars.content.statusEffects()) {
                if (effect == StatusEffects.none) continue;
                float duration = unit.getDuration(effect);
                if (duration <= 0f) continue;
                // Permanent and dynamic entries are not timer-managed by the stock loop.
                if (effect.permanent || effect.dynamic) {
                    applied.set(effect.id);
                    continue;
                }
                float next = Math.max(0f, duration - delta);
                if (next <= 0f) {
                    unit.unapply(effect);
                } else {
                    unit.setDuration(effect, next);
                    applied.set(effect.id);
                }
            }
        }
    }
}
