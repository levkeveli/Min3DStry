import arc.Core;
import arc.math.Mathf;
import arc.util.Strings;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Iconc;
import mindustry.type.UnitType;
import mindustry.world.blocks.defense.ForceProjector;
import mindustry.world.blocks.defense.ShockMine;
import mindustry.world.blocks.distribution.Junction;
import mindustry.world.blocks.distribution.MassDriver;
import mindustry.world.blocks.power.ImpactReactor;
import mindustry.world.blocks.power.NuclearReactor;
import mindustry.world.blocks.production.Separator;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.units.Reconstructor;
import mindustry.world.blocks.units.UnitAssembler;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatValues;
import mindustry.world.meta.StatUnit;

final class UpstreamCoreDatabaseStats {
    private UpstreamCoreDatabaseStats() {}

    static String fallback(Stat stat, UnlockableContent content) {
        if (stat == Stat.output) return productionOutput(content);
        if (stat == Stat.unitType && content instanceof CoreBlock core) {
            return core.unitType.localizedName;
        }
        if (stat == Stat.legSplashDamage && content instanceof UnitType unit) {
            return Core.bundle.format("bullet.splashdamage", Strings.autoFixed(unit.legSplashDamage, 2),
                Strings.autoFixed(unit.legSplashRange / Vars.tilesize, 2)) + " " + StatUnit.perLeg.localized();
        }
        if (stat == Stat.itemCapacity && content instanceof Junction junction) {
            return Strings.autoFixed(junction.capacity, 2) + " " + StatUnit.items.localized() + " " + StatUnit.perSide.localized();
        }
        if (stat == Stat.reload && content instanceof MassDriver driver) {
            float rate = 60f / driver.reload;
            return Strings.autoFixed(rate, 2) + StatUnit.perSecond.localized() + " ~ "
                + Strings.autoFixed(driver.itemCapacity * rate, 2) + " " + StatUnit.itemsSecond.localized();
        }
        if (stat == Stat.damage && content instanceof ShockMine mine) {
            return Core.bundle.format("bullet.lightning", mine.tendrils, Strings.autoFixed(mine.damage, 2));
        }
        if (stat == Stat.meltdownTime && content instanceof NuclearReactor reactor) {
            float average = (reactor.itemDuration / 60f) / (1f + reactor.heatConsumeRate / 2f);
            float value = 30f * reactor.heating * reactor.itemCapacity * average;
            if (value <= 1f) return Core.bundle.get("bar.nevermelts");
            float seconds = reactor.itemCapacity * average * (1f - Mathf.sqrt(1f - 1f / value));
            return Strings.autoFixed(seconds, 2) + " " + StatUnit.seconds.localized() + " " + Core.bundle.get("bar.whenfull");
        }
        if (stat == Stat.warmupTime && content instanceof ImpactReactor reactor && reactor.consPower != null) {
            float max = reactor.statWarmup(0.001f);
            float equal = reactor.statWarmup(1f - Mathf.pow(reactor.consPower.usage / reactor.powerProduction, 1f / 5f));
            return Strings.autoFixed(max, 2) + " " + StatUnit.seconds.localized() + " ~ "
                + Strings.autoFixed(equal, 2) + " " + StatUnit.seconds.localized() + " " + StatUnit.powerEquilibrium.localized()
                + "\n" + Core.bundle.format("bar.lerpwarmuptime", Strings.autoFixed(reactor.statWarmup(0.1f), 2),
                    Strings.autoFixed(reactor.statWarmup(0.01f), 2));
        }
        if (stat == Stat.booster && content instanceof ForceProjector projector) return projectorCoolants(projector);
        return null;
    }

    private static String projectorCoolants(ForceProjector projector) {
        var rows = new ArrayList<String>();
        for (var liquid : Vars.content.liquids()) {
            if (!projector.consumesLiquid(liquid)) continue;
            float cooling = projector.cooldownLiquid * (1f + (liquid.heatCapacity - 0.4f) * 0.9f);
            float regeneration = (projector.cooldownNormal * cooling - projector.cooldownNormal) * 60f;
            float cooldown = (projector.shieldHealth / (projector.cooldownBrokenBase * cooling)
                - projector.shieldHealth / projector.cooldownBrokenBase) / 60f;
            rows.add(liquid.localizedName + " " + Strings.autoFixed(projector.coolantConsumption * 60f, 2)
                + StatUnit.perSecond.localized() + " +" + Core.bundle.format("bar.regenerationrate", Strings.autoFixed(regeneration, 2))
                + " " + Core.bundle.format("ability.stat.cooldown", Strings.autoFixed(cooldown, 2)));
        }
        return String.join("\n", rows);
    }

    static String productionOutput(UnlockableContent content) {
        var rows = new ArrayList<String>();
        if (content instanceof UnitFactory factory) {
            for (var plan : factory.plans) {
                rows.add(planTitle(plan.unit, plan.time));
                if (plan.unit.unlockedNow() && !plan.unit.isBanned()) {
                    rows.add(StatValues.items(plan.time, plan.requirements).handheldText());
                }
            }
        } else if (content instanceof UnitAssembler assembler) {
            int tier = 0;
            for (var plan : assembler.plans) {
                rows.add(planTitle(plan.unit, plan.time));
                if (plan.unit.unlockedNow() && !plan.unit.isBanned()) {
                    if (tier > 0) rows.add(Stat.moduleTier.localized() + ": " + tier);
                    if (plan.itemReq != null) rows.add(StatValues.items(plan.itemReq).handheldText());
                    if (plan.requirements != null) {
                        for (var payload : plan.requirements) rows.add(payload.item.localizedName + " " + payload.amount);
                    }
                    if (plan.liquidReq != null) rows.add(StatValues.liquids(1f, plan.liquidReq).handheldText());
                }
                tier++;
            }
        } else if (content instanceof Reconstructor reconstructor) {
            for (var upgrade : reconstructor.upgrades) {
                if (upgrade[0].unlockedNow() && upgrade[1].unlockedNow()) {
                    rows.add(upgrade[0].localizedName + " " + Iconc.right + " " + upgrade[1].localizedName);
                }
            }
        } else if (content instanceof Separator separator) {
            int weight = 0;
            for (var output : separator.results) weight += output.amount;
            for (var output : separator.results) {
                rows.add(output.item.localizedName + " " + (int)((float)output.amount / weight * 100) + "%");
            }
        } else {
            return null;
        }
        rows.removeIf(String::isEmpty);
        return String.join("\n", rows);
    }

    private static String planTitle(UnitType unit, float time) {
        if (unit.isBanned()) return String.valueOf(Iconc.cancel);
        if (!unit.unlockedNow()) return Iconc.lock + " " + Core.bundle.get("unlock.incampaign");
        return unit.localizedName + " " + Strings.autoFixed(time / 60f, 1) + " " + Core.bundle.get("unit.seconds");
    }
}
