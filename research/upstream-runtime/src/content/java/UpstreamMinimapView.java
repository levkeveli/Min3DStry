/** Stock MinimapFragment fullscreen view state: open flag, pan and zoom clamps. */
public final class UpstreamMinimapView {
    public static final float screenWidth = 400f;
    public static final float screenHeight = 240f;
    public static final float minZoom = 1f;
    public static final float maxZoom = 4f;
    /** Keep at least this much of the scaled map inside the screen when panning. */
    private static final float minVisible = 80f;

    private boolean opened;
    private float panX, panY;
    private float zoom = minZoom;

    public boolean open() {
        return opened;
    }

    public void openView() {
        opened = true;
    }

    public void close() {
        opened = false;
    }

    public void toggle() {
        opened = !opened;
    }

    public void reset() {
        opened = false;
        zoom = minZoom;
        panX = 0f;
        panY = 0f;
    }

    public float zoom() {
        return zoom;
    }

    public float panX() {
        return panX;
    }

    public float panY() {
        return panY;
    }

    /** Height-fitted map size in screen pixels. */
    public float size() {
        return screenHeight * zoom;
    }

    public float x() {
        return (screenWidth - size()) / 2f + panX;
    }

    public float y() {
        return (screenHeight - size()) / 2f + panY;
    }

    public float panLimit() {
        return Math.max(0f, (size() - minVisible) / 2f);
    }

    public void zoomBy(float amount) {
        zoom = Math.max(minZoom, Math.min(maxZoom, zoom + amount));
        clampPan();
    }

    /** Maps a world X coordinate into the drawn map rectangle. */
    public static float screenX(float worldX, float worldWidth, float mapX, float mapSize) {
        return mapX + (worldX / worldWidth) * mapSize;
    }

    /** Maps a world Y coordinate into the drawn map rectangle (world Y is up). */
    public static float screenY(float worldY, float worldHeight, float mapY, float mapSize) {
        return mapY + (1f - worldY / worldHeight) * mapSize;
    }

    /** Moves the view by screen pixels; callers pass input-axis scaled deltas. */
    public void move(float dx, float dy) {
        panX += dx;
        panY += dy;
        clampPan();
    }

    private void clampPan() {
        float limit = panLimit();
        panX = Math.max(-limit, Math.min(limit, panX));
        panY = Math.max(-limit, Math.min(limit, panY));
    }
}
