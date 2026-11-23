import arc.files.Fi;
import java.lang.reflect.Method;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.Team;
import mindustry.graphics.MinimapRenderer;
import mindustry.world.Tile;

public final class UpstreamStockMinimapReference {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected Glacier and block_colors.png");
        var map = new Fi(args[0]);
        var session = UpstreamSinglePlayerSession.create(map.parent(), "en");
        try {
            session.start(map, null, "stock-minimap");
            if (!"Glacier".equals(Vars.state.map.name())) throw new AssertionError("Cannot load the stock Glacier map");
            Vars.content.loadColors(new Fi(args[1]));

            Tile ore = floor(40, 40, false);
            ore.setOverlay(Blocks.oreCopper);
            Tile shadow = floor(45, 40, false);
            floor(45, 41, false).setBlock(Blocks.snowWall);
            Tile water = floor(50, 40, true);
            floor(50, 41, false);
            Tile building = floor(55, 40, false);
            floor(55, 41, false);
            building.setBlock(Blocks.copperWall, Team.crux);
            building.build.wasVisible = false;

            var desktop = new MinimapRenderer();
            Method stock = MinimapRenderer.class.getDeclaredMethod("colorFor", Tile.class);
            stock.setAccessible(true);
            for (Tile tile : new Tile[]{ore, shadow, water, building, Vars.world.tile(0, 0)}) {
                checkPixel(desktop, stock, tile, UpstreamMinimapColors.colorFor(tile));
            }

            Vars.state.rules.fog = true;
            int hidden = UpstreamMinimapColors.colorFor(building);
            checkPixel(desktop, stock, building, hidden);
            building.build.wasVisible = true;
            int visible = UpstreamMinimapColors.colorFor(building);
            checkPixel(desktop, stock, building, visible);
            if (hidden == visible) throw new AssertionError("Fixture did not exercise the original fog rule");

            int size = 64;
            byte[] pixels = UpstreamMinimap.sample(size);
            var unit = Vars.player.unit();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    Tile tile = Vars.world.tile(x * Vars.world.width() / size, y * Vars.world.height() / size);
                    int offset = (x + y * size) * 4;
                    int pixel = (pixels[offset] & 255) << 24 | (pixels[offset + 1] & 255) << 16
                        | (pixels[offset + 2] & 255) << 8 | (pixels[offset + 3] & 255);
                    if (unit != null && unit.tileX() == tile.x && unit.tileY() == tile.y) {
                        if (pixel != 0xffffffff) throw new AssertionError("Missing local player marker");
                    } else {
                        checkPixel(desktop, stock, tile, pixel);
                    }
                }
            }
            System.out.println("UPSTREAM_STOCK_MINIMAP pixels=4096 ore=exact shadow=exact liquid=exact fog=exact");
        } finally {
            session.shutdown();
        }
    }

    private static Tile floor(int x, int y, boolean water) {
        Tile tile = Vars.world.tile(x, y);
        tile.setBlock(Blocks.air);
        tile.setFloor((water ? Blocks.water : Blocks.snow).asFloor());
        tile.setOverlay(Blocks.air);
        return tile;
    }

    private static void checkPixel(MinimapRenderer desktop, Method stock, Tile tile, int actual) throws Exception {
        int expected = (int) stock.invoke(desktop, tile);
        if (actual != expected) throw new AssertionError("Minimap RGBA differs at " + tile.x + "," + tile.y);
    }
}
