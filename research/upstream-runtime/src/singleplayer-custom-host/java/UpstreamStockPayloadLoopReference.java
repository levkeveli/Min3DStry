import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.game.Gamemode;
import mindustry.game.Team;
import mindustry.io.MapIO;
import mindustry.world.Tile;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.payloads.Constructor.ConstructorBuild;
import mindustry.world.blocks.payloads.Payload;
import mindustry.world.blocks.payloads.PayloadConveyor.PayloadConveyorBuild;
import mindustry.world.blocks.payloads.PayloadDeconstructor.PayloadDeconstructorBuild;

public final class UpstreamStockPayloadLoopReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected a stock map, two save paths and bundle");
        var halfway = new Fi(args[1]);
        var completed = new Fi(args[2]);
        Core.bundle = I18NBundle.createBundle(new Fi(args[3]), Locale.ENGLISH);
        var session = UpstreamSinglePlayerSession.create(halfway.parent(), "en");
        try {
            session.startCustomGame(MapIO.createMap(new Fi(args[0]), false), Gamemode.sandbox);
            Vars.state.rules.infiniteResources = false;
            Vars.state.rules.buildCostMultiplier = 1.5f;
            Vars.state.rules.deconstructRefundMultiplier = 0.25f;
            var team = Vars.state.rules.defaultTeam;
            var field = emptyField();
            int x = field.x, y = field.y;
            field.setBlock(Blocks.constructor, team, 0);
            int beltX = x + 3, recyclerX = x + 6;
            Vars.world.tile(beltX, y).setBlock(Blocks.reinforcedPayloadConveyor, team, 0);
            Vars.world.tile(recyclerX, y).setBlock(Blocks.smallDeconstructor, team, 0);
            var constructor = (ConstructorBuild) field.build;
            var belt = (PayloadConveyorBuild) Vars.world.build(beltX, y);
            var recycler = (PayloadDeconstructorBuild) Vars.world.build(recyclerX, y);
            constructor.configure(Blocks.reinforcedContainer);
            constructor.items.add(Items.tungsten, 45);
            constructor.items.add(Items.graphite, 60);

            stepUntil(session, 60, () -> false);
            if (constructor.recipe != Blocks.reinforcedContainer || constructor.progress != 0f) {
                throw new AssertionError("The configured stock Constructor must wait for power");
            }
            addPower(x, y, team);
            stepUntil(session, 1600, () -> belt.getPayload() != null);
            requireContainer(belt.getPayload());
            if (!constructor.items.empty()) throw new AssertionError("Original Constructor did not consume both materials");

            stepUntil(session, 300, () -> recycler.deconstructing != null);
            requireContainer(recycler.deconstructing);
            stepUntil(session, 30, () -> false);
            if (recycler.progress != 0f || !recycler.items.empty()) {
                throw new AssertionError("The original Payload Deconstructor must wait for power");
            }
            addPower(recyclerX, y, team);
            stepUntil(session, 600, () -> recycler.progress >= 0.33f);
            float savedProgress = recycler.progress;
            if (savedProgress < 0.33f || savedProgress >= 1f || recycler.accum == null
                    || recycler.items.get(Items.tungsten) <= 0 || recycler.items.get(Items.graphite) <= 0) {
                throw new AssertionError("Stock deconstruction did not partially refund both materials");
            }
            float[] amounts = recycler.accum.clone();
            int tungsten = recycler.items.get(Items.tungsten);
            int graphite = recycler.items.get(Items.graphite);
            session.save(halfway);

            if (!session.start(null, halfway, "stock-payload-loop")) throw new AssertionError("Cannot reload stock save");
            var restored = (PayloadDeconstructorBuild) Vars.world.build(recyclerX, y);
            requireContainer(restored.deconstructing);
            if (restored.progress != savedProgress || !Arrays.equals(restored.accum, amounts)
                    || restored.items.get(Items.tungsten) != tungsten || restored.items.get(Items.graphite) != graphite) {
                throw new AssertionError("Stock save lost the payload, progress, or refunded materials");
            }
            stepUntil(session, 600, () -> restored.deconstructing == null);
            if (restored.deconstructing != null || restored.accum != null
                    || restored.items.get(Items.tungsten) != 45 || restored.items.get(Items.graphite) != 60) {
                throw new AssertionError("Stock Payload Deconstructor did not return the full build-cost-scaled requirements");
            }
            session.save(completed);
            System.out.println("UPSTREAM_STOCK_PAYLOAD_LOOP progress=" + savedProgress + " tungsten=45 graphite=60");
        } finally {
            session.shutdown();
        }
    }

    private static Tile emptyField() {
        for (int y = 4; y < Vars.world.height() - 4; y++) {
            for (int x = 3; x < Vars.world.width() - 9; x++) {
                boolean clear = true;
                for (int dy = -2; dy <= 2 && clear; dy++) {
                    for (int dx = -2; dx <= 7; dx++) {
                        var tile = Vars.world.tile(x + dx, y + dy);
                        if (tile.block() != Blocks.air || !tile.floor().hasSurface()) {
                            clear = false;
                            break;
                        }
                    }
                }
                if (clear) return Vars.world.tile(x, y);
            }
        }
        throw new AssertionError("No clear ground for the Erekir payload line");
    }

    private static void addPower(int x, int y, Team team) {
        Vars.world.tile(x, y - 2).setBlock(Blocks.powerSource, team, 0);
    }

    private static void requireContainer(Payload payload) {
        if (!(payload instanceof BuildPayload item) || item.build.block != Blocks.reinforcedContainer) {
            throw new AssertionError("The stock reinforced container did not reach the next building");
        }
    }

    private static void stepUntil(UpstreamSinglePlayerSession session, int limit, BooleanSupplier done) {
        for (int i = 0; i < limit && !done.getAsBoolean(); i++) session.stepSimulation(0f, 0f);
    }
}
