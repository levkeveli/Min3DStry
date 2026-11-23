import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.game.Gamemode;
import mindustry.game.Team;
import mindustry.io.MapIO;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.units.Reconstructor;
import mindustry.world.blocks.units.Reconstructor.ReconstructorBuild;
import mindustry.world.consumers.ConsumeItems;

/** Run an original Additive Reconstructor through the normal offline tick loop. */
public final class UpstreamStockReconstructorReference {
    private static final int X = 31, Y = 35;

    public static void main(String[] args) throws Exception {
        var halfway = new Fi(args[1]);
        var complete = new Fi(args[2]);
        Core.bundle = I18NBundle.createBundle(new Fi(args[3]), Locale.ENGLISH);
        var session = UpstreamSinglePlayerSession.create(halfway.parent(), "en");
        try {
            var map = MapIO.createMap(new Fi(args[0]), false);
            session.startCustomGame(map, Gamemode.sandbox);
            Vars.state.rules.infiniteResources = false;
            var tile = Vars.world.tile(X, Y);
            tile.setBlock(Blocks.additiveReconstructor, Team.sharded, 0);
            var build = (ReconstructorBuild)tile.build;
            var stock = (Reconstructor)build.block;
            Vars.world.tile(X - 2, Y).setBlock(Blocks.powerSource, Team.sharded, 0);
            var consumption = (ConsumeItems)stock.findConsumer(c -> c instanceof ConsumeItems);
            for (var requirement : consumption.items) build.items.set(requirement.item, requirement.amount);
            var dagger = UnitTypes.dagger.create(Team.sharded);
            dagger.set(build.x, build.y);
            var payload = new UnitPayload(dagger);
            if (!build.acceptPayload(build, payload)) throw new AssertionError("Stock unit payload acceptance");
            build.handlePayload(build, payload);

            for (int i = 0; i < 180 && build.progress < 60f; i++) session.stepSimulation(0f, 0f);
            float progress = build.progress;
            if (progress < 60f || progress >= stock.constructTime || build.power.status <= 0
                    || build.payload.unit.type != UnitTypes.dagger) throw new AssertionError("Powered stock production");
            session.save(halfway);

            if (!session.start(null, halfway, "reconstructor-save")) throw new AssertionError("Reopen stock save");
            build = (ReconstructorBuild)Vars.world.tile(X, Y).build;
            if (build.progress != progress || build.payload.unit.type != UnitTypes.dagger) {
                throw new AssertionError("Stock progress and input unit after reload");
            }
            for (int i = 0; i < stock.constructTime + 60 && build.payload != null
                    && build.payload.unit.type == UnitTypes.dagger; i++) session.stepSimulation(0f, 0f);
            if (build.payload == null || build.payload.unit.type != UnitTypes.mace) {
                throw new AssertionError("Stock Dagger to Mace upgrade after reload");
            }
            for (var requirement : consumption.items) {
                if (build.items.get(requirement.item) != 0) throw new AssertionError("Original upgrade cost");
            }
            session.save(complete);
            System.out.println("UPSTREAM_RECONSTRUCTOR_REFERENCE stock=additive-reconstructor input=dagger "
                + "progress=" + progress + " output=mace stockCost=ok");
        } finally {
            session.shutdown();
        }
    }
}
