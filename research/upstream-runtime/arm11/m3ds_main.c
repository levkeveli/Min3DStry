// libctru's 32 KiB default stack is too small for the generated save serializer.
u32 __stacksize__ = 256 * 1024;

// Headless probes use linear memory for the debug console framebuffers.
// Leave the rest for the managed heap and its GC metadata.
u32 __ctru_linear_heap_size = 4 * 1024 * 1024;

int main(int argc, char** argv) {
    gfxInitDefault();
    consoleInit(GFX_TOP, NULL);
    char memory[120];
    int memory_length = snprintf(memory, sizeof(memory),
        "UPSTREAM_MEMORY appHeap=%lu linearHeap=%lu stack=%lu\n",
        (unsigned long)envGetHeapSize(), (unsigned long)envGetLinearHeapSize(),
        (unsigned long)__stacksize__);
    if (memory_length > 0 && memory_length < (int)sizeof(memory)) {
        svcOutputDebugString(memory, memory_length);
    }
    int result = m3ds_teavm_main(argc, argv);
    m3ds_flush_log();
    puts("START exits");
    while (aptMainLoop()) {
        hidScanInput();
        if (hidKeysDown() & KEY_START) {
            break;
        }
        gspWaitForVBlank();
    }
    gfxExit();
    return result;
}
