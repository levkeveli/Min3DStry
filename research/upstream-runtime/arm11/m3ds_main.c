// libctru's 32 KiB default stack is too small for the generated save serializer.
u32 __stacksize__ = 256 * 1024;

int main(int argc, char** argv) {
    gfxInitDefault();
    consoleInit(GFX_TOP, NULL);
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
