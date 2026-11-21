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
import mindustry.net.Net;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.zip.DeflaterOutputStream;

/**
 * Host-only resilience checks for the pinned upstream .msav writer (#42).
 *
 * <p>Uses the unmodified v160.8 server JAR and the original Glacier map. The
 * probe verifies that malformed and unsupported saves are rejected, that the
 * upstream backup path recovers a corrupt slot, and that a failed write leaves
 * no new save behind. It does not define a Min3DStry save format.</p>
 */
public final class UpstreamSaveResilienceProbe {
    private static final byte[] HEADER = {'M', 'S', 'A', 'V'};

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one path to the original Glacier map");
        }
        new HeadlessApplication(new ApplicationCore() {
            @Override public void setup() {
                Core.settings.setDataDirectory(new Fi("save-resilience-config"));
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
                    run(new Fi(args[0]));
                    Core.app.exit();
                } catch (Throwable error) {
                    error.printStackTrace();
                    System.exit(1);
                }
            }
        }, error -> {
            error.printStackTrace();
            System.exit(1);
        });
    }

    private static void run(Fi mapFile) throws IOException {
        Fi root = new Fi(Files.createTempDirectory("min3dstry-save-resilience").toFile());
        try {
            var map = MapIO.createMap(mapFile, false);
            Vars.world.loadMap(map);

            Fi valid = root.child("valid.msav");
            SaveIO.write(valid);
            require(SaveIO.isSaveValid(valid), "valid save rejected");
            SaveIO.load(valid, Vars.world.context);
            System.out.println("UPSTREAM_SAVE_RESILIENCE valid=ok");

            Fi truncated = root.child("truncated.msav");
            byte[] validBytes = valid.readBytes();
            writeBytes(truncated, Arrays.copyOf(validBytes, Math.max(16, validBytes.length / 2)));
            // isSaveValid() only reads metadata, so a half-written save can still
            // have a valid header. Full loading is the authoritative check here.
            requireLoadRejected(truncated, "truncated save");
            System.out.println("UPSTREAM_SAVE_RESILIENCE truncated=rejected");

            Fi badHeader = root.child("bad-header.msav");
            writeDeflated(badHeader, "NOPE".getBytes(StandardCharsets.US_ASCII), 13);
            require(!SaveIO.isSaveValid(badHeader), "bad-header save accepted");
            requireLoadRejected(badHeader, "bad-header save");
            System.out.println("UPSTREAM_SAVE_RESILIENCE bad_header=rejected");

            Fi unknownVersion = root.child("unknown-version.msav");
            writeDeflated(unknownVersion, HEADER, 9999);
            require(!SaveIO.isSaveValid(unknownVersion), "unknown-version save accepted");
            requireLoadRejected(unknownVersion, "unknown-version save");
            System.out.println("UPSTREAM_SAVE_RESILIENCE unknown_version=rejected");

            // SaveIO.save(Fi) moves an existing slot to backupFileFor(file)
            // before writing, and SaveIO.isSaveValid(Fi)/load(Fi) check that
            // backup sibling. Exercise that upstream path directly.
            Fi backupSlot = root.child("backup-slot.msav");
            SaveIO.write(backupSlot);
            SaveIO.save(backupSlot);
            Fi backup = SaveIO.backupFileFor(backupSlot);
            require(backup.exists(), "upstream SaveIO.save did not create a backup");
            writeBytes(backupSlot, "corrupt".getBytes(StandardCharsets.US_ASCII));
            require(SaveIO.isSaveValid(backupSlot), "upstream backup was not recoverable");
            SaveIO.load(backupSlot, Vars.world.context);
            System.out.println("UPSTREAM_SAVE_RESILIENCE backup=recovered");

            // The following two checks exercise the port-side atomic-write
            // policy used for new output files. They are deliberately not
            // claimed as upstream SaveIO behavior.
            Fi existing = root.child("existing.msav");
            SaveIO.write(existing);
            requireThrows(() -> writeAtomically(existing, SaveIO::write),
                "existing save was overwritten");
            require(SaveIO.isSaveValid(existing), "existing save was damaged");
            System.out.println("MIN3DSTRY_SAVE_POLICY existing=protected");

            Fi interrupted = root.child("interrupted.msav");
            requireThrows(() -> writeAtomically(interrupted, tmp -> {
                SaveIO.write(tmp);
                throw new IOException("injected write failure");
            }), "interrupted write was reported as successful");
            require(!interrupted.exists(), "interrupted write left a final save");
            require(!interrupted.sibling("interrupted.msav.tmp").exists(),
                "interrupted write left a temporary save");
            System.out.println("MIN3DSTRY_SAVE_POLICY interrupted=rejected");

            System.out.println("UPSTREAM_SAVE_RESILIENCE_PASS");
        } finally {
            root.deleteDirectory();
        }
    }

    private static void requireLoadRejected(Fi file, String label) {
        try {
            SaveIO.load(file, Vars.world.context);
        } catch (SaveIO.SaveException expected) {
            return;
        }
        throw new AssertionError(label + " loaded successfully");
    }

    private static void writeDeflated(Fi file, byte[] header, int version) throws IOException {
        // SaveIO.getStream uses InflaterInputStream and readHeader/readInt use
        // DataInput, so this is the same zlib body plus big-endian version frame.
        try (OutputStream out = new DeflaterOutputStream(file.write(false))) {
            out.write(header);
            new DataOutputStream(out).writeInt(version);
        }
    }

    private static void writeBytes(Fi file, byte[] bytes) throws IOException {
        try (OutputStream out = file.write(false)) {
            out.write(bytes);
        }
    }

    private static void writeAtomically(Fi output, SaveAction action) throws IOException {
        Fi temporary = output.sibling(output.name() + ".tmp");
        if (temporary.exists()) {
            temporary.delete();
        }
        if (output.exists()) {
            throw new IOException("Refusing to overwrite an existing save: " + output);
        }
        try {
            action.write(temporary);
            if (!SaveIO.isSaveValid(temporary)) {
                throw new IOException("Temporary save is not valid: " + temporary);
            }
            temporary.moveTo(output);
        } catch (Throwable error) {
            temporary.delete();
            throw error;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void requireThrows(ThrowingRunnable action, String message) {
        try {
            action.run();
        } catch (Throwable expected) {
            return;
        }
        throw new AssertionError(message);
    }

    private interface SaveAction {
        void write(Fi file) throws IOException;
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
