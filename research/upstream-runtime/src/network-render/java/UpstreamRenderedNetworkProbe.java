import arc.Core;
import arc.files.Fi;
import arc.graphics.g2d.Draw;
import arc.struct.IntSet;
import java.io.IOException;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.UnitTypes;
import mindustry.core.World;
import mindustry.gen.Groups;
import mindustry.gen.Unit;
import mindustry.graphics.CacheLayer;

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
        CacheLayer.init();
        UpstreamLiveNetworkProbe.run(endpoint, view::loadGraphics, view::render, view.buildingControls);
    }

    private void loadGraphics() {
        UpstreamTerrainDrawProbe.loadAtlas(atlas, true);
        viewport = UpstreamTerrainDrawProbe.liveViewport(target);
        target.initialize();
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
        for (var building : Groups.build) {
            if ((building.block == Blocks.coreNucleus || building.block == Blocks.conveyor
                    || building.block == Blocks.copperWall)
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
        target.buildOverlay(buildingControls.cursorX() - viewport.left, buildingControls.cursorY() - viewport.bottom,
            buildingControls.block() == Blocks.copperWall ? 1 : 0, buildingControls.rotation(),
            buildingControls.removing());
    }
}
