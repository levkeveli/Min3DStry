import arc.Core;
import arc.files.Fi;
import arc.mock.MockApplication;
import arc.mock.MockAudio;
import arc.mock.MockFiles;
import arc.mock.MockGraphics;
import arc.mock.MockSettings;
import arc.net.Client;
import arc.net.Connection;
import arc.net.DcReason;
import arc.net.NetListener;
import arc.struct.Seq;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.core.ContentLoader;
import mindustry.core.GameState;
import mindustry.core.NetClient;
import mindustry.core.World;
import mindustry.core.Version;
import mindustry.gen.ConnectConfirmCallPacket;
import mindustry.gen.Groups;
import mindustry.gen.Player;
import mindustry.gen.SendChatMessageCallPacket;
import mindustry.gen.SendMessageCallPacket2;
import mindustry.gen.StateSnapshotCallPacket;
import mindustry.maps.Maps;
import mindustry.mod.Mods;
import mindustry.net.ArcNetProvider;
import mindustry.net.Net;
import mindustry.net.NetworkIO;
import mindustry.net.Packets.ConnectPacket;
import mindustry.net.Packets.StreamBegin;
import mindustry.net.Packets.StreamChunk;
import mindustry.net.Streamable.StreamBuilder;

/** Records the compressed payload sent by a local upstream headless server. */
public final class UpstreamWorldStreamCapture implements NetListener {
    private static final String chatText = "New 3DS crossplay reference";
    private final CompletableFuture<byte[]> world = new CompletableFuture<>();
    private final CompletableFuture<Void> snapshot = new CompletableFuture<>();
    private final CompletableFuture<Void> chat = new CompletableFuture<>();
    private StreamBuilder builder;

    public static void main(String[] args) throws Exception {
        boolean confirm = args.length == 3 && args[2].equals("--confirm");
        if (args.length != 2 && !confirm) {
            throw new IllegalArgumentException("Expected a loopback port, new fixture path and optional --confirm");
        }
        int port = Integer.parseInt(args[0]);
        Version.init();
        if (Version.build != 160 || Version.revision != 8 || !Version.type.equals("official")) {
            throw new IllegalArgumentException("Run with the official v160.8 server JAR");
        }
        Vars.net = new Net(null);
        Vars.net.setClientConnected();
        var capture = new UpstreamWorldStreamCapture();
        var client = new Client(16_384, ArcNetProvider.clientReadBufferSize, new ArcNetProvider.PacketSerializer());
        client.addListener(capture);
        client.start();
        try {
            client.connect(5_000, "127.0.0.1", port, port);
            byte[] payload = capture.world.get(10, TimeUnit.SECONDS);
            Files.write(Path.of(args[1]), payload, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.println("UPSTREAM_WORLD_STREAM_CAPTURE bytes=" + payload.length);
            if (confirm) {
                loadStockWorld(payload, Path.of(args[1]).toAbsolutePath().getParent());
                client.sendTCP(new ConnectConfirmCallPacket());
                capture.snapshot.get(10, TimeUnit.SECONDS);
                System.out.println("UPSTREAM_HOSTED_WORLD_CONFIRMED stock-snapshot=true");
                // Stock NetClient ignores messages from connections younger than 500 ms.
                Thread.sleep(600);
                var message = new SendChatMessageCallPacket();
                message.message = chatText;
                client.sendTCP(message);
                capture.chat.get(10, TimeUnit.SECONDS);
                System.out.println("UPSTREAM_HOSTED_WORLD_CHAT stock-echo=true");
            }
        } finally {
            client.stop();
        }
    }

    private static void loadStockWorld(byte[] payload, Path output) {
        Core.app = new MockApplication();
        Core.audio = new MockAudio();
        Core.files = new MockFiles();
        Core.graphics = new MockGraphics();
        Core.settings = new MockSettings();
        Vars.headless = true;
        Vars.state = new GameState();
        Vars.content = new ContentLoader();
        Vars.customMapDirectory = new Fi(output.resolve("maps").toString());
        Vars.maps = new Maps();
        Vars.mods = new Mods();
        Vars.content.createBaseContent();
        Vars.content.init();
        Groups.init();
        Vars.world = new World();
        Vars.player = Player.create();
        Vars.netClient = new NetClient();
        InputStream compressed = new ByteArrayInputStream(payload);
        NetworkIO.loadWorld(new InflaterInputStream(compressed));
        var core = Vars.player.team().core();
        if (Vars.world.width() <= 0 || Vars.world.height() <= 0 || core == null) {
            throw new IllegalStateException("Stock Java did not load the hosted world and player's core");
        }
        System.out.println("UPSTREAM_HOSTED_WORLD_STOCK_LOAD tiles=" + Vars.world.width() + "x" + Vars.world.height()
            + " core=" + core.block.name + " map=" + Vars.state.map.name());
    }

    @Override
    public void connected(Connection connection) {
        var packet = new ConnectPacket();
        packet.name = "Min3DStry World Fixture";
        packet.locale = "en";
        packet.usid = "world-stream-fixture";
        packet.versionType = Version.type;
        packet.uuid = Base64.getEncoder().encodeToString(new byte[]{77, 51, 68, 83, 84, 82, 89, 1});
        packet.mods = new Seq<>();
        packet.color = -1;
        connection.sendTCP(packet);
    }

    @Override
    public void received(Connection connection, Object object) {
        try {
            if (object instanceof StreamBegin begin && begin.type == Net.packetIdWorldStream) {
                builder = new StreamBuilder(begin, false);
            } else if (object instanceof StreamChunk chunk && builder != null && builder.id == chunk.id) {
                builder.add(chunk.data);
                if (builder.isDone()) {
                    if (builder.received != builder.total) throw new IllegalStateException("World stream size differs");
                    world.complete(builder.build().stream.readAllBytes());
                    builder = null;
                }
            } else if (object instanceof StateSnapshotCallPacket) {
                snapshot.complete(null);
            } else if (object instanceof SendMessageCallPacket2 reply && snapshot.isDone()) {
                reply.handled();
                if (chatText.equals(reply.unformatted)) chat.complete(null);
            }
        } catch (RuntimeException error) {
            world.completeExceptionally(error);
            chat.completeExceptionally(error);
        }
    }

    @Override
    public void disconnected(Connection connection, DcReason reason) {
        var error = new IllegalStateException("Disconnected before receiving the world and its first state: " + reason);
        world.completeExceptionally(error);
        snapshot.completeExceptionally(error);
        chat.completeExceptionally(error);
    }
}
