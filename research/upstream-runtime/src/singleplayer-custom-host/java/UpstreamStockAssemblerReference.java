import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import mindustry.Vars;
import mindustry.ai.types.AssemblerAI;
import mindustry.content.Blocks;
import mindustry.content.Liquids;
import mindustry.content.UnitTypes;
import mindustry.game.Gamemode;
import mindustry.gen.Groups;
import mindustry.io.MapIO;
import mindustry.world.Tile;
import mindustry.world.blocks.payloads.BuildPayload;
import mindustry.world.blocks.payloads.Payload;
import mindustry.world.blocks.payloads.UnitPayload;
import mindustry.world.blocks.sandbox.LiquidSource.LiquidSourceBuild;
import mindustry.world.blocks.units.UnitAssembler;
import mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild;

public final class UpstreamStockAssemblerReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected a stock Erekir map, two save paths and bundle");
        var halfway = new Fi(args[1]);
        var completed = new Fi(args[2]);
        Core.bundle = I18NBundle.createBundle(new Fi(args[3]), Locale.ENGLISH);
        var session = UpstreamSinglePlayerSession.create(halfway.parent(), "en");
        try {
            session.startCustomGame(MapIO.createMap(new Fi(args[0]), false), Gamemode.sandbox);
            Vars.state.rules.infiniteResources = false;
            var team = Vars.state.rules.defaultTeam;
            var tile = emptyField();
            int x = tile.x, y = tile.y;
            var stock = (UnitAssembler) Blocks.tankAssembler;
            tile.setBlock(stock, team, 0);
            var assembler = (UnitAssemblerBuild) tile.build;
            Vars.world.tile(x - 3, y).setBlock(Blocks.powerSource, team, 0);
            var liquid = Vars.world.tile(x, y - 3);
            liquid.setBlock(Blocks.liquidSource, team, 0);
            var source = (LiquidSourceBuild) liquid.build;
            source.source = Liquids.cyanogen;

            for (int i = 0; i < 4; i++) {
                addPayload(session, assembler, new UnitPayload(UnitTypes.stell.create(team)));
            }
            for (int i = 0; i < 10; i++) {
                addPayload(session, assembler, new BuildPayload(Blocks.tungstenWallLarge, team));
            }
            if (assembler.plan().unit != UnitTypes.vanquish || assembler.blocks.get(UnitTypes.stell) != 4
                    || assembler.blocks.get(Blocks.tungstenWallLarge) != 10) {
                throw new AssertionError("Expected exact original Vanquish materials");
            }
            var position = assembler.getUnitSpawn().cpy().add(32f, 0f);
            assembler.onCommand(position);
            stepUntil(session, 2400, () -> assembler.units.size == stock.dronesCreated
                && assembler.units.count(unit -> ((AssemblerAI)unit.controller()).inPosition()) == stock.dronesCreated);
            if (assembler.units.size != stock.dronesCreated || assembler.power.status <= 0f
                    || assembler.liquids.get(Liquids.cyanogen) <= 0f
                    || assembler.units.contains(unit -> !((AssemblerAI)unit.controller()).inPosition())) {
                throw new AssertionError("Original drones, power and cyanogen did not become available");
            }

            source.source = null;
            assembler.liquids.set(Liquids.cyanogen, 0f);
            float starved = assembler.progress;
            stepUntil(session, 20, () -> false);
            if (assembler.progress != starved) throw new AssertionError("Production without cyanogen");
            source.source = Liquids.cyanogen;
            float targetProgress = Math.max(starved + 0.05f, 0.2f);
            stepUntil(session, 1600, () -> assembler.progress >= targetProgress);
            float savedProgress = assembler.progress;
            if (savedProgress < targetProgress || savedProgress >= 1f || !assembler.ready()) {
                throw new AssertionError("Powered assembly did not resume after restoring cyanogen");
            }
            var droneIds = assembler.units.map(unit -> unit.id);
            session.save(halfway);

            if (!session.start(null, halfway, "stock-assembler-save")) throw new AssertionError("Cannot reload stock save");
            var restored = (UnitAssemblerBuild) Vars.world.build(x, y);
            if (restored.progress != savedProgress || !position.equals(restored.commandPos)
                    || restored.blocks.get(UnitTypes.stell) != 4 || restored.blocks.get(Blocks.tungstenWallLarge) != 10) {
                throw new AssertionError("Stock progress, materials or rally position lost after reload");
            }
            session.stepSimulation(0, 0);
            if (restored.units.size != droneIds.size || restored.units.contains(unit -> !droneIds.contains(unit.id))) {
                throw new AssertionError("Stock drones lost after reload");
            }
            stepUntil(session, 4000, () -> Groups.unit.count(unit -> unit.team == team && unit.type == UnitTypes.vanquish) > 0);
            var vanquish = Groups.unit.find(unit -> unit.team == team && unit.type == UnitTypes.vanquish);
            if (vanquish == null || restored.progress != 0f || !restored.blocks.isEmpty()
                    || !position.equals(vanquish.command().targetPos)) {
                throw new AssertionError("Stock Vanquish, materials or assigned rally point missing");
            }
            session.save(completed);
            System.out.println("UPSTREAM_STOCK_ASSEMBLER x=" + x + " y=" + y + " saved=" + savedProgress
                + " drones=" + droneIds.size + " output=vanquish");
        } finally {
            session.shutdown();
        }
    }

    private static Tile emptyField() {
        for (int y = 12; y < Vars.world.height() - 12; y++) {
            for (int x = 6; x < Vars.world.width() - 20; x++) {
                // The stock 5x5 assembler spawns its output nine tiles east.
                if (clearSquare(x, y, 3) && clearSquare(x + 9, y, 4)) return Vars.world.tile(x, y);
            }
        }
        throw new AssertionError("No clear ground for a stock Tank Assembler and its output");
    }

    private static boolean clearSquare(int x, int y, int radius) {
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                var tile = Vars.world.tile(x + dx, y + dy);
                if (tile.block() != Blocks.air || !tile.floor().hasSurface()) return false;
            }
        }
        return true;
    }

    private static void addPayload(UpstreamSinglePlayerSession session, UnitAssemblerBuild assembler, Payload payload) {
        if (!assembler.acceptPayload(assembler, payload)) throw new AssertionError("Stock payload was refused");
        assembler.handlePayload(assembler, payload);
        stepUntil(session, 80, () -> assembler.payload == null);
        if (assembler.payload != null) throw new AssertionError("Stock payload stayed in the intake");
    }

    private static void stepUntil(UpstreamSinglePlayerSession session, int limit, BooleanSupplier done) {
        for (int i = 0; i < limit && !done.getAsBoolean(); i++) session.stepSimulation(0f, 0f);
    }
}
