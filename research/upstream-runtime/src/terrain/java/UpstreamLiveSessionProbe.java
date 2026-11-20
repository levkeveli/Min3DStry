import java.io.IOException;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

public final class UpstreamLiveSessionProbe {
    public static void main(String[] args) throws IOException {
        var session = new UpstreamLiveSession(args, new UpstreamTerrainRenderProbe(false));
        session.draw();
        System.out.println("A sends copper. B collects it. X pauses. START exits.");
        System.out.println("UPSTREAM_LIVE_READY");
        long lastMs = NativeLoop.now();
        int input;
        while ((input = NativeLoop.input()) >= 0) {
            long now = NativeLoop.now();
            session.advance((int)Math.min(250L, Math.max(0L, now - lastMs)), input);
            lastMs = now;
            session.draw();
            NativeLoop.waitForVBlank();
        }
        System.out.println("UPSTREAM_LIVE_EXIT");
    }

    @Include(value = "m3ds_terrain_renderer.h", isSystem = true)
    private static final class NativeLoop {
        @Import(name = "m3ds_terrain_input")
        static native int input();

        @Import(name = "m3ds_terrain_clock_ms")
        static native long now();

        @Import(name = "m3ds_terrain_wait")
        static native void waitForVBlank();
    }
}
