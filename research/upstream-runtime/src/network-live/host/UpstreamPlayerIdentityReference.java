import arc.Core;
import arc.Settings;
import arc.files.Fi;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.util.Arrays;

/** Verify stock settings reuse, profile separation and the native UTF payload. */
public final class UpstreamPlayerIdentityReference {
    public static void main(String[] args) throws Exception {
        Fi root = new Fi(Files.createTempDirectory("m3ds-identity-").toFile());
        try {
            var a = root.child("profile-a");
            var b = root.child("profile-b");
            a.mkdirs();
            b.mkdirs();
            var settings = new Settings();
            settings.setDataDirectory(a);
            settings.load();
            settings.put("name", "Stock name");
            settings.put("locale", "de");
            settings.put("color-0", 0x123456ff);
            settings.forceSave();
            Core.settings = settings;
            var first = UpstreamPlayerIdentity.load(a, "127.0.0.1");
            if (!wire(first.name()).equals("Stock name") || !wire(first.locale()).equals("de")
                    || first.color() != 0x123456ff || first.uuid().length != 8) throw new AssertionError("Stock settings");
            a.child("player-name.txt").writeString("Étoile-🚀", false, "UTF-8");
            var reopened = UpstreamPlayerIdentity.load(a, "127.0.0.1");
            if (!wire(reopened.name()).equals("Étoile-🚀") || !Arrays.equals(first.uuid(), reopened.uuid())
                    || !Arrays.equals(first.usid(), reopened.usid())) throw new AssertionError("Reopened identity");
            var otherServer = UpstreamPlayerIdentity.load(a, "192.0.2.1");
            if (!Arrays.equals(first.uuid(), otherServer.uuid()) || Arrays.equals(first.usid(), otherServer.usid())) {
                throw new AssertionError("Per-server user ID");
            }
            var secondProfile = UpstreamPlayerIdentity.load(b, "127.0.0.1");
            if (!wire(secondProfile.name()).equals("Min3DStry ARM11") || Arrays.equals(first.uuid(), secondProfile.uuid())
                    || Arrays.equals(first.usid(), secondProfile.usid())) throw new AssertionError("Separate profiles");
            System.out.println("UPSTREAM_PLAYER_IDENTITY_REFERENCE persisted=ok servers=2 profiles=2 utf=ok");
        } finally {
            root.deleteDirectory();
        }
    }

    private static String wire(byte[] payload) throws Exception {
        var utf = new byte[payload.length + 1];
        int length = payload.length - 1;
        if (payload[length] != 0) throw new AssertionError("Native string terminator");
        utf[0] = (byte)(length >>> 8);
        utf[1] = (byte)length;
        System.arraycopy(payload, 0, utf, 2, length);
        try (var data = new DataInputStream(new ByteArrayInputStream(utf))) {
            return data.readUTF();
        }
    }
}
