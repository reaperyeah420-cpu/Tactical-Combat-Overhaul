package tco;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;

import tco.compat.AgcCompat;

/**
 * Who is flying a ship. The game wraps a mod's AI in its own wrapper, so ours is told apart by the handle
 * install() returns. With Advanced Gunnery Control, its ship modes wrap our AI (see AgcCompat).
 */
public final class AiUtil {

    private AiUtil() {
    }

    /** Sets the AI; returns the handle isOurs() checks. */
    public static Object install(ShipAPI ship, ShipAIPlugin ai) {
        ship.setShipAI(ai);
        Object handle = AgcCompat.wrapOurs(ship);
        return handle != null ? handle : ship.getShipAI();
    }

    public static boolean isOurs(ShipAPI ship, Object handle) {
        if (handle instanceof AgcCompat.Wrapped) return AgcCompat.stillWraps(ship, (AgcCompat.Wrapped) handle);
        return handle != null && ship.getShipAI() == handle;
    }

    /** Our AI is under AGC's: the engine runs that one, and so must we when we run it ourselves. */
    public static boolean isWrapped(Object handle) {
        return handle instanceof AgcCompat.Wrapped;
    }

    /** Back to the game's AI. */
    public static void resetDefault(ShipAPI ship) {
        ship.resetDefaultAI();
        AgcCompat.wrapGame(ship);
    }

    /** The game's AI was just put on by hand (createDefaultShipAI). */
    public static void gameAiInstalled(ShipAPI ship) {
        AgcCompat.wrapGame(ship);
    }

    /**
     * The player has the helm of this ship (it is the player ship and the autopilot is off). The game's
     * isUIAutopilotOn() is named the wrong way round: it returns !autopilot (javap on CombatEngine; other mods use
     * it this way too).
     */
    public static boolean playerFlying(CombatEngineAPI engine, ShipAPI ship) {
        return engine != null && ship != null && ship == engine.getPlayerShip() && engine.isUIAutopilotOn();
    }

    /** The player ship is on autopilot. */
    public static boolean autopilotOn(CombatEngineAPI engine) {
        return engine != null && !engine.isUIAutopilotOn();
    }

    public static boolean isGameAi(ShipAPI ship) {
        Object ai = ship.getShipAI();
        if (ai == null) return false;
        Boolean underAgc = AgcCompat.gameUnderAgc(ship);
        if (underAgc != null) return underAgc;
        String name = ai.getClass().getName();
        return !name.startsWith("tco.") && !name.contains("ShipAIWrapper");
    }
}
