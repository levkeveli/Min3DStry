import arc.ApplicationListener;
import arc.Core;
import arc.backend.headless.HeadlessApplication;
import arc.files.Fi;
import mindustry.Vars;

public final class UpstreamBuildingBaseline {
    public static void main(String[] args) {
        new HeadlessApplication(new ApplicationListener() {
            @Override
            public void init() {
                Core.settings.setDataDirectory(new Fi("building-baseline-config"));
                Vars.loadLocales = false;
                Vars.headless = true;
                Vars.loadSettings();
                Vars.init();
                Vars.content.createBaseContent();
                Vars.content.init();

                writeFactories();
                Core.app.exit();
            }
        }, error -> {
            error.printStackTrace();
            System.exit(1);
        });
    }

    static void writeFactories() {
        for (var block : Vars.content.blocks()) {
            var building = block.buildType.get();
            System.out.print("UPSTREAM_BUILDING\t" + block.id + "\t" + block.name + "\t"
                + building.getClass().getName() + "\n");
        }
    }
}
