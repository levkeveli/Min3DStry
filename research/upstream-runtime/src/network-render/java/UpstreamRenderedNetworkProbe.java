import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.Draw;
import arc.struct.IntSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.core.World;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.CacheLayer;
import mindustry.world.blocks.distribution.Sorter;

/** Presents the received Glacier world through the ARM11 terrain renderer. */
public final class UpstreamRenderedNetworkProbe {
    private final Fi atlas;
    private final UpstreamTerrainRenderProbe target = new UpstreamTerrainRenderProbe(false);
    private final UpstreamLiveBuildControls buildingControls = new UpstreamLiveBuildControls();
    private final Runnable drawEntities = this::drawEntities;
    private final IntSet reportedConveyors = new IntSet();
    private UpstreamTerrainDrawProbe.Viewport viewport;
    private Unit controlled;
    private int draws;
    private int lastLeft = -1, lastBottom = -1;

    private UpstreamRenderedNetworkProbe(Fi atlas) {
        this.atlas = atlas;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        var endpoint = UpstreamLiveNetworkProbe.endpointFile(args);
        var view = new UpstreamRenderedNetworkProbe(endpoint.sibling("sprites.aatls"));
        int language = view.target.mountAssets();
        var preference = endpoint.sibling("locale.txt");
        String requested = preference.exists() && preference.length() <= 16 ? preference.readString("UTF-8").trim() : "";
        Core.bundle = UpstreamInterfaceLocale.load(new Fi("/romfs/bundles/bundle"), language, requested);
        var locale = Core.bundle.getLocale();
        System.out.println("UPSTREAM_INTERFACE_LOCALE system=" + language + " bundle="
            + (locale.getLanguage().isEmpty() ? "en" : locale) + " router=" + Core.bundle.get("block.router.name")
            + " copper=" + Core.bundle.get("item.copper.name"));
        CacheLayer.init();
        UpstreamLiveNetworkProbe.run(endpoint, view::loadGraphics, view::render, view.buildingControls);
    }

    private void loadGraphics() {
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS atlas");
        UpstreamTerrainDrawProbe.loadAtlas(atlas, true);
        viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        target.initialize();
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS labels");
        var labels = String.join("\0", Blocks.conveyor.localizedName, Blocks.copperWall.localizedName,
            Core.bundle.get("keybind.rotate.name"), Core.bundle.get("waves.remove"),
            "List", "Config", Core.bundle.get("back")) + "\0";
        target.buildLabels(labels.getBytes(StandardCharsets.UTF_8));
        System.out.println("UPSTREAM_INTERFACE_GRAPHICS ready");
    }

    private void render(Unit unit) {
        if (lastLeft == -1 && !Vars.state.map.name().equals("Glacier")) {
            throw new IllegalStateException("The rendered multiplayer probe is packaged for Glacier");
        }
        controlled = unit;
        float x = unit == null ? Vars.player.x() : unit.x();
        float y = unit == null ? Vars.player.y() : unit.y();
        viewport.moveTo(World.toTile(x) - UpstreamTerrainDrawProbe.VIEW_WIDTH / 2,
                        World.toTile(y) - UpstreamTerrainDrawProbe.VIEW_HEIGHT / 2);
        draws = 0;
        viewport.draw(drawEntities);
        buildingControls.viewport(viewport.left, viewport.bottom);
        if (unit != null && (lastLeft != viewport.left || lastBottom != viewport.bottom)) {
            lastLeft = viewport.left;
            lastBottom = viewport.bottom;
            System.out.println("UPSTREAM_NETWORK_FRAME unit=" + unit.id() + " left=" + lastLeft
                + " bottom=" + lastBottom + " sprites=" + draws);
        }
    }

    private void drawEntities() {
        // The upstream tile renderer caches both walls and routers.
        int right = Math.min(viewport.left + UpstreamTerrainDrawProbe.VIEW_WIDTH, Vars.world.width());
        int top = Math.min(viewport.bottom + UpstreamTerrainDrawProbe.VIEW_HEIGHT, Vars.world.height());
        for (int y = viewport.bottom; y < top; y++) {
            for (int x = viewport.left; x < right; x++) {
                var tile = Vars.world.tile(x, y);
                if ((tile.block() == Blocks.copperWall || tile.block() == Blocks.router
                        || tile.build instanceof Sorter.SorterBuild) && tile.build != null) {
                    tile.build.drawCached();
                }
            }
        }
        for (var building : Groups.build) {
            if ((building.block == Blocks.coreNucleus || building.block == Blocks.conveyor)
                    && viewport.contains(building)) {
                building.draw();
                if (building.block == Blocks.conveyor && reportedConveyors.add(building.pos())) {
                    System.out.println("UPSTREAM_NETWORK_CONVEYOR x=" + building.tileX()
                        + " y=" + building.tileY() + " rotation=" + building.rotation);
                }
            }
        }
        for (var unit : Groups.unit) {
            if (unit.type() != UnitTypes.gamma && unit.type() != UnitTypes.dagger) continue;
            int x = World.toTile(unit.x()), y = World.toTile(unit.y());
            if (x < viewport.left || x >= viewport.left + UpstreamTerrainDrawProbe.VIEW_WIDTH
                    || y < viewport.bottom || y >= viewport.bottom + UpstreamTerrainDrawProbe.VIEW_HEIGHT) continue;
            // UnitType.drawBody uses the atlas region, world position and rotation minus 90 degrees.
            Draw.rect(Core.atlas.find(unit.type().name), unit.x(), unit.y(), unit.rotation() - 90f);
            if (unit == controlled) draws++;
        }
        var block = buildingControls.block();
        var catalog = buildingControls.catalog();
        var config = buildingControls.itemConfig();
        int shortcut = block == Blocks.conveyor ? 0 : block == Blocks.copperWall ? 1 : -1;
        target.buildOverlay(buildingControls.cursorX() - viewport.left, buildingControls.cursorY() - viewport.bottom,
            shortcut, buildingControls.rotation(), buildingControls.removing(), block.rotate,
            catalog.isOpen() || config.isOpen(), buildingControls.configuring());
        if (catalog.isOpen()) {
            target.buildCatalog(catalog.labels(), catalog.selectedRow(block), catalog.previousPage(), catalog.nextPage(), true);
        } else if (config.isOpen()) {
            target.buildCatalog(config.labels(), config.selectedRow(), config.previousPage(), config.nextPage(), false);
        }
    }
}
