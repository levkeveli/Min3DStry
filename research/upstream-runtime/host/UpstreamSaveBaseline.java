import arc.ApplicationCore;
import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.core.Logic;
import mindustry.core.NetServer;
import mindustry.core.World;
import mindustry.io.MapIO;
import mindustry.io.SaveIO;
import mindustry.io.SaveOptions;
import mindustry.net.Net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Host-only reference for the pinned upstream save writer. Loads the original
 * Glacier map with World.loadMap and writes it through SaveIO. The output is
 * the current MSAV stream (deflated), not a Min3DStry format.
 */
class UpstreamSaveBaseline {
    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one path to a Mindustry map");
        }
        new HeadlessApplication(new ApplicationCore() {
            @Override public void setup() {
                Core.settings.setDataDirectory(new Fi("save-baseline-config"));
                Vars.headless = true;
                Vars.net = new Net(null);
                Vars.init();
                Vars.world = new World();
                Vars.content.createBaseContent();
                add(Vars.logic = new Logic());
                add(Vars.netServer = new NetServer());
                Vars.content.init();
            }

            @Override public void init() {
                super.init();
                try {
                    var map = MapIO.createMap(new Fi(args[0]), false);
                    Vars.world.loadMap(map);

                    ByteArrayOutputStream raw = new ByteArrayOutputStream();
                    SaveIO.write(new DeflaterOutputStream(raw), new SaveOptions());
                    byte[] bytes = raw.toByteArray();

                    try (DataInputStream in = new DataInputStream(
                            new InflaterInputStream(new ByteArrayInputStream(bytes)))) {
                        byte[] header = new byte[4];
                        in.readFully(header);
                        int version = in.readInt();
                        System.out.printf(
                            "UPSTREAM_SAVE header=%s version=%d bytes=%d%n",
                            new String(header, StandardCharsets.US_ASCII), version, bytes.length);
                    }
                    Core.app.exit();
                } catch (Exception error) {
                    throw new RuntimeException(error);
                }
            }
        }, error -> {
            error.printStackTrace();
            System.exit(1);
        });
    }
}
