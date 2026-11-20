import arc.files.Fi;
import java.io.IOException;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.core.NetClient;
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
        Vars.netClient = new NetClient();
        try (var stream = new InflaterInputStream(fixture.read())) {
            NetworkIO.loadWorld(stream);
        }
        app.runPosted();
        if (Vars.world.width() != 150 || Vars.world.height() != 250
                || Groups.player.size() != 1 || !Vars.player.name.equals("Min3DStry World Fixture")) {
            throw new IllegalStateException("The server world or assigned player did not load");
        }
        String fingerprint = UpstreamWorldLoadProbe.reportTerrain(false);
        System.out.println("UPSTREAM_WORLD_STREAM map=" + Vars.state.map.name() + " wave=" + Vars.state.wave
            + " team=" + Vars.player.team().id + " playerId=" + Vars.player.id
            + " defaultTeam=" + Vars.state.rules.defaultTeam.id + " waveTeam=" + Vars.state.rules.waveTeam.id
            + " waveSpacing=" + (int)Vars.state.rules.waveSpacing + " fingerprint=" + fingerprint);
    }
}
