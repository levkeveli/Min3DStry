import arc.Core;
import arc.util.Strings;
import java.util.ArrayList;
import mindustry.ctype.UnlockableContent;
import mindustry.gen.Iconc;
import mindustry.type.UnitType;
import mindustry.world.blocks.production.Separator;
import mindustry.world.blocks.units.Reconstructor;
import mindustry.world.blocks.units.UnitAssembler;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.meta.Stat;
import mindustry.world.meta.StatValues;

final class UpstreamCoreDatabaseProduction {
    private UpstreamCoreDatabaseProduction() {}

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
