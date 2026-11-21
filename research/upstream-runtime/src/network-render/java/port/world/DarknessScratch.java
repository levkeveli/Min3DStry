package port.world;

import org.teavm.interop.Address;
import org.teavm.interop.Import;
import org.teavm.interop.c.Include;

/** Temporary storage for the two buffers in upstream World.addDarkness. */
public final class DarknessScratch implements AutoCloseable {
    private static boolean nativeMemory;
    private final byte[] heap;
    private Address memory;
    private int current, next;

    public static void useNativeMemory() {
        nativeMemory = true;
    }

    public DarknessScratch(int tiles) {
        int bytes = Math.multiplyExact(tiles, 2);
        next = tiles;
        if (nativeMemory && bytes > 0) {
            heap = null;
            memory = Native.allocate(1, bytes);
            if (memory == null) throw new IllegalStateException("Could not allocate " + bytes + " darkness scratch bytes");
        } else {
            heap = new byte[bytes];
        }
    }

    public byte get(int index) {
        int offset = current + index;
        return heap == null ? memory.add(offset).getByte() : heap[offset];
    }

    public void set(int index, byte value) {
        put(current + index, value);
    }

    public void setNext(int index, byte value) {
        put(next + index, value);
    }

    private void put(int offset, byte value) {
        if (heap == null) memory.add(offset).putByte(value);
        else heap[offset] = value;
    }

    public void advance() {
        int previous = current;
        current = next;
        next = previous;
    }

    @Override
    public void close() {
        if (memory != null) {
            Native.release(memory);
            memory = null;
        }
    }

    @Include(value = "stdlib.h", isSystem = true)
    private static final class Native {
        @Import(name = "calloc")
        static native Address allocate(int count, int size);
        @Import(name = "free")
        static native void release(Address memory);
    }
}
