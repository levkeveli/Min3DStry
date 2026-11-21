#ifndef M3DS_WORLD_FRAGMENT_H
#define M3DS_WORLD_FRAGMENT_H

/*
 * Citro2D draws a sprite from a pivot: the sprite's top-left corner is
 * (pos - center). When a source region is split into atlas-backed fragments,
 * every fragment keeps the whole region's pivot. A fragment that starts at
 * `start` inside `[region_start, region_start + size)` therefore subtracts its
 * source offset times the destination texel step from the shared center.
 *
 * The same expression covers positive and mirrored (negative) steps. The
 * earlier mirrored branch used the fragment's distance from the region's far
 * edge, which shifted any mirrored draw wider than one atlas tile.
 */
static inline float m3ds_world_fragment_center(
    float whole_center, int start, int region_start, float step) {
    return whole_center - (float)(start - region_start) * step;
}

#endif
