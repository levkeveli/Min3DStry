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
