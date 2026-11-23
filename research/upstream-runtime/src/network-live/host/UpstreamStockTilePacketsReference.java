import arc.struct.IntSeq;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Items;
import mindustry.game.Team;
import mindustry.gen.BuildDestroyedCallPacket;
import mindustry.gen.BuildHealthUpdateCallPacket;
import mindustry.gen.FillTileBlocksCallPacket;
import mindustry.gen.FillTileFloorsCallPacket;
import mindustry.gen.FillTileOverlaysCallPacket;
import mindustry.gen.RemoveTileCallPacket;
import mindustry.gen.SetFloorCallPacket;
import mindustry.gen.SetOverlayCallPacket;
import mindustry.gen.SetTeamCallPacket;
import mindustry.gen.SetTeamsCallPacket;
import mindustry.gen.SetTileBlocksCallPacket;
import mindustry.gen.SetTileCallPacket;
import mindustry.gen.SetTileFloorsCallPacket;
import mindustry.gen.SetTileOverlaysCallPacket;
import mindustry.net.Net;
import mindustry.net.Packet;

final class UpstreamStockTilePacketsReference {
    static void verify() {
        verifySurfaces();
        verifyBuildings();
        verifyPowerTeams();
        System.out.println("UPSTREAM_WORLD_TILE_PACKETS floor=3 overlay=3 block=3 health=ok destroy=ok team=2 power=reflowed");
    }

    private static void verifySurfaces() {
        var first = Vars.world.tile(35, 45);
        var second = Vars.world.tile(35, 46);
        var floor = new SetFloorCallPacket();
        floor.tile = first;
        floor.floor = Blocks.sand;
        floor.overlay = Blocks.oreLead;
        apply(floor);
        if (first.floor() != Blocks.sand || first.overlay() != Blocks.oreLead) throw new AssertionError("Stock floor paint");

        var overlay = new SetOverlayCallPacket();
        overlay.tile = first;
        overlay.overlay = Blocks.oreCopper;
        apply(overlay);
        if (first.overlay() != Blocks.oreCopper) throw new AssertionError("Stock overlay paint");

        int[] positions = {first.pos(), second.pos()};
        var floorList = new SetTileFloorsCallPacket();
        floorList.block = Blocks.stone;
        floorList.positions = positions;
        apply(floorList);
        if (first.floor() != Blocks.stone || second.floor() != Blocks.stone) throw new AssertionError("Stock floor batch");

        var overlayList = new SetTileOverlaysCallPacket();
        overlayList.block = Blocks.oreCoal;
        overlayList.positions = positions;
        apply(overlayList);
        if (first.overlay() != Blocks.oreCoal || second.overlay() != Blocks.oreCoal) throw new AssertionError("Stock overlay batch");

        var floorFill = new FillTileFloorsCallPacket();
        floorFill.x = floorFill.x2 = first.x;
        floorFill.y = first.y;
        floorFill.y2 = second.y;
        floorFill.block = Blocks.snow;
        apply(floorFill);
        if (first.floor() != Blocks.snow || second.floor() != Blocks.snow) throw new AssertionError("Stock floor fill");

        var overlayFill = new FillTileOverlaysCallPacket();
        overlayFill.x = overlayFill.x2 = first.x;
        overlayFill.y = first.y;
        overlayFill.y2 = second.y;
        overlayFill.block = Blocks.oreLead;
        apply(overlayFill);
        if (first.overlay() != Blocks.oreLead || second.overlay() != Blocks.oreLead) throw new AssertionError("Stock overlay fill");
    }

    private static void verifyBuildings() {
        var first = Vars.world.tile(40, 42);
        var second = Vars.world.tile(40, 43);
        var building = new SetTileCallPacket();
        building.tile = first;
        building.block = Blocks.titaniumConveyor;
        building.team = Team.sharded;
        building.rotation = 1;
        apply(building);
        var original = first.build;
        if (original.block != Blocks.titaniumConveyor || original.rotation != 1) throw new AssertionError("Stock block placement");

        var health = new BuildHealthUpdateCallPacket();
        health.buildings = IntSeq.with(first.pos(), Float.floatToIntBits(12f));
        apply(health);
        if (original.health != 12f) throw new AssertionError("Stock damage update");

        var team = new SetTeamCallPacket();
        team.build = original;
        team.team = Team.crux;
        apply(team);
        if (first.build != original || original.team != Team.crux || original.health != 12f) {
            throw new AssertionError("Stock team change replaced the building");
        }

        var remove = new RemoveTileCallPacket();
        remove.tile = first;
        apply(remove);
        if (first.block() != Blocks.air) throw new AssertionError("Stock scripted tile removal");

        var blocks = new SetTileBlocksCallPacket();
        blocks.positions = new int[]{first.pos(), second.pos()};
        blocks.block = Blocks.copperWall;
        blocks.team = Team.sharded;
        apply(blocks);
        if (first.block() != Blocks.copperWall || second.block() != Blocks.copperWall) throw new AssertionError("Stock block batch");

        var fill = new FillTileBlocksCallPacket();
        fill.x = fill.x2 = first.x;
        fill.y = first.y;
        fill.y2 = second.y;
        fill.block = Blocks.titaniumWall;
        fill.team = Team.crux;
        apply(fill);
        if (first.block() != Blocks.titaniumWall || second.block() != Blocks.titaniumWall
                || first.team() != Team.crux || second.team() != Team.crux) throw new AssertionError("Stock block fill");

        var destroyed = new BuildDestroyedCallPacket();
        destroyed.build = first.build;
        apply(destroyed);
        if (first.block() != Blocks.air || second.block() != Blocks.titaniumWall) throw new AssertionError("Stock building destruction");
    }

    private static void verifyPowerTeams() {
        var source = Vars.world.tile(45, 52);
        var receiver = Vars.world.tile(49, 52);
        var store = Vars.world.tile(45, 56);
        source.setBlock(Blocks.powerNode, Team.sharded);
        receiver.setBlock(Blocks.powerNode, Team.sharded);
        store.setBlock(Blocks.container, Team.sharded);
        store.build.items.add(Items.copper, 7);
        source.build.configured(null, receiver.pos());
        if (source.build.power.graph != receiver.build.power.graph
                || !source.build.power.links.contains(receiver.pos())) throw new AssertionError("Stock fixture power link");
        var teams = new SetTeamsCallPacket();
        teams.positions = new int[]{source.pos(), receiver.pos(), store.pos()};
        teams.team = Team.derelict;
        apply(teams);
        if (source.team() != Team.derelict || store.team() != Team.derelict || receiver.team() != Team.derelict
                || store.build.items.get(Items.copper) != 7 || source.build.power.graph != receiver.build.power.graph
                || !source.build.power.links.contains(receiver.pos()) || !receiver.build.power.links.contains(source.pos())) {
            throw new AssertionError("Stock bulk ownership change did not reflow power or keep inventory");
        }
    }

    private static void apply(Packet packet) {
        var received = UpstreamLiveNetworkProbe.tileMutationPacket(Net.getPacketId(packet) & 0xff);
        if (received == null || received.getClass() != packet.getClass()) {
            throw new AssertionError("Missing stock dispatch for " + packet.getClass().getSimpleName());
        }
        UpstreamSnapshotDispatchReference.applyPacket(packet, received);
    }
}
