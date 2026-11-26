import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.util.I18NBundle;
import java.util.Locale;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.content.Planets;
import mindustry.content.UnitTypes;
import mindustry.game.EventType.GameOverEvent;
import mindustry.io.SaveIO;
import mindustry.type.Sector;
import mindustry.world.Build;
import mindustry.world.Tile;

/** Lose the stock campaign through the real Logic.update core-loss event. */
public final class UpstreamCampaignDefeatReference {
    public static void main(String[] args) throws Exception {
        Fi assets = new Fi(args[0]), root = new Fi(args[1]);
        if (root.exists()) throw new IllegalArgumentException("Use a new scratch campaign directory");
        root.mkdirs();
        Core.bundle = I18NBundle.createBundle(assets.child("bundle"), Locale.ENGLISH);
        var session = UpstreamSinglePlayerSession.create(root, "en", assets.child("planets"),
            UpstreamSinglePlayerSession.loadSettings(root), assets.child("maps"));
        var losses = new int[1];
        Events.on(GameOverEvent.class, event -> {
            if (Vars.state.rules.sector == null || event.winner != Vars.state.rules.waveTeam) {
                throw new AssertionError("Unexpected stock campaign result");
            }
            losses[0]++;
        });
        try {
            verifyDefeat(session, root, Planets.serpulo.getStartSector(), losses, 1);
            verifyDefeat(session, root, Planets.erekir.getStartSector(), losses, 2);
        } finally {
            session.shutdown();
        }
    }

    private static void verifyDefeat(UpstreamSinglePlayerSession session, Fi root, Sector sector,
                                     int[] losses, int expected) throws Exception {
        Fi slot = session.sectorFile(sector);
        session.launchNewSector(sector, sector, slot);
        var core = session.core();
        int wave = Vars.state.wave;
        if (core == null || session.saveCampaignDefeat(slot)) {
            throw new AssertionError("A fresh sector was misclassified as defeated");
        }

        var team = Vars.state.rules.defaultTeam;
        boolean clears = sector.planet.clearSectorOnLose || sector.planet.campaignRules.clearSectorOnLose;
        var wall = clears ? Blocks.berylliumWall : Blocks.copperWall;
        Tile placed = null;
        for (Tile tile : Vars.world.tiles) {
            if (tile.x > 20 && tile.y > 20 && tile.x < Vars.world.width() - 20 && tile.y < Vars.world.height() - 20
                    && tile.build == null && Build.validPlace(wall, team, tile.x, tile.y, 0, false, false)) {
                placed = tile;
                break;
            }
        }
        if (placed == null) throw new AssertionError("Fixture has no valid wall location");
        int x = placed.x, y = placed.y;
        placed.setBlock(wall, team);
        core.health = 1f;
        var enemy = clears ? UnitTypes.stell : UnitTypes.dagger;
        for (int offset : new int[]{-32, 32}) {
            enemy.spawn(Vars.state.rules.waveTeam, core.x + offset, core.y);
            enemy.spawn(Vars.state.rules.waveTeam, core.x, core.y + offset);
        }
        session.save(slot);
        Fi threatened = root.child("threatened").child(sector.planet.name);
        slot.copyTo(threatened.child(slot.name()));
        Core.settings.forceSave();
        Core.settings.getSettingsFile().copyTo(threatened.child("settings.bin"));

        byte[] original = slot.readBytes();
        Vars.logic.reset();
        if (!session.start(null, slot, "campaign-threatened-reference", sector) || session.core() == null
                || session.core().health != 1f) {
            throw new AssertionError("The threatened base did not reload in its original state");
        }
        for (int tick = 0; tick < 1800 && !Vars.state.gameOver; tick++) {
            session.stepSimulation(0f, 0f);
        }
        if (losses[0] != expected || !Vars.state.gameOver || session.core() != null
                || !session.saveCampaignDefeat(slot)) {
            throw new AssertionError("Upstream Logic did not report the core loss");
        }
        sector.loadInfo();
        if (sector.info.hasCore || Vars.state.stats.wavesLasted != wave
                || !SaveIO.isSaveValid(slot)
                || !java.util.Arrays.equals(SaveIO.backupFileFor(slot).readBytes(), original)) {
            throw new AssertionError("Defeat did not persist the original save and backup state");
        }
        int spawn = sector.info.spawnPosition, attempts = sector.info.attempts;
        Fi lost = root.child("lost").child(sector.planet.name);
        slot.copyTo(lost.child(slot.name()));
        lost.child("placement.txt").writeString(x + " " + y + " " + spawn);
        Core.settings.forceSave();
        Core.settings.getSettingsFile().copyTo(lost.child("settings.bin"));
        Vars.logic.reset();
        var retry = session.campaignSlots(session.sectorSaves()).stream()
            .filter(candidate -> candidate.sector == sector).findFirst().orElseThrow();
        if (retry.origin != sector || !UpstreamSinglePlayerSession.needsCampaignRetry(retry)) {
            throw new AssertionError("The lost starter did not offer retry: origin=" + retry.origin
                + " hasCore=" + sector.info.hasCore + " hasSave=" + sector.hasSave()
                + " recorded=" + Core.settings.has(sector.planet.name + "-s-" + sector.id + "-info"));
        }
        session.retryCampaignSector(retry, null);
        var rebuilt = Vars.world.tile(x, y);
        boolean restored = rebuilt.block() == wall && rebuilt.team() == Team.derelict;
        if (session.core() == null || !sector.hasBase() || sector.info.attempts != attempts + 1
                || (!clears && session.core().pos() != spawn) || restored == clears) {
            throw new AssertionError("Retry did not follow the original terrain and derelict rules for "
                + sector.planet.name + " (clears=" + clears + ", restored=" + restored + ")");
        }
        boolean hasPlan = false;
        for (var plan : Vars.state.rules.defaultTeam.data().plans) {
            if (plan.x == x && plan.y == y && plan.block == wall) hasPlan = true;
        }
        if (!clears && !hasPlan) throw new AssertionError("Stock retry did not retain the rebuild plan");
        System.out.println("UPSTREAM_CAMPAIGN_DEFEAT_REFERENCE planet=" + sector.planet.name
            + " sector=" + sector.id + " noCore=true retry=ok derelict=" + restored + " backup=ok");
    }
}
