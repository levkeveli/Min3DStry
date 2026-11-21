import arc.files.Fi;
import java.io.IOException;
import mindustry.Vars;
import mindustry.io.SaveIO;
import mindustry.mod.Mods;

/** Writes and reloads the upstream sector slot name for ground zero. */
public final class UpstreamCampaignSectorProbe {
    public static void main(String[] args) throws IOException {
        if (args.length != 0 && args.length != 3) {
            throw new IllegalArgumentException("Expected paths to Glacier, the v13 save, and a new sector slot");
        }
        String[] paths = args.length == 3 ? args : new String[] {
            "/3ds/min3dstry/glacier.msav",
            "/3ds/min3dstry/glacier-v13.msav",
            "/3ds/min3dstry/sector-serpulo-170.msav"
        };
        var output = new Fi(paths[2]);
        var app = UpstreamWorldLoadProbe.load(paths);
        output.parent().mkdirs();
        if (output.exists()) {
            throw new IllegalArgumentException("Sector slot already exists: " + output);
        }
        Vars.mods = new Mods();
        SaveIO.write(output);
        if (!output.exists() || output.length() <= 0) {
            throw new IllegalStateException("Sector slot was not written");
        }
        UpstreamWorldLoadProbe.loadCurrentSave(app, output);
        long bytes = output.length();
        output.delete();
        System.out.println(
            "UPSTREAM_CAMPAIGN_SECTOR slot=sector-serpulo-170.msav bytes=" + bytes
            + " version=13 marker=ok");
    }
}
