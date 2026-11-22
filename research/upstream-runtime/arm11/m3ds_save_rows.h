#ifndef M3DS_SAVE_ROWS_H
#define M3DS_SAVE_ROWS_H

#ifdef __cplusplus
extern "C" {
#endif

int m3ds_save_rows_begin(
    int width, int height, int tiles, int buildings, int verifyFixture);
void m3ds_save_rows_tile(int index, int floor, int overlay, int block,
    int x, int y, int flags, int buildingIndex);
void m3ds_save_rows_building(int index, int tileIndex, int x, int y,
    int block, int team, int rotation, int enabled, int modules,
    int healthBits, int itemCount, int powerLinks, int powerStatusBits,
    int liquidCount, int liquidId, int liquidAmountBits, int extraSize,
    int stateLow, int stateHigh);
int m3ds_save_rows_end(void);
int m3ds_save_rows_copy_path(void *destination, int capacity);

#ifdef __cplusplus
}
#endif

#endif /* M3DS_SAVE_ROWS_H */
