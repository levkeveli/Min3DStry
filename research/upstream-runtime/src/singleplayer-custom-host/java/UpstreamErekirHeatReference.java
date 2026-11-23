import arc.files.Fi;
import arc.graphics.Blending;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.Liquids;
import mindustry.world.blocks.heat.HeatConductor.HeatConductorBuild;
import mindustry.world.blocks.power.HeaterGenerator.HeaterGeneratorBuild;
import mindustry.world.blocks.production.HeatCrafter.HeatCrafterBuild;
import mindustry.world.blocks.sandbox.LiquidSource.LiquidSourceBuild;
import port.render.UpstreamFluidAtlas;

/** Runs the original Erekir production chain inside the port's offline tick loop. */
public final class UpstreamErekirHeatReference implements UpstreamTerrainDrawProbe.TerrainTarget {
    private static final int REACTOR_X = 271, Y = 103, CONDUCTOR_X = 275, CRUCIBLE_X = 278;
    private int conductorSprites, additiveSprites;

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected fixture, atlas, block colors and output save");
        var session = UpstreamSinglePlayerSession.create(new Fi("."), "en");
        try {
            if (!session.start(null, new Fi(args[0]), "save")) throw new AssertionError("Missing stock fixture");
            UpstreamTerrainDrawProbe.loadAtlas(new Fi(args[1]), UpstreamFluidAtlas::load);
            Vars.content.loadColors(new Fi(args[2]));
            new UpstreamErekirHeatReference().verify(session);
            session.save(new Fi(args[3]));
        } finally {
            session.shutdown();
        }
    }

    private void verify(UpstreamSinglePlayerSession session) {
        var reactor = (HeaterGeneratorBuild)Vars.world.build(REACTOR_X, Y);
        var conductor = (HeatConductorBuild)Vars.world.build(CONDUCTOR_X, Y);
        var crucible = (HeatCrafterBuild)Vars.world.build(CRUCIBLE_X, Y);
        var outlet = Vars.world.build(REACTOR_X, Y + 4);
        if (reactor.block != Blocks.neoplasiaReactor || conductor.block != Blocks.heatRedirector
                || crucible.block != Blocks.carbideCrucible || outlet.block != Blocks.reinforcedLiquidTank) {
            throw new AssertionError("Stock heat fixture changed");
        }

        var viewport = UpstreamTerrainDrawProbe.liveViewport(this);
        viewport.moveTo(269, 102);
        var layers = new UpstreamBuildingLayers();
        Runnable draw = () -> layers.render(viewport, reactor.team, UpstreamSinglePlayerApp::supportsBuildingDraw);
        viewport.draw(draw);
        int cold = conductorSprites;
        if (cold == 0) throw new AssertionError("Missing original cold redirector sprites");

        step(session, 520);
        var output = outlet.liquids.get(Liquids.neoplasm);
        var carbide = crucible.items.get(Items.carbide);
        if (Math.abs(reactor.heat - 60f) > 0.01f || Math.abs(conductor.heat - 36f) > 0.01f
                || Math.abs(crucible.heat - 36f) > 0.01f || output <= 0f || carbide <= 0) {
            throw new AssertionError("Stock chain did not produce heat, carbide and neoplasm: "
                + reactor.heat + ", " + conductor.heat + ", " + crucible.heat + ", " + carbide + ", " + output);
        }
        viewport.draw(draw);
        if (conductorSprites <= cold || additiveSprites == 0) {
            throw new AssertionError("Missing original heat input and output sprite passes");
        }
        System.out.println("UPSTREAM_EREKIR_HEAT reactor=60 redirector=36 crucible=36 carbide=" + carbide
            + " neoplasm=" + output + " additive=" + additiveSprites);

        conductor.rotation = 1;
        step(session, 4);
        if (crucible.heat != 0f || reactor.heat != 60f) {
            throw new AssertionError("Rotating the redirector did not isolate the crucible");
        }
        conductor.rotation = 0;
        step(session, 4);
        if (Math.abs(crucible.heat - 36f) > 0.01f) throw new AssertionError("Heat route did not recover");
        System.out.println("UPSTREAM_EREKIR_HEAT_ROTATION isolated=ok restored=ok");

        var water = (LiquidSourceBuild)Vars.world.build(REACTOR_X, Y - 3);
        float neoplasmBefore = reactor.liquids.get(Liquids.neoplasm) + outlet.liquids.get(Liquids.neoplasm);
        water.source = null;
        reactor.liquids.set(Liquids.water, 0f);
        step(session, 12);
        float neoplasmStarved = reactor.liquids.get(Liquids.neoplasm) + outlet.liquids.get(Liquids.neoplasm);
        if (reactor.productionEfficiency != 0f || reactor.heat >= 60f || neoplasmStarved > neoplasmBefore + 0.01f) {
            throw new AssertionError("The reactor kept producing without water");
        }
        water.source = Liquids.water;
        step(session, 32);
        if (reactor.heat < 59.99f || reactor.productionEfficiency < 0.99f
                || reactor.liquids.get(Liquids.neoplasm) + outlet.liquids.get(Liquids.neoplasm) <= neoplasmStarved) {
            throw new AssertionError("The reactor did not resume production after restoring water");
        }
        System.out.println("UPSTREAM_EREKIR_HEAT_WATER starved=ok recovered=ok");
    }

    private static void step(UpstreamSinglePlayerSession session, int ticks) {
        for (int i = 0; i < ticks; i++) session.stepSimulation(0f, 0f);
    }

    @Override
    public void begin(int left, int bottom, int width, int height) {
        conductorSprites = additiveSprites = 0;
    }

    @Override
    public void draw(int page, int left, int top, int width, int height, float x, float y,
                     float originX, float originY, float drawWidth, float drawHeight,
                     float rotation, int color, int mixColor, Blending blending) {
        if (x + originX == CONDUCTOR_X * Vars.tilesize && y + originY == Y * Vars.tilesize) {
            conductorSprites++;
            if (blending == Blending.additive) additiveSprites++;
        }
    }

    @Override
    public void drawSolidQuad(float[] vertices, int offset, Blending blending) {}

    @Override
    public void end() {}
}
