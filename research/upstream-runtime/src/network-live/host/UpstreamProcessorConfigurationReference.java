import arc.files.Fi;
import java.nio.file.Files;
import java.util.Random;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.SectorPresets;
import mindustry.gen.Player;
import mindustry.logic.LAssembler;
import mindustry.logic.LCategory;
import mindustry.logic.LStatements.JumpStatement;
import mindustry.world.blocks.logic.CanvasBlock.CanvasBuild;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

/** Runs inside the unmodified v160.8 Java server. */
public final class UpstreamProcessorConfigurationReference {
    public static String run() throws Exception {
        Vars.logic.reset();
        Vars.world.loadSector(SectorPresets.groundZero.sector);
        var team = Vars.state.rules.defaultTeam;
        var processorTile = Vars.world.tile(129, 60);
        var canvasTile = Vars.world.tile(131, 60);
        processorTile.setBlock(Blocks.microProcessor, team);
        canvasTile.setBlock(Blocks.canvas, team);
        var processor = (LogicBuild)processorTile.build;
        var canvas = (CanvasBuild)canvasTile.build;

        processor.configured(null, canvas.pos());
        if (processor.links.size != 1 || processor.links.first().x != canvas.tileX()) {
            throw new AssertionError("Original processor did not link the Canvas");
        }

        String program = "# keep original text and links\ngetlink canvas 0\nop add color 2 3\nwrite color canvas 0\nend\n";
        byte[] config = UpstreamProcessorConfiguration.encode(processor, program);
        if (config == null) throw new AssertionError("A legal stock program was rejected");
        processor.configured(null, config);
        if (!processor.code.equals(program) || processor.links.size != 1 || processor.executor.links[0] != canvas
                || processor.links.first().x != canvas.tileX() || processor.links.first().y != canvas.tileY()) {
            throw new AssertionError("Editing code lost its original source or relative building link");
        }
        processor.executor.team = team;
        for (int tick = 0; tick < 8; tick++) processor.executor.runOnce();
        if (canvas.getPixel(0) != 5) throw new AssertionError("Edited stock program did not draw to Canvas");

        String tooLarge = " ".repeat(UpstreamProcessorConfiguration.MAX_SOURCE_BYTES + 1);
        if (UpstreamProcessorConfiguration.encode(processor, tooLarge) != null) {
            throw new AssertionError("The raw program size limit was ignored");
        }
        var random = new Random(160_008);
        var noisy = new StringBuilder(30_000);
        for (int i = 0; i < 30_000; i++) noisy.append((char)('!' + random.nextInt(94)));
        if (UpstreamProcessorConfiguration.encode(processor, noisy.toString()) != null) {
            throw new AssertionError("The compressed program size limit was ignored");
        }
        if (UpstreamProcessorConfiguration.encode(processor, "") == null || !processor.code.equals(program)) {
            throw new AssertionError("Validation changed the running program");
        }
        checkInstructionEditing();
        checkProcessorPalette(processor);
        return "UPSTREAM_PROCESSOR_EDIT_REFERENCE source=exact links=preserved canvas=5 limits=checked instructions=stock palette=ok";
    }

    private static void checkProcessorPalette(LogicBuild processor) throws Exception {
        var previousPlayer = Vars.player;
        var clipboard = Files.createTempFile("min3ds-processor-palette-", ".mlog");
        try {
            Vars.player = Player.create();
            Vars.player.team(processor.team);
            var config = new UpstreamBuildingConfig();
            config.editProcessorsWith(original -> original, new Fi(clipboard.toFile()));

            String source = "getlink canvas 0\nwrite 8 canvas 0\nend\n";
            Files.writeString(clipboard, source);
            config.open(processor);
            if (!config.isOpen()) throw new AssertionError("The stock processor did not open its palette");
            config.tap(160, 81);
            if (!processor.code.equals(source) || config.isOpen()) {
                throw new AssertionError("The import action did not submit the original mlog");
            }

            config.open(processor);
            config.tap(160, 156);
            config.tap(160, 156);
            config.tap(160, 81);
            config.tap(160, 56);
            var expected = LAssembler.read(source, false);
            expected.add(expected.peek().copy());
            if (!processor.code.equals(LAssembler.write(expected)) || processor.links.size != 1 || config.isOpen()) {
                throw new AssertionError("The instruction palette did not apply the edit or preserve its link");
            }
        } finally {
            Vars.player = previousPlayer;
            Files.deleteIfExists(clipboard);
        }
    }

    private static void checkInstructionEditing() {
        var model = new UpstreamLogicStatements("jump 2 always x false\nset color 5\nend\n", false, false);
        int end = -1;
        for (int i = 0; i < model.paletteSize(); i++) {
            var row = model.paletteEntry(i);
            if (row.privileged() || row.hidden() || row.category() == LCategory.unit) {
                throw new AssertionError("Stock permission filter leaked a restricted instruction");
            }
            if (row.statementKey().equals("end")) end = i;
        }
        if (end < 0 || !model.insert(0, end) || ((JumpStatement)model.get(1)).destIndex != 3) {
            throw new AssertionError("Inserting before a jump changed its target");
        }
        if (!model.duplicate(1) || ((JumpStatement)model.get(1)).destIndex != 4
            || ((JumpStatement)model.get(2)).destIndex != 4) {
            throw new AssertionError("The copied jump did not retain the original target");
        }
        model.move(4, 0);
        if (((JumpStatement)model.get(2)).destIndex != 0 || ((JumpStatement)model.get(3)).destIndex != 0) {
            throw new AssertionError("Moving a target left a stale jump address");
        }
        model.remove(0);
        if (((JumpStatement)model.get(1)).destIndex != -1 || ((JumpStatement)model.get(2)).destIndex != -1) {
            throw new AssertionError("Removing a target left an active jump");
        }
        if (!model.replace(1, "set color 7") || !model.source().contains("set color 7\n")) {
            throw new AssertionError("The stock assembler did not serialize the edited statement");
        }
    }
}
