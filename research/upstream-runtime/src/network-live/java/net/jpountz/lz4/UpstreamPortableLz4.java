package net.jpountz.lz4;

/** Selects the bundled Java implementation without LZ4Factory's native or reflective lookup. */
public final class UpstreamPortableLz4 {
    private UpstreamPortableLz4() {}

    public static final LZ4Compressor compressor = LZ4JavaSafeCompressor.INSTANCE;
    public static final LZ4SafeDecompressor decompressor = LZ4JavaSafeSafeDecompressor.INSTANCE;
}
