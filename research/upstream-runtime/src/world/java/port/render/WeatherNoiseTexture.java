package port.render;

import arc.graphics.Texture;

/** Holds the stock texture dimensions used by Arc's weather UV calculations. */
public final class WeatherNoiseTexture extends Texture {
    public final int index;

    private WeatherNoiseTexture(int index, int size) {
        this.index = index;
        width = height = size;
    }

    public static WeatherNoiseTexture load(String name) {
        return switch (name) {
            case "noiseAlpha" -> new WeatherNoiseTexture(0, 128);
            case "fog" -> new WeatherNoiseTexture(1, 256);
            default -> throw new IllegalArgumentException("Unrecognized stock weather texture " + name);
        };
    }
}
