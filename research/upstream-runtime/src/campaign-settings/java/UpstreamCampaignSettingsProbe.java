import arc.Settings;
import arc.files.Fi;

/** Real Arc Settings settings.bin round-trip on the target filesystem. */
public final class UpstreamCampaignSettingsProbe {
    public static void main(String[] args) {
        String base = args.length > 0
            ? args[0]
            : System.getProperty("java.io.tmpdir") + "/m3ds-campaign-settings";
        Fi dir = new Fi(base);
        dir.mkdirs();
        Fi settingsFile = dir.child("settings.bin");
        Fi backupFile = dir.child("settings_backup.bin");
        settingsFile.delete();
        backupFile.delete();

        Settings settings = new Settings();
        settings.setDataDirectory(dir);
        settings.setAutosave(false);
        settings.load();
        settings.put("m3ds-probe-int", 12345);
        settings.put("m3ds-probe-string", "settings-roundtrip");
        settings.forceSave();

        if (!settingsFile.exists() || settingsFile.length() <= 0) {
            throw new IllegalStateException("settings.bin was not written");
        }
        long bytes = settingsFile.length();

        Settings reloaded = new Settings();
        reloaded.setDataDirectory(dir);
        reloaded.setAutosave(false);
        reloaded.load();
        Object intValue = reloaded.get("m3ds-probe-int", null);
        Object stringValue = reloaded.get("m3ds-probe-string", null);
        if (!(intValue instanceof Integer) || ((Integer)intValue) != 12345) {
            throw new IllegalStateException("settings integer did not reload");
        }
        if (!"settings-roundtrip".equals(stringValue)) {
            throw new IllegalStateException("settings string did not reload");
        }

        System.out.println(
            "UPSTREAM_CAMPAIGN_SETTINGS bytes=" + bytes
            + " int=12345 string=ok reload=ok");
    }
}
