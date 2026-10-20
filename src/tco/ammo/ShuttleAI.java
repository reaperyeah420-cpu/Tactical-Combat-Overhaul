package tco.ammo;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipwideAIFlags;

/** Flies a supply shuttle where the ammo plugin points it. */
public class ShuttleAI implements ShipAIPlugin {

    private final ShipAPI ship;
    private final ShipwideAIFlags flags = new ShipwideAIFlags();
    private final ShipAIConfig config = new ShipAIConfig();
    private final Vector2f dest = new Vector2f();
    private boolean flying = false;
    private boolean raiseShield = false;

    public ShuttleAI(ShipAPI ship) {
        this.ship = ship;
    }

    public void flyTo(Vector2f target) {
        dest.set(target);
        flying = true;
    }

    public void setRaiseShield(boolean raiseShield) {
        this.raiseShield = raiseShield;
    }

    /** Docked: the plugin moves the shuttle, engines off. */
    public void hold() {
        flying = false;
    }

    @Override
    public void advance(float amount) {
        if (!flying || amount <= 0f || !ship.isAlive()) return;

        if (raiseShield && ship.getShield() != null && ship.getShield().isOff()
                && !ship.getFluxTracker().isOverloadedOrVenting()) {
            ship.getShield().toggleOn();
        }

        Vector2f loc = ship.getLocation();
        Vector2f vel = ship.getVelocity();
        float dx = dest.x - loc.x;
        float dy = dest.y - loc.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;
        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));

        float off = shortestRotation(ship.getFacing(), angle);
        float angVel = ship.getAngularVelocity();
        float turnAcc = Math.max(ship.getTurnAcceleration(), 1f);
        float remaining = off - angVel * Math.abs(angVel) / (2f * turnAcc);
        if (remaining > 0.5f) ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
        else if (remaining < -0.5f) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);

        float speed = vel.length();
        float closing = (vel.x * dx + vel.y * dy) / dist;
        float decel = Math.max(ship.getDeceleration(), 1f);
        float brakeDist = speed * speed / (2f * decel);

        if (closing > 0f && dist <= brakeDist) {
            ship.giveCommand(ShipCommand.DECELERATE, null, 0);
        } else if (Math.abs(off) < 45f) {
            ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        } else if (speed > 60f && closing < speed * 0.3f) {
            ship.giveCommand(ShipCommand.DECELERATE, null, 0);
        }
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
