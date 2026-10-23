package tco.radar;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags;

/**
 * Flies a sensor picket to its station and holds it there: brakes to a hover within ARRIVE of the point and faces
 * where it is looking, instead of overshooting and circling (the supply shuttle AI never stops).
 */
final class PicketAI implements ShipAIPlugin {

    private static final float ARRIVE = 250f;

    private final ShipAPI ship;
    private final ShipwideAIFlags flags = new ShipwideAIFlags();
    private final ShipAIConfig config = new ShipAIConfig();
    private final Vector2f dest = new Vector2f();
    private final Vector2f look = new Vector2f();
    private boolean hasDest;

    PicketAI(ShipAPI ship) {
        this.ship = ship;
    }

    void flyTo(Vector2f to, Vector2f lookAt) {
        dest.set(to);
        look.set(lookAt != null ? lookAt : to);
        hasDest = true;
    }

    Vector2f dest() {
        return hasDest ? dest : null;
    }

    @Override
    public void advance(float amount) {
        if (!hasDest || amount <= 0f || !ship.isAlive()) return;
        Vector2f loc = ship.getLocation();
        Vector2f vel = ship.getVelocity();
        float dx = dest.x - loc.x, dy = dest.y - loc.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float speed = vel.length();
        if (dist <= ARRIVE) {
            // On station: stop and watch
            if (speed > 5f) ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            turnTo((float) Math.toDegrees(Math.atan2(look.y - loc.y, look.x - loc.x)));
            return;
        }
        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        float off = turnTo(angle);
        float closing = (vel.x * dx + vel.y * dy) / dist;
        float decel = Math.max(ship.getDeceleration(), 1f);
        float brakeDist = speed * speed / (2f * decel) + ARRIVE * 0.5f;
        if (closing > 0f && dist <= brakeDist) {
            ship.giveCommand(ShipCommand.DECELERATE, null, 0);
        } else if (Math.abs(off) < 30f) {
            ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        } else if (speed > 40f) {
            // Sideways drift: shed it before burning
            ship.giveCommand(ShipCommand.DECELERATE, null, 0);
        }
    }

    /** Turns towards an angle; returns how far off it still is. */
    private float turnTo(float angle) {
        float off = shortestRotation(ship.getFacing(), angle);
        float angVel = ship.getAngularVelocity();
        float turnAcc = Math.max(ship.getTurnAcceleration(), 1f);
        float remaining = off - angVel * Math.abs(angVel) / (2f * turnAcc);
        if (remaining > 0.5f) ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
        else if (remaining < -0.5f) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
        return off;
    }

    private static float shortestRotation(float from, float to) {
        float diff = (to - from) % 360f;
        if (diff > 180f) diff -= 360f;
        else if (diff <= -180f) diff += 360f;
        return diff;
    }

    @Override
    public void setDoNotFireDelay(float amount) {
    }

    @Override
    public void forceCircumstanceEvaluation() {
    }

    @Override
    public boolean needsRefit() {
        return false;
    }

    @Override
    public ShipwideAIFlags getAIFlags() {
        return flags;
    }

    @Override
    public void cancelCurrentManeuver() {
    }

    @Override
    public ShipAIConfig getConfig() {
        return config;
    }
}
