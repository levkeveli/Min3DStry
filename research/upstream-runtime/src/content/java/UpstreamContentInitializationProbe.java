import arc.Core;
import arc.Settings;
import arc.graphics.Pixmap;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;

public final class UpstreamContentInitializationProbe {
    public static void main(String[] args) {
        Vars.headless = true;
        Core.settings = new Settings();
        Vars.state = new GameState();
        Vars.content = new ContentLoader();
        Vars.content.createBaseContent();
        Vars.content.init();
        checkPixels();
        UpstreamBuildingBaseline.writeFactories();
        System.out.println("UPSTREAM_INITIALIZED pixel-copy=ok factories=" + Vars.content.blocks().size);
    }

    private static void checkPixels() {
        Pixmap original = new Pixmap(3, 2);
        if (!original.pixels.isDirect() || original.get(1, 1) != 0) {
            throw new IllegalStateException("Unexpected initial pixel storage");
        }
        original.set(1, 1, 0x3478a9ef);
        Pixmap copy = original.copy();
        if (copy.width != 3 || copy.height != 2 || copy.get(1, 1) != 0x3478a9ef) {
            throw new IllegalStateException("Pixel copy changed the dimensions or RGBA bytes");
        }
        original.set(1, 1, 0);
        if (copy.get(1, 1) != 0x3478a9ef) {
            throw new IllegalStateException("Copied pixel storage is shared");
        }
        copy.dispose();
        original.dispose();
    }
}
