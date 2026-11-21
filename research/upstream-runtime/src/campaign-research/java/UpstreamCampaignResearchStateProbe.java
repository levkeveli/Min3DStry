import arc.Core;
import arc.Settings;
import arc.files.Fi;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.content.TechTree.TechNode;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.type.Item;
import mindustry.type.ItemStack;

/** Reloads persisted upstream research requirements into the Serpulo tech tree. */
public final class UpstreamCampaignResearchStateProbe {
    /** Disables Arc's asynchronous settings backup, which has no lane on TeaVM C. */
    private static final class ProbeSettings extends Settings {
        void saveWithoutBackup(){
            executor.shutdownNow();
            try{
                saveValues();
            }catch(RuntimeException expected){
                // The file is written before Arc schedules its background backup copy.
            }
        }
    }

    public static void main(String[] args){
        String temporary = System.getProperty("java.io.tmpdir");
        boolean live = temporary != null && temporary.startsWith("/3ds/min3dstry");
        String base = args.length > 0
            ? args[0]
            : (live
                ? "/3ds/min3dstry"
                : temporary + "/m3ds-campaign-research");
        Fi dir = new Fi(base);
        dir.mkdirs();
        Fi settingsFile = dir.child("settings.bin");
        Fi backupFile = dir.child("settings_backup.bin");
        Fi backupsDir = dir.child("settings_backups");
        byte[] oldSettings = settingsFile.exists() ? settingsFile.readBytes() : null;
        byte[] oldBackup = backupFile.exists() ? backupFile.readBytes() : null;
        boolean backupsExisted = backupsDir.exists();
        int blocks = -1;
        try{
            settingsFile.delete();
            backupFile.delete();

            ProbeSettings blank = new ProbeSettings();
            blank.setDataDirectory(dir);
            blank.setAutosave(false);
            blank.load();
            Core.settings = blank;

            Vars.headless = true;
            Vars.state = new GameState();
            Vars.content = new ContentLoader();
            Vars.content.createBaseContent();
            Vars.content.init();
            blocks = Vars.content.blocks().size;

            TechNode before = Blocks.conveyor.techNode;
            if(before == null || before.rootNode == null){
                throw new IllegalStateException("Conveyor has no Serpulo tech node");
            }
            if(!"serpulo".equals(before.rootNode.name)){
                throw new IllegalStateException("Conveyor is not under the Serpulo tech tree");
            }
            if(requirement(before, Items.copper) != 0){
                throw new IllegalStateException("Research requirement was not empty before persistence");
            }

            blank.put("req-conveyor-copper", 50);
            blank.saveWithoutBackup();

            if(!settingsFile.exists() || settingsFile.length() <= 0){
                throw new IllegalStateException("settings.bin was not written");
            }

            // A fresh instance must recover research progress from settings.bin.
            ProbeSettings reloaded = new ProbeSettings();
            reloaded.setDataDirectory(dir);
            reloaded.setAutosave(false);
            reloaded.load();
            Core.settings = reloaded;

            Vars.content = new ContentLoader();
            Vars.content.createBaseContent();
            Vars.content.init();

            TechNode after = Blocks.conveyor.techNode;
            if(after == null || after.rootNode == null || !"serpulo".equals(after.rootNode.name)){
                throw new IllegalStateException("Conveyor tech node did not reload under Serpulo");
            }
            if(requirement(after, Items.copper) != 50){
                throw new IllegalStateException("Persisted research requirement did not reload");
            }
        }finally{
            if(oldSettings == null){
                settingsFile.delete();
            }else{
                settingsFile.writeBytes(oldSettings);
            }
            if(oldBackup == null){
                backupFile.delete();
            }else{
                backupFile.writeBytes(oldBackup);
            }
            if(!backupsExisted && backupsDir.exists()){
                backupsDir.deleteDirectory();
            }
        }

        System.out.println(
            "UPSTREAM_CAMPAIGN_RESEARCH path=" + (live ? "live" : "tmp")
            + " root=serpulo node=conveyor copper=50 lockedBefore=ok reload=ok blocks=" + blocks);
    }

    private static int requirement(TechNode node, Item item){
        for(ItemStack stack : node.finishedRequirements){
            if(stack.item == item) return stack.amount;
        }
        return -1;
    }
}
