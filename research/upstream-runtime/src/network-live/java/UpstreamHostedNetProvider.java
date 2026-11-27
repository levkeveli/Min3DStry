import arc.func.Cons;
import arc.math.Mathf;
import arc.net.Server.ServerConnectFilter;
import arc.struct.IntMap;
import arc.util.Log;
import arc.util.Time;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import mindustry.Vars;
import mindustry.net.Administration.Config;
import mindustry.net.Host;
import mindustry.net.Net;
import mindustry.net.NetConnection;
import mindustry.net.NetworkIO;
import mindustry.net.Packet;
import mindustry.net.Packets.Connect;
import mindustry.net.Packets.Disconnect;
import mindustry.net.Packets.StreamBegin;
import mindustry.net.Packets.StreamChunk;
import mindustry.net.Streamable;
import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

/** Delivers libctru socket events to the original v160.8 server and packet handlers. */
final class UpstreamHostedNetProvider implements Net.NetProvider {
    private static final int connected = 1, tcp = 2, udp = 3, disconnected = 4, discovery = 5;
    private static final int receiveBudget = 64, chunkBytes = 1024;
    private static final long keepaliveMillis = 8000, timeoutMillis = 12000;
    private static final byte[] keepalive = {(byte)0xfe, 2};

    private final UpstreamHostPacketCodec codec = new UpstreamHostPacketCodec();
    private final byte[] inbox = new byte[16384];
    private final int[] details = new int[3];
    private final IntMap<Peer> peers = new IntMap<>();
    private final int clientSlots;
    private ServerConnectFilter filter;
    private boolean open;

    UpstreamHostedNetProvider(int clientSlots) {
        this.clientSlots = clientSlots;
    }

    @Override
    public void hostServer(int port) throws IOException {
        if (open || Native.open(port, clientSlots, Mathf.rand.nextInt()) == 0) {
            throw new IOException("Unable to open the stock multiplayer port");
        }
        open = true;
        byte[] group = (Vars.multicastGroup + "\0").getBytes(StandardCharsets.US_ASCII);
        if (Native.multicast(Address.ofData(group), Vars.multicastPort) == 0) {
            Log.warn("Stock multiplayer multicast registration failed");
        }
    }

    void update() {
        if (!open) return;
        for (int n = 0; n < receiveBudget; n++) {
            int event = Native.poll(Address.ofData(inbox), inbox.length, Address.ofData(details));
            if (event == 0) break;
            int slot = details[0], size = details[2];
            switch (event) {
                case connected -> accept(slot, details[1]);
                case disconnected -> notifyDisconnect(peers.remove(slot));
                case discovery -> {
                    var data = NetworkIO.writeServerData();
                    Native.advertise(Address.ofData(data.array()), data.position());
                }
                case tcp, udp -> {
                    Peer peer = peers.get(slot);
                    if (peer != null && !peer.closing) receive(peer, event == tcp, size);
                }
                default -> throw new IllegalStateException("Unknown native host event " + event);
            }
        }
        long now = Time.millis();
        for (var entry : peers) {
            Peer peer = entry.value;
            if (peer.closing) continue;
            if (now - peer.lastReceivedTcp >= timeoutMillis) {
                peer.close();
                continue;
            }
            peer.pumpStream();
            if (now - peer.lastSentTcp >= keepaliveMillis) peer.write(keepalive, keepalive.length, true);
        }
    }

    private void accept(int slot, int address) {
        String ip = (address & 255) + "." + (address >>> 8 & 255) + "."
            + (address >>> 16 & 255) + "." + (address >>> 24);
        int limit = Vars.netServer.admins.getPlayerLimit();
        if ((limit > 0 && peers.size >= limit) || Vars.netServer.admins.isDosBlacklisted(ip)
                || (filter != null && !filter.accept(ip))) {
            Native.disconnect(slot);
            return;
        }
        var peer = new Peer(slot, ip);
        peers.put(slot, peer);
        var packet = new Connect();
        packet.addressTCP = ip;
        Vars.net.handleServerReceived(peer, packet);
    }

    private void notifyDisconnect(Peer peer) {
        if (peer == null) return;
        var packet = new Disconnect();
        packet.reason = "closed";
        Vars.net.handleServerReceived(peer, packet);
        System.out.println("UPSTREAM_HOST_PEER_LEFT slot=" + peer.slot);
    }

    private void receive(Peer peer, boolean reliable, int size) {
        if (reliable) peer.lastReceivedTcp = Time.millis();
        int spamLimit = Config.packetSpamLimit.num();
        if (spamLimit > 0 && !peer.packetRate.allow(3000, spamLimit)) {
            peer.blacklist();
            peer.close();
            return;
        }
        if ((inbox[0] & 255) == 0xfe) {
            // Arc's Connection replies to a framework Ping on TCP even if it arrived on UDP.
            if (size == 7 && inbox[1] == 0 && inbox[6] == 0) {
                inbox[6] = 1;
                peer.write(inbox, size, true);
            }
            return;
        }
        Packet packet;
        try {
            packet = codec.decode(inbox, size);
        } catch (RuntimeException invalid) {
            long now = Time.millis();
            if (now - peer.lastReadError < 2000) peer.close();
            peer.lastReadError = now;
            System.out.println("UPSTREAM_HOST_PACKET_ERROR decode=" + invalid.getClass().getName()
                + " message=" + invalid.getMessage());
            return;
        }
        try {
            Vars.net.handleServerReceived(peer, packet);
        } catch (RuntimeException failure) {
            System.out.println("UPSTREAM_HOST_PACKET_ERROR action=" + packet.getClass().getName()
                + " failure=" + failure.getClass().getName() + " message=" + failure.getMessage());
            throw failure;
        }
    }

    @Override
    public Iterable<NetConnection> getConnections() {
        var connections = new ArrayList<NetConnection>(peers.size);
        for (var entry : peers) connections.add(entry.value);
        return connections;
    }

    @Override
    public void closeServer() {
        for (NetConnection connection : getConnections()) {
            var peer = (Peer)connection;
            peer.close();
            notifyDisconnect(peers.remove(peer.slot));
        }
        if (open) Native.close();
        open = false;
        peers.clear();
    }

    @Override public void setConnectFilter(ServerConnectFilter next) { filter = next; }
    @Override public ServerConnectFilter getConnectFilter() { return filter; }
    @Override public void connectClient(String ip, int port, Runnable success) throws IOException {
        throw new IOException("The hosted game owns the server transport");
    }
    @Override public void sendClient(Object object, boolean reliable) {}
    @Override public void disconnectClient() {}
    @Override public void discoverServers(Cons<Host> callback, Runnable done) { done.run(); }
    @Override public void pingHost(String address, int port, Cons<Host> valid, Cons<Exception> failed) {
        failed.get(new IOException("Close the hosted game before browsing servers"));
    }

    private final class Peer extends NetConnection {
        final int slot;
        final ArrayDeque<PendingStream> streams = new ArrayDeque<>();
        long lastSentTcp = Time.millis(), lastReceivedTcp = lastSentTcp;
        long lastReadError = Long.MIN_VALUE / 2;
        boolean closing;

        Peer(int slot, String ip) {
            super(ip);
            this.slot = slot;
        }

        boolean write(byte[] bytes, int size, boolean reliable) {
            if (closing || Native.send(slot, Address.ofData(bytes), size, reliable ? 1 : 0) == 0) return false;
            if (reliable) lastSentTcp = Time.millis();
            return true;
        }

        @Override public void send(Object value, boolean reliable) {
            if (closing) return;
            int size;
            try {
                size = codec.encode((Packet)value);
            } catch (RuntimeException failure) {
                System.out.println("UPSTREAM_HOST_PACKET_ERROR encode=" + (value == null ? "null" : value.getClass().getName())
                    + " phase=" + codec.encodePhase() + " failure=" + failure.getClass().getName());
                throw failure;
            }
            if (!write(codec.output, size, reliable) && reliable) close();
        }

        @Override public void sendStream(Streamable stream) {
            if (closing) return;
            StreamBegin begin = new StreamBegin();
            begin.total = stream.stream.available();
            begin.type = Net.getPacketId(stream);
            streams.add(new PendingStream(begin, stream.stream));
            pumpStream();
        }

        void pumpStream() {
            var stream = streams.peek();
            if (stream == null) return;
            try {
                // Bound per-tick work and preserve unsent chunks when the socket queue fills.
                for (int sent = 0; sent < 8; sent++) {
                    if (stream.next == null) {
                        int size = Math.min(chunkBytes, stream.source.available());
                        if (size == 0) {
                            streams.remove();
                            stream.source.close();
                            return;
                        }
                        var chunk = new StreamChunk();
                        chunk.id = stream.begin.id;
                        // TeaVM 0.16 ByteArrayInputStream.readNBytes does not advance its cursor.
                        chunk.data = new byte[size];
                        if (stream.source.read(chunk.data, 0, size) != size) throw new IOException("Incomplete original world stream");
                        stream.next = chunk;
                    }
                    int size = codec.encode(stream.next);
                    if (!write(codec.output, size, true)) return;
                    stream.next = null;
                }
            } catch (IOException failure) {
                Log.err(failure);
                close();
            }
        }

        @Override public boolean isConnected() { return !closing && peers.get(slot) == this; }
        @Override public void close() {
            if (closing) return;
            closing = true;
            while (!streams.isEmpty()) {
                try {
                    streams.remove().source.close();
                } catch (IOException failure) {
                    Log.err(failure);
                }
            }
            Native.disconnect(slot);
        }
    }

    private static final class PendingStream {
        final StreamBegin begin;
        final InputStream source;
        Packet next;

        PendingStream(StreamBegin begin, InputStream source) {
            this.begin = begin;
            this.source = source;
            next = begin;
        }
    }

    @Include(value = "m3ds_host_net_bridge.h", isSystem = true)
    private static final class Native {
        @Import(name = "m3ds_host_net_open") static native int open(int port, int peers, int idSeed);
        @Import(name = "m3ds_host_net_multicast") static native int multicast(Address group, int port);
        @Import(name = "m3ds_host_net_close") static native void close();
        @Import(name = "m3ds_host_net_poll") static native int poll(Address data, int capacity, Address details);
        @Import(name = "m3ds_host_net_send") static native int send(int peer, Address data, int size, int reliable);
        @Import(name = "m3ds_host_net_disconnect") static native void disconnect(int peer);
        @Import(name = "m3ds_host_net_advertise") static native int advertise(Address data, int size);
    }
}
