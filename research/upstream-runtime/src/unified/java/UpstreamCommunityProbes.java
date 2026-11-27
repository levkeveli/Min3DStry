import arc.struct.Seq;
import mindustry.net.Host;
import mindustry.net.NetworkIO;
import mindustry.net.ServerGroup;
import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Bounded native UDP probes with stock NetworkIO decoding on the game thread. */
final class UpstreamCommunityProbes {
    record Result(ServerGroup group, UpstreamServerAddress address, Host host) {}

    private record Endpoint(ServerGroup group, UpstreamServerAddress address) {}

    private UpstreamCommunityProbes() {
    }

    static Seq<Result> query(Seq<ServerGroup> groups, BooleanSupplier canceled) throws InterruptedException {
        var endpoints = new Seq<Endpoint>();
        for (ServerGroup group : groups) {
            for (String entry : group.addresses) {
                var address = UpstreamServerAddress.parse(entry);
                if (address != null) endpoints.add(new Endpoint(group, address));
            }
        }
        var results = new Seq<Result>();
        if (endpoints.isEmpty()) return results;
        int slots = Native.open();
        if (slots <= 0) return results;
        try {
            var pending = new Endpoint[slots];
            byte[] packet = new byte[512];
            int[] ping = new int[1];
            int next = 0, active = 0;
            while ((next < endpoints.size || active > 0) && !canceled.getAsBoolean()) {
                for (int slot = 0; slot < slots; slot++) {
                    if (pending[slot] != null) {
                        int length = Native.poll(slot, Address.ofData(packet), packet.length, Address.ofData(ping));
                        if (length == 0) continue;
                        if (length > 0) {
                            Host host = decode(pending[slot].address, packet, length, ping[0]);
                            if (host != null) results.add(new Result(pending[slot].group, pending[slot].address, host));
                        }
                        pending[slot] = null;
                        active--;
                    }
                    if (next < endpoints.size) {
                        Endpoint endpoint = endpoints.get(next++);
                        byte[] name = endpoint.address.host().getBytes(StandardCharsets.US_ASCII);
                        if (Native.start(slot, Address.ofData(name), name.length, endpoint.address.port()) != 0) {
                            pending[slot] = endpoint;
                            active++;
                        }
                    }
                }
                Thread.sleep(16);
            }
            return results;
        } finally {
            Native.close();
        }
    }

    static Host decode(UpstreamServerAddress address, byte[] packet, int length, int ping) {
        if (length <= 0 || length > packet.length) return null;
        try {
            Host host = NetworkIO.readServerData(ping, address.host(), ByteBuffer.wrap(packet, 0, length));
            // ArcNetProvider uses the queried port for remote hosts.
            host.port = address.port();
            return host;
        } catch (BufferUnderflowException | ArrayIndexOutOfBoundsException invalid) {
            return null;
        }
    }

    @Include(value = "m3ds_community_probe.h", isSystem = true)
    private static final class Native {
        @Import(name = "m3ds_community_probe_open")
        static native int open();

        @Import(name = "m3ds_community_probe_start")
        static native int start(int slot, Address host, int length, int port);

        @Import(name = "m3ds_community_probe_poll")
        static native int poll(int slot, Address packet, int capacity, Address ping);

        @Import(name = "m3ds_community_probe_close")
        static native void close();
    }
}
