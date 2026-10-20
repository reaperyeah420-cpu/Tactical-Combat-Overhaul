package tco;

import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * Who is flying a ship. The game wraps a mod's AI in its own wrapper, so ours is told apart by the handle
 * install() returns.
 */
public final class AiUtil {

    private AiUtil() {
    }

    /** Sets the AI; returns the handle getShipAI() will give back. */
    public static Object install(ShipAPI ship, ShipAIPlugin ai) {
        ship.setShipAI(ai);
        return ship.getShipAI();
    }

    public static boolean isOurs(ShipAPI ship, Object handle) {
        return handle != null && ship.getShipAI() == handle;
    }

    public static boolean isGameAi(ShipAPI ship) {
        Object ai = ship.getShipAI();
        if (ai == null) return false;
        String name = ai.getClass().getName();
        return !name.startsWith("tco.") && !name.contains("ShipAIWrapper");
    }
}
