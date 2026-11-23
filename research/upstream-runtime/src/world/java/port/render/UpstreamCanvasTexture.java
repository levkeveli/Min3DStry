package port.render;

import arc.graphics.Pixmap;
import arc.graphics.Texture;

/** Retains the original Canvas pixmap for upload by the platform renderer. */
public final class UpstreamCanvasTexture extends Texture {
    private static int nextId;
    public final int id = ++nextId;
    public final int[] pixels;
    public int revision;
    private boolean disposed;

    public UpstreamCanvasTexture(Pixmap pixmap) {
        width = pixmap.width;
        height = pixmap.height;
        pixels = new int[width * height];
        draw(pixmap);
    }

    @Override
    public void draw(Pixmap pixmap) {
        if (width != pixmap.width || height != pixmap.height) {
            throw new IllegalArgumentException("Canvas pixmap size changed");
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) pixels[y * width + x] = pixmap.get(x, y);
        }
        revision++;
    }

    @Override
    public boolean isDisposed() {
        return disposed;
    }

    @Override
    public void dispose() {
        disposed = true;
    }
}
