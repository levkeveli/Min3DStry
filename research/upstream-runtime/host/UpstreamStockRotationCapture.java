import arc.net.Client;
import arc.net.Connection;
import arc.net.DcReason;
import arc.net.NetListener;
import arc.struct.Seq;
import arc.util.serialization.Base64Coder;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mindustry.Vars;
import mindustry.core.Version;
import mindustry.gen.ConnectConfirmCallPacket;
import mindustry.gen.WorldDataBeginCallPacket;
import mindustry.net.ArcNetProvider;
import mindustry.net.Net;
import mindustry.net.Packets;

/** Captures the original stream bytes before and after rotation on one Arc connection. */
public final class UpstreamStockRotationCapture implements NetListener {
    private final Path directory;
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicReference<Throwable> error = new AtomicReference<>();
    private Packets.StreamBegin begin;
    private ByteArrayOutputStream file;
    private int worlds;
    private boolean rotating;

    private UpstreamStockRotationCapture(Path directory) {
        this.directory = directory;
    }

    @Override
    public void connected(Connection connection) {
        var packet = new Packets.ConnectPacket();
        packet.version = 160;
        packet.versionType = "official";
        packet.name = "Rotation Fixture";
        packet.locale = "en";
        packet.usid = "m3ds-rotation";
        packet.uuid = new String(Base64Coder.encode(new byte[] {72, 87, 19, 23, 12, 44, 78, 1}));
        packet.mobile = true;
        packet.color = 0xFFB841FF;
        packet.mods = new Seq<>();
        connection.sendTCP(packet);
    }

    @Override
    public void received(Connection connection, Object packet) {
        try {
            if (packet instanceof WorldDataBeginCallPacket && worlds == 1) {
                rotating = true;
            } else if (packet instanceof Packets.StreamBegin header) {
                if ((header.type & 0xff) != Net.packetIdWorldStream || header.total <= 0
                        || worlds > 1 || (worlds == 1 && !rotating)) {
                    throw new IllegalStateException("Unexpected stock map rotation stream");
                }
                begin = header;
                file = new ByteArrayOutputStream();
            } else if (packet instanceof Packets.StreamChunk chunk) {
                if (begin == null || chunk.id != begin.id || chunk.data.length > begin.total - file.size()) {
                    throw new IllegalStateException("Unexpected stock WorldStream chunk");
                }
                file.write(chunk.data);
                if (file.size() == begin.total) {
                    worlds++;
                    Files.write(directory.resolve("world-" + worlds + ".bin"), file.toByteArray());
                    connection.sendTCP(new ConnectConfirmCallPacket());
                    System.out.println("STOCK_ROTATION_CAPTURE world=" + worlds + " id=" + begin.id + " bytes=" + begin.total);
                    if (worlds == 2) done.countDown();
                    begin = null;
                }
            }
        } catch (Exception failure) {
            error.set(failure);
            done.countDown();
        }
    }

    @Override
    public void disconnected(Connection connection, DcReason reason) {
        if (done.getCount() != 0) {
            error.compareAndSet(null, new IllegalStateException("Disconnected during stock rotation: " + reason));
            done.countDown();
        }
    }

    public static void main(String[] args) throws Exception {
        Version.build = 160;
        Vars.net = new Net(null);
        var listener = new UpstreamStockRotationCapture(Path.of(args[1]));
        int port = Integer.parseInt(args[0]);
        var client = new Client(16_384, ArcNetProvider.clientReadBufferSize, new ArcNetProvider.PacketSerializer());
        client.addListener(listener);
        var thread = new Thread(client, "stock-rotation");
        thread.setDaemon(true);
        thread.start();
        try {
            client.connect(5000, "127.0.0.1", port, port);
            if (!listener.done.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for stock rotation");
            if (listener.error.get() != null) throw new IllegalStateException("Stock rotation failed", listener.error.get());
        } finally {
            client.close();
            client.stop();
        }
    }
}
