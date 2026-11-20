import arc.files.Fi;
import java.io.IOException;
import mindustry.Vars;
import mindustry.io.SaveIO;
import mindustry.mod.Mods;

public final class UpstreamWorldRoundTripProbe {
    public static void main(String[] args) throws IOException {
        if (args.length != 0 && args.length != 3) {
            throw new IllegalArgumentException("Expected paths to Glacier, the v13 save, and a new output file");
        }
        String[] paths = args.length == 3 ? args : new String[] {
            "/3ds/min3dstry/glacier.msav",
            "/3ds/min3dstry/glacier-v13.msav",
            "/3ds/min3dstry/glacier-roundtrip.msav"
        };
        var output = new Fi(paths[2]);
        var app = UpstreamWorldLoadProbe.load(paths);
        output.parent().mkdirs();
        if (!output.file().createNewFile()) {
            throw new IllegalArgumentException("Round trip output already exists: " + output);
        }
        Vars.mods = new Mods();
        SaveIO.write(output);
        UpstreamWorldLoadProbe.loadCurrentSave(app, output);
        System.out.println("UPSTREAM_SAVE_ROUNDTRIP version=13 marker=ok");
    }
}
