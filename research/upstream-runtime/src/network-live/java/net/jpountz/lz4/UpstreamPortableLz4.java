package net.jpountz.lz4;

/** Selects the bundled Java implementation without LZ4Factory's native or reflective lookup. */
public final class UpstreamPortableLz4 {
    private UpstreamPortableLz4() {}

    static {
        // TeaVM 0.16 does not populate os.arch. The bundled compressor
        // checks it even on the bytewise implementation.
        if (System.getProperty("os.arch") == null) System.setProperty("os.arch", "arm");
    }

    public static final LZ4Compressor compressor = LZ4JavaSafeCompressor.INSTANCE;
    public static final LZ4SafeDecompressor decompressor = LZ4JavaSafeSafeDecompressor.INSTANCE;
}
