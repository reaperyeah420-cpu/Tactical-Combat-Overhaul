package tco.rts;

import org.lwjgl.util.vector.Vector2f;

final class RtsMath {

    private RtsMath() {
    }

    static float distance(Vector2f a, Vector2f b) {
        float dx = b.x - a.x;
        float dy = b.y - a.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    static float angle(Vector2f from, Vector2f to) {
        return (float) Math.toDegrees(Math.atan2(to.y - from.y, to.x - from.x));
    }

    static float shortestRotation(float from, float to) {
        float diff = (to - from) % 360f;
        if (diff > 180f) diff -= 360f;
        else if (diff <= -180f) diff += 360f;
        return diff;
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
