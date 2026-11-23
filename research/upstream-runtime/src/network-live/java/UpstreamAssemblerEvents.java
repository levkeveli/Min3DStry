import arc.struct.Seq;
import mindustry.gen.AssemblerDroneSpawnedCallPacket;
import mindustry.gen.AssemblerUnitSpawnedCallPacket;
import mindustry.net.Net;
import mindustry.net.Packet;
import mindustry.world.blocks.units.UnitAssembler.UnitAssemblerBuild;

final class UpstreamAssemblerEvents {
    private final Seq<UnitAssemblerBuild> awaitingDrones = new Seq<>();

    static Packet packet(int id) {
        if (id == (Net.getPacketClassId(AssemblerUnitSpawnedCallPacket.class) & 0xff)) {
            return new AssemblerUnitSpawnedCallPacket();
        }
        if (id == (Net.getPacketClassId(AssemblerDroneSpawnedCallPacket.class) & 0xff)) {
            return new AssemblerDroneSpawnedCallPacket();
        }
        return null;
    }

    void apply(Packet event) {
        event.handleClient();
        if (event instanceof AssemblerDroneSpawnedCallPacket drone
                && drone.tile != null && drone.tile.build instanceof UnitAssemblerBuild assembler
                && assembler.syncLiveDrones()) {
            awaitingDrones.addUnique(assembler);
        }
    }

    void syncDrones() {
        awaitingDrones.removeAll(assembler -> assembler.tile.build != assembler || !assembler.syncLiveDrones());
    }
}
