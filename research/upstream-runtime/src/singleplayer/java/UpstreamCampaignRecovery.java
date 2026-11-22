import mindustry.Vars;
import mindustry.game.Team;
import mindustry.game.Teams.BlockPlan;
import mindustry.gen.Building;
import mindustry.maps.SectorDamage;
import mindustry.type.Sector;
import mindustry.world.Build;
import mindustry.world.WorldParams;
import mindustry.world.blocks.power.PowerGraph;

/** Offline part of v160.8 Control.playSector's recovery after a sector loss. */
final class UpstreamCampaignRecovery implements Runnable {
    private final BlockPlan[] previousPlans;
    private final Building[] previousBuildings, previousDerelicts;

    private UpstreamCampaignRecovery(BlockPlan[] plans, Building[] buildings, Building[] derelicts) {
        previousPlans = plans;
        previousBuildings = buildings;
        previousDerelicts = derelicts;
    }

    static UpstreamCampaignRecovery prepare(Sector sector, WorldParams params) {
        int spawnPos = sector.info.spawnPosition;
        if (spawnPos == 0 || !sector.info.sectorDataMatches(sector)) return null;

        var spawn = Vars.world.tile(spawnPos);
        if (spawn == null) return null;

        spawn.setBlock(sector.planet.defaultCore, Vars.state.rules.defaultTeam);
        SectorDamage.apply(1f);
        var team = Vars.state.rules.defaultTeam.data();
        params.corePositionOverride = spawnPos;
        return new UpstreamCampaignRecovery(team.plans.toArray(BlockPlan.class),
            team.buildings.<Building>toArray(Building.class),
            Team.derelict.data().buildings.<Building>toArray(Building.class));
    }

    @Override
    public void run() {
        var team = Vars.state.rules.defaultTeam;
        for (var generated : Team.derelict.data().buildings.<Building>toArray(Building.class)) {
            generated.tile.remove();
        }

        for (var build : previousDerelicts) {
            var tile = Vars.world.tile(build.tileX(), build.tileY());
            if (tile != null && tile.build == null
                    && Build.validPlace(build.block, Team.derelict, build.tileX(), build.tileY(), build.rotation, false, false)) {
                tile.setBlock(build.block, Team.derelict, build.rotation, () -> build);
            }
        }

        for (var build : previousBuildings) {
            if (build.power != null) {
                build.power.graph = new PowerGraph();
                build.power.links.clear();
            }
        }

        for (var build : previousBuildings) {
            var tile = Vars.world.tile(build.tileX(), build.tileY());
            if (tile != null && tile.build == null
                    && Build.validPlace(build.block, team, build.tileX(), build.tileY(), build.rotation, false, false)) {
                build.addPlan(false, true);
                tile.setBlock(build.block, team, build.rotation, () -> build);
                build.changeTeam(Team.derelict);
                build.dropped();
            }
        }

        for (var build : previousBuildings) {
            if (build.isValid()) build.updateProximity();
        }

        for (var plan : previousPlans) {
            var build = Vars.world.build(plan.x, plan.y);
            if (!(build != null && build.block == plan.block && build.tileX() == plan.x
                    && build.tileY() == plan.y && build.team != Vars.state.rules.waveTeam)) {
                team.data().plans.add(plan);
            }
        }
    }
}
