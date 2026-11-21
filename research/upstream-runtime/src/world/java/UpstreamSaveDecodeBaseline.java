import arc.files.Fi;
import mindustry.io.SaveIO;

/**
 * Decodes a real upstream .msav through the pinned v160.8 SaveIO and prints the
 * same building fields that the native Min3DStry reader hashes. The aggregate
 * hash is compared with SECTOR_PARITY on the 3DS build.
 */
public final class UpstreamSaveDecodeBaseline {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one path to a .msav save");
        }
        Fi save = new Fi(args[0]);
        var app = UpstreamWorldLoadProbe.initialize(save.parent());
        SaveIO.load(save);
        app.runPosted();
        UpstreamWorldLoadProbe.reportSaveState(false);
    }
}
