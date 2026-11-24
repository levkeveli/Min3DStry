import arc.files.Fi;
import arc.struct.IntSeq;
import arc.struct.StringMap;
import java.io.DataOutputStream;
import java.util.zip.DeflaterOutputStream;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.game.Gamemode;
import mindustry.game.Team;
import mindustry.io.JsonIO;
import mindustry.io.MapIO;
import mindustry.io.SaveIO;
import mindustry.io.SaveOptions;
import mindustry.type.UnitType;
import mindustry.world.blocks.ControlBlock;

public final class UpstreamStockSavedPlayerReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected the stock Glacier and an output directory");
        var map = new Fi(args[0]);
        var output = new Fi(args[1]);
        output.mkdirs();
        var session = UpstreamSinglePlayerSession.create(output, "en");
        try {
            session.startCustomGame(MapIO.createMap(map, false), Gamemode.sandbox);
            var near = UnitTypes.dagger.spawn(Team.crux, 488f, 480f);
            UnitTypes.dagger.spawn(Team.crux, 520f, 480f);
            UnitTypes.dagger.spawn(Team.sharded, 480f, 480f);
            var groups = new IntSeq[10];
            for (int i = 0; i < groups.length; i++) groups[i] = new IntSeq(true, 4);
            groups[0].add(near.id);
            String json = JsonIO.write(groups);
            output.child("groups.json").writeString(json, false, "UTF-8");

            var unitSave = output.child("unit.msav");
            writeTaggedSave(unitSave, UnitTypes.dagger, json, 480f, 480f);
            if (!session.start(null, unitSave, "saved-player-unit")
                    || Vars.player.team() != Team.crux || Vars.state.rules.defaultTeam != Team.sharded
                    || Vars.player.x != 480f || Vars.player.y != 480f) {
                throw new AssertionError("The original team or view position was lost before spawning");
            }
            var restoredUnit = session.ensurePlayerSpawn();
            if (restoredUnit == null || restoredUnit.type != UnitTypes.dagger
                    || restoredUnit.team != Team.crux || restoredUnit.x != 488f) {
                throw new AssertionError("The nearest original unit on the saved team was not restored");
            }
            session.save(output.child("unit-roundtrip.msav"));

            var tile = Vars.world.tile(65, 60);
            tile.setBlock(Blocks.duo, Team.crux);
            var blockSave = output.child("turret.msav");
            writeTaggedSave(blockSave, UnitTypes.block, json, tile.worldx(), tile.worldy());
            if (!session.start(null, blockSave, "saved-player-turret")) throw new AssertionError("Cannot open turret save");
            var restoredTurret = (ControlBlock) Vars.world.build(65, 60);
            if (session.ensurePlayerSpawn() != restoredTurret.unit() || Vars.player.team() != Team.crux) {
                throw new AssertionError("The original controlled turret did not resume");
            }
            session.save(output.child("turret-roundtrip.msav"));
            verifyBackupTags(session, output.child("recover.msav"), unitSave, blockSave);

            session.startCustomGame(MapIO.createMap(map, false), Gamemode.sandbox);
            var fresh = output.child("fresh.msav");
            session.save(fresh);
            if (Vars.player.team() != Vars.state.rules.defaultTeam || Vars.player.unit() == null
                    || !Vars.player.unit().spawnedByCore()
                    || !"null".equals(SaveIO.getMeta(fresh).tags.get("controlGroups"))) {
                throw new AssertionError("A fresh match inherited old player metadata");
            }
            System.out.println("UPSTREAM_SAVED_PLAYER team=crux position=restored unit=dagger turret=duo fresh=reset");
        } finally {
            session.shutdown();
        }
    }

    private static void verifyBackupTags(UpstreamSinglePlayerSession session, Fi primary, Fi unit, Fi turret)
            throws Exception {
        try (var source = SaveIO.getStream(unit);
                var target = new DeflaterOutputStream(primary.write(false))) {
            byte[] header = source.readNBytes(SaveIO.header.length + Integer.BYTES);
            int metaLength = source.readInt();
            target.write(header);
            var output = new DataOutputStream(target);
            output.writeInt(metaLength);
            output.write(source.readNBytes(metaLength));
        }
        if (!SaveIO.isSaveValid(primary) || !"dagger".equals(SaveIO.getMeta(primary).tags.get("controlledType"))) {
            throw new AssertionError("Truncated primary does not retain valid player metadata");
        }
        turret.copyTo(SaveIO.backupFileFor(primary));
        if (!session.start(null, primary, "saved-player-backup")) {
            throw new AssertionError("Stock backup did not load");
        }
        var tile = Vars.world.tile(65, 60);
        if (!(tile.build instanceof ControlBlock restored)
                || Vars.player.x != tile.worldx() || Vars.player.y != tile.worldy()
                || session.ensurePlayerSpawn() != restored.unit()) {
            throw new AssertionError("Recovered world did not use its backup player metadata");
        }
        System.out.println("UPSTREAM_SAVED_PLAYER_BACKUP primary=truncated tags=backup");
    }

    private static void writeTaggedSave(Fi file, UnitType type, String groups, float x, float y) {
        Vars.player.clearUnit();
        Vars.player.team(Team.crux);
        Vars.player.set(x, y);
        var options = new SaveOptions();
        options.extraTags = StringMap.of("controlledType", type.name, "controlGroups", groups);
        SaveIO.save(file, options);
    }
}
