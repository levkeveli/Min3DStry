import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.SectorPresets;
import mindustry.type.Sector;

/** Open the pinned campaign maps through the original FileMapGenerator. */
public final class UpstreamCampaignLaunchReference {
    public static void main(String[] args) throws Exception {
        Fi assets = new Fi(args[0]), root = new Fi(args[1]);
        if (root.exists()) throw new IllegalArgumentException("Use a new scratch campaign directory");
        root.mkdirs();
        Core.bundle = I18NBundle.createBundle(assets.child("bundle"), Locale.ENGLISH);
        var settings = UpstreamSinglePlayerSession.loadSettings(root);
        var session = UpstreamSinglePlayerSession.create(root, "en", assets.child("planets"), settings,
            assets.child("maps"));
        try {
            var zero = SectorPresets.groundZero.sector;
            var onset = SectorPresets.onset.sector;
            if (zero.id != 170 || onset.id != 10) {
                throw new AssertionError("Stock Serpulo or Erekir starter sector IDs differ");
            }
            for (Sector sector : new Sector[]{zero, onset}) {
                if (sector.preset.generator.map == null) {
                    throw new AssertionError("Upstream preset generator has no original map: " + sector.preset.name);
                }
                Vars.logic.reset();
                Vars.world.loadSector(sector);
                session.application().runPosted();
                if (Vars.state.rules.sector != sector || Vars.world.width() != sector.preset.generator.map.width
                        || Vars.world.height() != sector.preset.generator.map.height || Vars.state.rules.defaultTeam.core() == null) {
                    throw new AssertionError("Cannot load the original starter world: " + sector.preset.name);
                }
                System.out.println("UPSTREAM_CAMPAIGN_PRESET planet=" + sector.planet.name
                    + " preset=" + sector.preset.name + " sector=" + sector.id
                    + " size=" + Vars.world.width() + "x" + Vars.world.height());
            }
        } finally {
            session.shutdown();
        }
    }
}
