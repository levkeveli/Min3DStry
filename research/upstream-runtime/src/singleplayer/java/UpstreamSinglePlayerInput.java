import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

/** Native 3DS HID and touch bindings for offline single-player mode. */
@Include(value = "m3ds_input.h", isSystem = true)
final class UpstreamSinglePlayerInput {
    static final int DPAD_ACTIVE = 1 << 16;
    static final int X_PRESSED = 1 << 20;
    static final int WAVE_PRESSED = 1 << 21;
    static final int SAVE_PRESSED = 1 << 22;
    static final int L_HELD = 1 << 23;
    static final int X_HELD = 1 << 25;

    private UpstreamSinglePlayerInput() {
    }

    @Import(name = "m3ds_input_poll")
    static native int poll();

    @Import(name = "m3ds_input_touch")
    static native int touch();
}
