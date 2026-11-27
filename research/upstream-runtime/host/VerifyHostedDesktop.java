import arc.Core;
import arc.Events;
import arc.files.Fi;
import arc.util.ScreenUtils;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.desktop.DesktopLauncher;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.ClientCreateEvent;
import mindustry.game.EventType.Trigger;
import mindustry.gen.Unit;
import mindustry.world.Build;

public final class VerifyHostedDesktop {
    private static int tileX, tileY;
    private static long deadline;
    private static boolean passed, screenshotPending, placed;
    private static mindustry.world.Tile ore;

    public static void main(String[] args) {
        Events.on(ClientCreateEvent.class, event -> Core.settings.defaults("communityservers", false));
        Events.on(ClientLoadEvent.class, event -> {
            Vars.player.name = "New 3DS desktop test";
            Vars.ui.join.connect("127.0.0.1", 6567);
            deadline = System.nanoTime() + 35_000_000_000L;
            Timer.schedule(new Timer.Task() {
                @Override public void run() {
                    try {
                        if (System.nanoTime() > deadline) throw new IllegalStateException("Stock desktop session timed out");
                        if (ore != null && !Vars.net.client()) throw new IllegalStateException("Stock desktop lost its active session");
                        if (!Vars.net.client() || !Vars.state.isGame() || Vars.player.dead()) return;
                        if (ore == null) {
                            if (Vars.world.width() != 256 || Vars.world.height() != 256
                                    || Vars.player.team().core() == null
                                    || Vars.player.team().core().block != Blocks.coreShard) {
                                throw new IllegalStateException("Expected the hosted stock Ground Zero campaign world");
                            }
                            Unit unit = Vars.player.unit();
                            int copper = Vars.player.team().core().items.get(Items.copper);
                            System.out.println("STOCK_DESKTOP_HOST_JOIN graphics=" + !Vars.headless
                                + " map=" + Vars.state.map.name() + " unit=" + unit.type.name + " copper=" + copper);
                            for (int dy = -8; dy <= 8 && ore == null; dy++) {
                                for (int dx = -8; dx <= 8 && ore == null; dx++) {
                                    var tile = Vars.world.tile(unit.tileX() + dx, unit.tileY() + dy);
                                    if (tile != null && tile.block() == Blocks.air && tile.drop() == Items.copper
                                        && unit.within(tile.worldx(), tile.worldy(), unit.type.mineRange)) ore = tile;
                                }
                            }
                            if (ore == null) throw new IllegalStateException("No copper ore in starter mining range");
                            unit.mineTile(ore);
                            System.out.println("STOCK_DESKTOP_HOST_MINE x=" + ore.x + " y=" + ore.y);
                            return;
                        }
                        if (!placed) {
                            Unit unit = Vars.player.unit();
                            if (Vars.player.team().core().items.get(Items.copper) == 0) return;
                            unit.mineTile(null);
                            for (int dy = -4; dy <= 4 && tileX == 0; dy++) {
                                for (int dx = -4; dx <= 4 && tileX == 0; dx++) {
                                    int x = unit.tileX() + dx, y = unit.tileY() + dy;
                                    if (Math.abs(dx) + Math.abs(dy) < 3) continue;
                                    if (Build.validPlace(Blocks.conveyor, unit.team(), x, y, 0)) {
                                        tileX = x;
                                        tileY = y;
                                    }
                                }
                            }
                            if (tileX == 0) throw new IllegalStateException("No placeable conveyor near the stock player");
                            unit.addBuild(new BuildPlan(tileX, tileY, 0, Blocks.conveyor));
                            System.out.println("STOCK_DESKTOP_HOST_PLAN x=" + tileX + " y=" + tileY);
                            placed = true;
                            return;
                        }
                        if (Vars.world.tile(tileX, tileY).block() == Blocks.conveyor) {
                            screenshotPending = true;
                            cancel();
                        }
                    } catch (RuntimeException failure) {
                        failure.printStackTrace();
                        cancel();
                        Core.app.exit();
                    }
                }
            }, 0.25f, 0.25f);
        });
        Events.run(Trigger.postDraw, () -> {
            if (!screenshotPending) return;
            screenshotPending = false;
            ScreenUtils.saveScreenshot(new Fi(args[0]));
            System.out.println("STOCK_DESKTOP_HOST_BUILD block=conveyor x=" + tileX + " y=" + tileY);
            passed = true;
            Vars.netClient.disconnectQuietly();
            Core.app.exit();
        });
        DesktopLauncher.main(new String[0]);
        if (!passed) System.exit(1);
    }
}