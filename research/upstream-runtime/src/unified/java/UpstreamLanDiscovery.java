import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import mindustry.Vars;
import mindustry.net.Host;
import mindustry.net.NetworkIO;
import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

/** ArcNetProvider's LAN advertisement path over the handheld's SOC socket. */
final class UpstreamLanDiscovery implements AutoCloseable {
    // ArcNetProvider uses a 512-byte receive datagram and a three-second scan.
    private static final int WINDOW_MS = 3_000;
    private static final int NO_PACKET = -2;
    private final byte[] response = new byte[512];
    private final byte[] peer = new byte[4];
    private final List<Host> results = new ArrayList<>();
    private long started;
    private boolean scanning;

    boolean start(long now) {
        close();
        results.clear();
        byte[] group = (Vars.multicastGroup + '\0').getBytes(StandardCharsets.US_ASCII);
        scanning = Native.start(Vars.port, Address.ofData(group), Vars.multicastPort) != 0;
        started = now;
        return scanning;
    }

    boolean poll(long now) {
        if (!scanning) return false;
        if (now - started >= WINDOW_MS) {
            close();
            return false;
        }
        // Limit each UI frame to eight queued datagrams so the browser stays responsive.
        for (int i = 0; i < 8; i++) {
            int length = Native.read(Address.ofData(response), response.length, Address.ofData(peer));
            if (length == NO_PACKET) break;
            if (length < 0) {
                close();
                return false;
            }
            accept(results, response, length, peer, (int)(now - started));
        }
        return true;
    }

    List<Host> results() {
        return results;
    }

    @Override
    public void close() {
        if (scanning) {
            Native.close();
            scanning = false;
        }
    }

    static void accept(List<Host> hosts, byte[] response, int length, byte[] peer, int ping) {
        if (length <= 0 || length > response.length) return;
        String address = (peer[0] & 0xff) + "." + (peer[1] & 0xff) + "."
            + (peer[2] & 0xff) + "." + (peer[3] & 0xff);
        for (Host host : hosts) {
            if (host.address.equals(address)) return;
        }
        try {
            Host host = NetworkIO.readServerData(ping, address, ByteBuffer.wrap(response, 0, length));
            // NetworkIO reads a signed Java short while the advertised network port is unsigned.
            host.port &= 0xffff;
            hosts.add(host);
        } catch (BufferUnderflowException | ArrayIndexOutOfBoundsException ignored) {
            // Discovery can receive unrelated datagrams. Ignore malformed advertisements.
        }
    }

    @Include(value = "m3ds_net_bridge.h", isSystem = true)
    private static final class Native {
        @Import(name = "m3ds_live_net_discovery_start")
        static native int start(int port, Address group, int multicastPort);

        @Import(name = "m3ds_live_net_discovery_read")
        static native int read(Address packet, int capacity, Address peer);

        @Import(name = "m3ds_live_net_close")
        static native void close();
    }
}
