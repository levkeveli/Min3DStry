package port.render;

import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.math.Angles;
import arc.math.Mathf;
import arc.util.Time;
import mindustry.Vars;
import mindustry.gen.Unit;
import mindustry.graphics.Drawf;
import mindustry.graphics.Layer;
import mindustry.graphics.Pal;
import mindustry.world.Build;
import mindustry.world.blocks.ConstructBlock;

/** Adapts the pinned BuilderComp preview to a renderer without desktop input. */
public final class UpstreamRemoteBuildPlans {
    private UpstreamRemoteBuildPlans() {
    }

    public static void draw(Unit unit) {
        if (Vars.control != null || unit.isLocal() || !unit.activelyBuilding() || Vars.state.isPaused()) {
            unit.drawBuilding();
            return;
        }

        var plan = unit.buildPlan();
        var tile = plan.tile();
        if (tile == null || !unit.within(plan, Vars.state.rules.infiniteResources ? Float.MAX_VALUE : unit.type.buildRange)
                || tile.block() instanceof ConstructBlock || unit.team.core() == null) {
            unit.drawBuilding();
            return;
        }

        Draw.z(Layer.plans - 1f);
        plan.animScale = 1f;
        if (plan.breaking) {
            Drawf.selected(plan.x, plan.y, tile.block(), Pal.remove);
        } else {
            boolean valid = Build.validPlace(plan.block, unit.team, plan.x, plan.y, plan.rotation);
            var plans = unit.plans();
            plan.block.drawPlan(plan, plans, valid, 0.5f);
            Draw.reset();
            Draw.mixcol(Color.white, 0.24f + Mathf.absin(Time.globalTime, 6f, 0.28f));
            Draw.alpha(0.5f);
            plan.block.drawPlanConfigTop(plan, plans);
        }
        Draw.z(Layer.flyingUnit);
        if (unit.type.drawBuildBeam) {
            float focus = unit.type.buildBeamOffset + Mathf.absin(Time.time, 3f, 0.6f);
            unit.drawBuildingBeam(unit.x + Angles.trnsx(unit.rotation, focus),
                unit.y + Angles.trnsy(unit.rotation, focus));
        }
    }
}
