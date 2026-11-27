import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import mindustry.net.Host;

/** Read a real advertisement emitted by the unmodified v160.8 server. */
public final class UpstreamLanDiscoveryReference {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String map = args[1];
        byte[] response = new byte[512];
        DatagramPacket packet = new DatagramPacket(response, response.length);
        try (var socket = new DatagramSocket()) {
            socket.connect(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(250);
            byte[] request = {(byte)0xfe, 1};
            for (int i = 0; i < 100; i++) {
                try {
                    socket.send(new DatagramPacket(request, request.length));
                    socket.receive(packet);
                    break;
                } catch (SocketTimeoutException | java.net.PortUnreachableException retry) {
                    if (i == 99) throw retry;
                    Thread.sleep(250);
                }
            }
        }

        var hosts = new ArrayList<Host>();
        byte[] peer = packet.getAddress().getAddress();
        UpstreamLanDiscovery.accept(hosts, response, packet.getLength() - 1, peer, 5);
        if (!hosts.isEmpty()) throw new AssertionError("A truncated advertisement was accepted");

        UpstreamLanDiscovery.accept(hosts, response, packet.getLength(), peer, 5);
        UpstreamLanDiscovery.accept(hosts, response, packet.getLength(), peer, 6);
        if (hosts.size() != 1) throw new AssertionError("Expected one deduplicated stock advertisement");
        Host host = hosts.get(0);
        if (host.port != port || !host.mapname.equals(map) || host.version != 160
                || !host.versionType.equals("official") || host.name.isBlank() || host.players != 0
                || !host.address.equals("127.0.0.1") || host.mode == null) {
            throw new AssertionError("Stock server advertisement did not preserve the game fields");
        }
        System.out.println("UPSTREAM_LAN_DISCOVERY_REFERENCE map=" + host.mapname
            + " port=" + host.port + " version=" + host.version + " mode=" + host.mode.name());
    }
}
