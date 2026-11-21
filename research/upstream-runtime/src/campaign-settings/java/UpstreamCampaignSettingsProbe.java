import arc.Settings;
import arc.files.Fi;

/** Real Arc Settings settings.bin round-trip on the target filesystem. */
public final class UpstreamCampaignSettingsProbe {
    public static void main(String[] args) {
        String temporary = System.getProperty("java.io.tmpdir");
        boolean live = temporary != null && temporary.startsWith("/3ds/min3dstry");
        String base = args.length > 0
            ? args[0]
            : (live
                ? "/3ds/min3dstry"
                : temporary + "/m3ds-campaign-settings");
        Fi dir = new Fi(base);
        dir.mkdirs();
        Fi settingsFile = dir.child("settings.bin");
        Fi backupFile = dir.child("settings_backup.bin");
        Fi backupsDir = dir.child("settings_backups");
        byte[] oldSettings = settingsFile.exists() ? settingsFile.readBytes() : null;
        byte[] oldBackup = backupFile.exists() ? backupFile.readBytes() : null;
        boolean backupsExisted = backupsDir.exists();
        long bytes;
        try {
            settingsFile.delete();
            backupFile.delete();

            Settings settings = new Settings();
            settings.setDataDirectory(dir);
            settings.setAutosave(false);
            settings.load();
            settings.put("m3ds-probe-int", 12345);
            settings.put("m3ds-probe-string", "settings-roundtrip");
            settings.put("conveyor-unlocked", true);
            settings.put("req-conveyor-copper", 50);
            settings.forceSave();

            if (!settingsFile.exists() || settingsFile.length() <= 0) {
                throw new IllegalStateException("settings.bin was not written");
            }
            bytes = settingsFile.length();

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
            Object unlocked = reloaded.get("conveyor-unlocked", null);
            Object research = reloaded.get("req-conveyor-copper", null);
            if (!Boolean.TRUE.equals(unlocked)) {
                throw new IllegalStateException("campaign unlock did not reload");
            }
            if (!(research instanceof Integer) || ((Integer)research) != 50) {
                throw new IllegalStateException("campaign research did not reload");
            }
        } finally {
            if (oldSettings == null) {
                settingsFile.delete();
            } else {
                settingsFile.writeBytes(oldSettings);
            }
            if (oldBackup == null) {
                backupFile.delete();
            } else {
                backupFile.writeBytes(oldBackup);
            }
            if (!backupsExisted && backupsDir.exists()) {
                backupsDir.deleteDirectory();
            }
        }

        System.out.println(
            "UPSTREAM_CAMPAIGN_SETTINGS path=" + (live ? "live" : "tmp")
            + " bytes=" + bytes
            + " int=12345 string=ok campaign=unlocked:conveyor,research:50 reload=ok");
    }
}
