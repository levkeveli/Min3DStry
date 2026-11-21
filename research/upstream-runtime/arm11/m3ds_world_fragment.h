#ifndef M3DS_WORLD_FRAGMENT_H
#define M3DS_WORLD_FRAGMENT_H

/*
 * Citro2D draws a sprite from a pivot: the sprite's top-left corner is
 * (pos - center) with positive screen-space extents. A negative size only
 * flips that fragment's UVs. Position a mirrored fragment by measuring from
 * its exclusive end to the entire region's exclusive end.
 */
static inline float m3ds_world_fragment_center(
    float whole_center, int start, int end, int region_start, int region_end, float step) {
    const float offset = step < 0 ? (region_end - end) * -step : (start - region_start) * step;
    return whole_center - offset;
}

#endif
