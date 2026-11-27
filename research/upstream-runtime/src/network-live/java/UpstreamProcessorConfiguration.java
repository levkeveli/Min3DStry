import java.nio.charset.StandardCharsets;
import mindustry.gen.Building;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.logic.LogicBlock.LogicBuild;

/** The pinned LogicBlock editor's configuration operations without desktop Scene2D. */
final class UpstreamProcessorConfiguration {
    // Pinned LogicBlock's private raw and compressed read limits.
    static final int MAX_SOURCE_BYTES = 100 * 1024;
    static final int MAX_COMPRESSED_BYTES = 16_000;

    static final int APPLET_CHARACTERS = 1000;

    private UpstreamProcessorConfiguration() {}

    static byte[] encode(LogicBuild processor, String program) {
        byte[] source = program.getBytes(StandardCharsets.UTF_8);
        if (source.length > MAX_SOURCE_BYTES) return null;
        byte[] config = LogicBlock.compress(source, processor.relativeConnections());
        return config.length <= MAX_COMPRESSED_BYTES ? config : null;
    }

    static boolean toggleLink(LogicBuild processor, Building other) {
        if (other == processor || !processor.validLink(other)) return false;
        processor.configure(other.pos());
        return true;
    }
}
