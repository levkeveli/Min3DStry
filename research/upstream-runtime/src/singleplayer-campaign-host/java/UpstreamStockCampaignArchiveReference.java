import arc.Core;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.content.SectorPresets;
import mindustry.io.MapIO;
import mindustry.type.Sector;

public final class UpstreamStockCampaignArchiveReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected staged assets and a new save directory");
        var assets = new Fi(args[0]);
        var output = new Fi(args[1]);
        if (output.exists()) throw new IllegalArgumentException("Use a new scratch campaign directory");
        output.mkdirs();
        Core.bundle = I18NBundle.createBundle(assets.child("bundle"), Locale.ENGLISH);
        var maps = assets.child("maps");
        var session = UpstreamSinglePlayerSession.create(output, "en", assets.child("planets"),
            UpstreamSinglePlayerSession.loadSettings(output), maps);
        try {
            int total = 0;
            int hidden = 0;
            for (String directory : new String[]{"serpulo", "serpulo/hidden", "erekir"}) {
                for (var file : maps.child(directory).list()) {
                    if (!file.extension().equals("msav")) continue;
                    var registered = Vars.tree.get("maps/" + directory + "/" + file.name());
                    var map = MapIO.createMap(registered, false);
                    if (!registered.equals(file) || map.width <= 0 || map.height <= 0) {
                        throw new AssertionError("Missing stock map registration or dimensions for " + file);
                    }
                    if (directory.equals("serpulo/hidden")) {
                        var sector = Planets.serpulo.sectors.get(Integer.parseInt(file.nameWithoutExtension()));
                        if (sector.preset == null || sector.preset.requireUnlock
                                || !file.equals(sector.preset.generator.map.file)) {
                            throw new AssertionError("Numbered sector has the wrong original preset map: " + file);
                        }
                        hidden++;
                    }
                    total++;
                }
            }
            int bound = 0;
            for (var preset : Vars.content.sectors()) {
                if (preset.sector == null) continue;
                if (preset.generator.map == null || !preset.generator.map.file.exists()) {
                    throw new AssertionError("Active preset has no original map: " + preset.name);
                }
                bound++;
            }
            if (total != 95 || bound != 95 || hidden != 46) throw new AssertionError("Expected all original campaign maps");
            verifyPatchedWorld(session, SectorPresets.littoralShipyard.sector, 2);
            verifyPatchedWorld(session, Planets.serpulo.sectors.get(242), 1);
            System.out.println("UPSTREAM_STOCK_CAMPAIGN_ARCHIVE maps=" + total + " presets=" + bound
                + " hidden=" + hidden + " patches=3");
        } finally {
            session.shutdown();
        }
    }

    private static void verifyPatchedWorld(UpstreamSinglePlayerSession session, Sector sector, int count)
            throws Exception {
        Vars.logic.reset();
        Vars.world.loadSector(sector);
        session.application().runPosted();
        var original = sector.preset.generator.map;
        if (Vars.state.rules.sector != sector || Vars.state.rules.defaultTeam.core() == null
                || Vars.world.width() != original.width || Vars.world.height() != original.height
                || Vars.state.data.getPatches().size != count) {
            throw new AssertionError("Stock world or embedded data patches differ for " + sector.id);
        }
        var patches = Vars.state.data.getPatches().map(asset -> asset.path + "\0" + asset.patch);
        var slot = session.sectorFile(sector);
        session.save(slot);
        if (!session.start(null, slot, "stock-campaign-archive", sector)
                || !Vars.state.data.getPatches().map(asset -> asset.path + "\0" + asset.patch).equals(patches)) {
            throw new AssertionError("Stock embedded patches did not survive a save for " + sector.id);
        }
        System.out.println("UPSTREAM_STOCK_CAMPAIGN_PATCH sector=" + sector.id + " patches=" + count);
    }
}
