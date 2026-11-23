import arc.Core;
import arc.Settings;
import arc.files.Fi;
import arc.util.serialization.Base64Coder;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import mindustry.Vars;
import mindustry.core.NetClient;
import mindustry.core.Platform;

/** Stock v160.8 settings and identities used by the native ConnectPacket encoder. */
record UpstreamPlayerIdentity(byte[] name, byte[] locale, byte[] usid, byte[] uuid, int color) {
    private static final String DEFAULT_NAME = "Min3DStry ARM11";

    static UpstreamPlayerIdentity load(Fi directory, String host) throws IOException {
        String graphicalLocale = Core.settings.getString("locale", "default");
        var settings = new Settings();
        settings.setDataDirectory(directory);
        settings.load();
        Core.settings = settings;
        var nameFile = directory.child("player-name.txt");
        String name = settings.getString("name", DEFAULT_NAME);
        if (nameFile.exists() && nameFile.length() <= 256) name = nameFile.readString("UTF-8").trim();
        name = usableName(name);
        var language = Core.settings.getString("locale", graphicalLocale);
        if (language.equals("default")) language = Locale.getDefault().toString();
        String usid = NetClient.forSnapshotDispatch().getUsid(host);
        byte[] uuid;
        try {
            uuid = Base64Coder.decode(new Platform() {}.getUUID());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid stock UUID in settings", invalid);
        }
        if (uuid.length != 8) throw new IOException("Expected an eight-byte UUID in stock settings");
        settings.forceSave();
        return new UpstreamPlayerIdentity(wireString(name), wireString(language), wireString(usid),
            uuid, settings.getInt("color-0", 0xffffffff));
    }

    private static String usableName(String name) {
        var cleaned = name.trim();
        if (cleaned.isEmpty() || cleaned.equals("[") || cleaned.equals("]") || cleaned.indexOf('\0') >= 0) {
            return DEFAULT_NAME;
        }
        while (cleaned.getBytes(StandardCharsets.UTF_8).length > Vars.maxNameLength) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(cleaned.length(), -1));
        }
        return cleaned;
    }

    // TypeIO uses DataOutput.writeUTF. Feed the existing C serializer its
    // modified UTF-8 payload and a terminator, excluding the Java length prefix.
    private static byte[] wireString(String value) throws IOException {
        var buffer = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(buffer)) {
            out.writeUTF(value);
            out.writeByte(0);
        }
        return Arrays.copyOfRange(buffer.toByteArray(), 2, buffer.size());
    }
}
