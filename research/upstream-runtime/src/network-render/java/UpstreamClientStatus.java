import arc.math.Mathf;
import arc.util.Time;
import arc.util.Tmp;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.content.StatusEffects;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.type.StatusEffect;
import port.render.UpstreamEffectGate;

/**
 * Advances status timers for the graphical client. The port does not run the
 * stock unit simulation, so a status applied by a client-side bullet hit would
 * otherwise never expire. This uses the public Statusc API and the pinned
 * StatusEffect data; periodic damage remains server-authoritative, while the
 * stock status Fx is spawned with the same chance/offset/colour rules as
 * StatusEffect.update.
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
                    spawnEffect(unit, effect);
                    continue;
                }
                float next = Math.max(0f, duration - delta);
                if (next <= 0f) {
                    unit.unapply(effect);
                } else {
                    unit.setDuration(effect, next);
                    applied.set(effect.id);
                    spawnEffect(unit, effect);
                }
            }
        }
    }

    private static void spawnEffect(Unit unit, StatusEffect effect) {
        if (!UpstreamEffectGate.enabled() || effect.effect == Fx.none
                || !Mathf.chanceDelta(effect.effectChance)
                || (Vars.player != null && unit.inFogTo(Vars.player.team()))) {
            return;
        }
        // Same offset, colour and parent rule as the pinned StatusEffect.update.
        Tmp.v1.rnd(Mathf.range(unit.type.hitSize / 2f));
        effect.effect.at(unit.x + Tmp.v1.x, unit.y + Tmp.v1.y, 0f, effect.color,
            effect.parentizeEffect ? unit : null);
    }
}
