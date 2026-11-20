import arc.files.Fi;
import arc.struct.IntSet;
import java.io.IOException;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.mod.Mods;
import mindustry.net.NetworkIO;

public final class UpstreamWorldStreamProbe {
    public static void main(String[] args) throws IOException {
        if (args.length > 1) throw new IllegalArgumentException("Expected a captured v160.8 WorldStream path");
        var fixture = new Fi(args.length == 0 ? "/3ds/min3dstry/glacier-world-stream.bin" : args[0]);
        var app = UpstreamWorldLoadProbe.initialize(fixture.parent());
        Vars.mods = new Mods();
        Vars.player = Player.create();
        var receivedEntities = new IntSet();
        try (var stream = new InflaterInputStream(fixture.read())) {
            NetworkIO.loadWorld(stream, receivedEntities::add);
        }
        app.runPosted();
        if (Vars.world.width() != 150 || Vars.world.height() != 250
                || Groups.player.size() != 1 || !Vars.player.name.equals("Min3DStry World Fixture")
                || Vars.player.id <= 0) {
            throw new IllegalStateException("The server world or assigned player did not load");
        }
        String fingerprint = UpstreamWorldLoadProbe.reportTerrain(false);
        System.out.println("UPSTREAM_WORLD_STREAM map=" + Vars.state.map.name() + " wave=" + Vars.state.wave
            + " team=" + Vars.player.team().id + " playerId=" + Vars.player.id
            + " defaultTeam=" + Vars.state.rules.defaultTeam.id + " waveTeam=" + Vars.state.rules.waveTeam.id
            + " waveSpacing=" + (int)Vars.state.rules.waveSpacing + " records=" + receivedEntities.size
            + " fingerprint=" + fingerprint);
    }
}
