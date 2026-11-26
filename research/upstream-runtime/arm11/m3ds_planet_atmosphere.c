#include "m3ds_planet_atmosphere.h"

#include <math.h>

/* Pinned Mindustry v160.8 core/assets/shaders/atmosphere.frag. */
enum { scatter_samples = 3 };
static const float peak = 0.1f, flare = 0.0025f, intensity = 14.3f;
static const float four_pi = 3.14159265359f * 4.0f;

typedef struct {
    float near, far;
} Intersection;

static float dot(M3dsAtmosphereVector a, M3dsAtmosphereVector b) {
    return a.x * b.x + a.y * b.y + a.z * b.z;
}

static M3dsAtmosphereVector offset(M3dsAtmosphereVector point, M3dsAtmosphereVector direction, float distance) {
    return (M3dsAtmosphereVector){
        point.x + direction.x * distance,
        point.y + direction.y * distance,
        point.z + direction.z * distance
    };
}

static Intersection intersect(M3dsAtmosphereVector point, M3dsAtmosphereVector direction, float radius) {
    float b = dot(point, direction);
    float c = dot(point, point) - radius * radius;
    float discriminant = b * b - c;
    if (discriminant < 0.0f) return (Intersection){10000.0f, -10000.0f};
    float distance = sqrtf(discriminant);
    return (Intersection){-b - distance, -b + distance};
}

static float density(M3dsAtmosphereVector point, float inner_radius, float inverse_thickness) {
    return expf(-(sqrtf(dot(point, point)) - inner_radius) * (4.0f * inverse_thickness));
}

static float optical_depth(M3dsAtmosphereVector start, M3dsAtmosphereVector end,
    float inner_radius, float inverse_thickness) {
    const float inverse_samples = 1.0f / scatter_samples;
    M3dsAtmosphereVector step = {
        (end.x - start.x) * inverse_samples,
        (end.y - start.y) * inverse_samples,
        (end.z - start.z) * inverse_samples
    };
    M3dsAtmosphereVector point = offset(start, step, 0.5f);
    float sum = 0.0f;
    for (int i = 0; i < scatter_samples; i++) {
        sum += density(point, inner_radius, inverse_thickness);
        point = offset(point, step, 1.0f);
    }
    return sum * sqrtf(dot(step, step)) * inverse_thickness;
}

static float mie_phase(float cosine, float cosine_squared) {
    const float anisotropy = -0.85f;
    const float squared = anisotropy * anisotropy;
    float numerator = (1.0f - squared) * (1.0f + cosine_squared);
    float denominator = 1.0f + squared - 2.0f * anisotropy * cosine;
    denominator *= sqrtf(denominator);
    denominator *= 2.0f + squared;
    return 1.5f * numerator / denominator;
}

bool m3ds_atmosphere_scatter(const M3dsAtmosphere *atmosphere, M3dsAtmosphereVector eye,
    M3dsAtmosphereVector ray, float result[3]) {
    result[0] = result[1] = result[2] = 0.0f;
    Intersection outer = intersect(eye, ray, atmosphere->outer_radius);
    Intersection inner = intersect(eye, ray, atmosphere->inner_radius);
    // Only forward camera rays that cover the shell contribute pixels.
    float end = fminf(outer.far, inner.near);
    if (outer.near < 0.0f || outer.near >= end) return false;

    float inverse_thickness = 1.0f / (atmosphere->outer_radius - atmosphere->inner_radius);
    float length = (end - outer.near) / scatter_samples;
    M3dsAtmosphereVector start = offset(eye, ray, outer.near);
    M3dsAtmosphereVector point = offset(start, ray, length * 0.5f);
    for (int i = 0; i < scatter_samples; i++) {
        Intersection toward_light = intersect(point, atmosphere->light, atmosphere->outer_radius);
        M3dsAtmosphereVector exit = offset(point, atmosphere->light, toward_light.far);
        float optical = (optical_depth(start, point, atmosphere->inner_radius, inverse_thickness)
            + optical_depth(point, exit, atmosphere->inner_radius, inverse_thickness)) * four_pi;
        float local_density = density(point, atmosphere->inner_radius, inverse_thickness);
        for (int channel = 0; channel < 3; channel++) {
            result[channel] += local_density * expf(-optical * (peak * atmosphere->color[channel] + flare));
        }
        point = offset(point, ray, length);
    }

    float cosine = -dot(ray, atmosphere->light);
    float cosine_squared = cosine * cosine;
    float rayleigh = 0.75f * (1.0f + cosine_squared);
    float mie = mie_phase(cosine, cosine_squared);
    for (int channel = 0; channel < 3; channel++) {
        result[channel] *= length * inverse_thickness
            * (peak * atmosphere->color[channel] * rayleigh + flare * mie) * intensity;
    }
    return true;
}

static uint8_t channel_to_unorm(float color) {
    float clamped = fminf(fmaxf(color, 0.0f), 1.0f);
    return (uint8_t)(clamped * 255.0f + 0.5f);
}

void m3ds_atmosphere_raster(const M3dsAtmosphere *atmosphere, M3dsAtmosphereVector eye,
    M3dsAtmosphereVector first_ray, M3dsAtmosphereVector pixel_dx, M3dsAtmosphereVector pixel_dy,
    unsigned width, unsigned height, uint8_t *rgb) {
    for (unsigned y = 0; y < height; y++) {
        M3dsAtmosphereVector left = offset(first_ray, pixel_dy, (float)y);
        for (unsigned x = 0; x < width; x++) {
            M3dsAtmosphereVector ray = offset(left, pixel_dx, (float)x);
            const float inverse_length = 1.0f / sqrtf(dot(ray, ray));
            ray = (M3dsAtmosphereVector){ray.x * inverse_length, ray.y * inverse_length, ray.z * inverse_length};
            float color[3];
            m3ds_atmosphere_scatter(atmosphere, eye, ray, color);
            for (int channel = 0; channel < 3; channel++) *rgb++ = channel_to_unorm(color[channel]);
        }
    }
}

typedef struct {
    float x, y, z, w;
} AtmosphereClipVertex;

static AtmosphereClipVertex clip_vertex(M3dsAtmosphereVector point, const float matrix[16]) {
    return (AtmosphereClipVertex){
        matrix[0] * point.x + matrix[4] * point.y + matrix[8] * point.z + matrix[12],
        matrix[1] * point.x + matrix[5] * point.y + matrix[9] * point.z + matrix[13],
        matrix[2] * point.x + matrix[6] * point.y + matrix[10] * point.z + matrix[14],
        matrix[3] * point.x + matrix[7] * point.y + matrix[11] * point.z + matrix[15]
    };
}

static AtmosphereClipVertex intersect_near(AtmosphereClipVertex a, AtmosphereClipVertex b, float from, float to) {
    float t = from / (from - to);
    float w = a.w + (b.w - a.w) * t;
    return (AtmosphereClipVertex){a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, -w, w};
}

unsigned m3ds_atmosphere_project_triangle(const M3dsAtmosphereVector triangle[3],
    const float matrix[16], float u_scale, float v_scale, M3dsAtmosphereVertex out[6]) {
    AtmosphereClipVertex clipped[4];
    unsigned count = 0;
    AtmosphereClipVertex previous = clip_vertex(triangle[2], matrix);
    float from = previous.z + previous.w;
    for (int i = 0; i < 3; i++) {
        AtmosphereClipVertex next = clip_vertex(triangle[i], matrix);
        float to = next.z + next.w;
        if ((from < 0 && to > 0) || (from > 0 && to < 0)) {
            clipped[count++] = intersect_near(previous, next, from, to);
        }
        if (to >= 0) clipped[count++] = next;
        previous = next;
        from = to;
    }
    if (count < 3) return 0;

    M3dsAtmosphereVertex projected[4];
    for (unsigned i = 0; i < count; i++) {
        AtmosphereClipVertex c = clipped[i];
        if (c.w <= 0.0f) return 0;
        float ndc_x = c.x / c.w;
        float ndc_y = c.y / c.w;
        // The top target is rotated. Arc's Z range [-1,1] becomes PICA's [-1,0].
        projected[i] = (M3dsAtmosphereVertex){ndc_y, -ndc_x, -0.5f * (c.z / c.w + 1.0f),
            0.5f * (ndc_x + 1.0f) * u_scale, 1.0f - 0.5f * (1.0f - ndc_y) * v_scale};
    }
    out[0] = projected[0];
    out[1] = projected[1];
    out[2] = projected[2];
    if (count == 3) return 3;
    out[3] = projected[0];
    out[4] = projected[2];
    out[5] = projected[3];
    return 6;
}
