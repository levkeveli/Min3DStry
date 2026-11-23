import arc.Core;
import arc.files.Fi;
import arc.graphics.Camera;
import arc.graphics.Texture;
import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
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
            verifyView(desktop, stock);
        } finally {
            session.shutdown();
        }
    }

    private static void verifyView(MinimapRenderer desktop, Method stockColor) throws Exception {
        Core.camera = new Camera();
        var glacier = desktopRegion(desktop, 80.125f, 120.75f);
        var projected = checkRegion(glacier, 80.125f, 120.75f);
        int size = 64;
        byte[] pixels = new byte[size * size * 4];
        UpstreamMinimap.sampleRegion(pixels, size, projected);
        var unit = Vars.player.unit();
        int unitX = unit == null ? -1 : Mathf.floor((unit.x / Vars.tilesize + 0.5f - projected.left()) * size / projected.span());
        int unitY = unit == null ? -1 : Mathf.floor((unit.y / Vars.tilesize + 0.5f - projected.bottom()) * size / projected.span());
        for (int y = 0; y < size; y++) {
            int tileY = Math.min(Vars.world.height() - 1,
                (int) (Mathf.lerp(1f - glacier.v2, 1f - glacier.v, (y + 0.5f) / size) * Vars.world.height()));
            for (int x = 0; x < size; x++) {
                int tileX = Math.min(Vars.world.width() - 1,
                    (int) (Mathf.lerp(glacier.u, glacier.u2, (x + 0.5f) / size) * Vars.world.width()));
                int offset = (y * size + x) * 4;
                int pixel = (pixels[offset] & 255) << 24 | (pixels[offset + 1] & 255) << 16
                    | (pixels[offset + 2] & 255) << 8 | (pixels[offset + 3] & 255);
                if (x == unitX && y == unitY) {
                    if (pixel != 0xffffffff) throw new AssertionError("Local marker did not follow the minimap region");
                } else {
                    checkPixel(desktop, stockColor, Vars.world.tile(tileX, tileY), pixel);
                }
            }
        }
        for (int[] dimensions : new int[][]{{250, 150}, {150, 250}, {24, 40}, {16, 10}}) {
            Vars.world.resize(dimensions[0], dimensions[1]);
            for (float[] point : new float[][]{{-20, -10}, {75.5f, 100.25f}, {300, 400}}) {
                checkRegion(desktopRegion(desktop, point[0], point[1]), point[0], point[1]);
            }
        }
        System.out.println("UPSTREAM_STOCK_MINIMAP_VIEW pixels=4096 regions=13 edges=exact");
    }

    private static TextureRegion desktopRegion(MinimapRenderer desktop, float x, float y) throws Exception {
        var texture = new Texture() { };
        texture.width = Vars.world.width();
        texture.height = Vars.world.height();
        var stockTexture = MinimapRenderer.class.getDeclaredField("texture");
        stockTexture.setAccessible(true);
        stockTexture.set(desktop, texture);
        var stockRegion = MinimapRenderer.class.getDeclaredField("region");
        stockRegion.setAccessible(true);
        stockRegion.set(desktop, new TextureRegion(texture));
        Core.camera.position.set(x * Vars.tilesize, y * Vars.tilesize);
        desktop.setZoom(4f);
        return desktop.getRegion();
    }

    private static UpstreamMinimap.Region checkRegion(TextureRegion stock, float x, float y) {
        int width = Vars.world.width(), height = Vars.world.height();
        var view = UpstreamMinimap.region(width, height, x, y);
        close(view.left(), stock.u * width);
        close(view.bottom(), (1f - stock.v2) * height);
        close(view.span(), (stock.u2 - stock.u) * width);
        close(view.span(), (stock.v2 - stock.v) * height);
        return view;
    }

    private static void close(float actual, float expected) {
        if (Math.abs(actual - expected) > 0.0001f) {
            throw new AssertionError("Stock minimap view differs: " + actual + " should be " + expected);
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
