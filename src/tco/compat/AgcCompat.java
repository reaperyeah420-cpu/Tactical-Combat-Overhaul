package tco.compat;

import java.lang.ref.WeakReference;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * Advanced Gunnery Control's ship modes run in an AI that wraps the ship's AI underneath. Whenever TCO puts its own
 * AI on a ship, AGC is asked to wrap that instead, and when TCO hands the ship back, to wrap the game's AI again
 * straight away. Only AgcBridge touches AGC's classes, and only once AGC is known to be enabled.
 */
public final class AgcCompat {

    public static final String MOD_ID = "advanced_gunnery_control_dbeaa06e";
    // AGC keeps a weak reference to its wrapper here
    private static final String AGC_AI_KEY = "AGC_ShipAI";
    // The AGC wrapper that has one of TCO's AIs underneath
    private static final String UNDER_KEY = "tco_under_agc";

    private static Boolean enabled;

    /** The handle AiUtil.install gives for a TCO AI that AGC wrapped. */
    public static final class Wrapped {
        final Object agc;

        Wrapped(Object agc) {
            this.agc = agc;
        }
    }

    private AgcCompat() {
    }

    private static boolean enabled() {
        if (enabled == null) {
            enabled = Global.getSettings().getModManager().isModEnabled(MOD_ID);
            if (enabled) Global.getLogger(AgcCompat.class).info("TCO: Advanced Gunnery Control found, ship modes wrap TCO's AI");
        }
        return enabled;
    }

    /** AGC's live wrapper on this ship, or null. */
    static Object wrapper(ShipAPI ship) {
        Object ref = ship.getCustomData().get(AGC_AI_KEY);
        return ref instanceof WeakReference ? ((WeakReference<?>) ref).get() : null;
    }

    private static boolean isBasic(Object ai) {
        return ai != null && ai.getClass().getName().endsWith(".BasicShipAI");
    }

    /** TCO just put its own AI on the ship: AGC wraps it with the ship's modes. Returns the handle, or null. */
    public static Wrapped wrapOurs(ShipAPI ship) {
        if (!enabled()) return null;
        Object agc = wrap(ship);
        if (agc == null) return null;
        ship.setCustomData(UNDER_KEY, agc);
        return new Wrapped(agc);
    }

    /** The game's AI is back on: AGC wraps it now rather than on its own timer. */
    public static void wrapGame(ShipAPI ship) {
        if (!enabled() || !isBasic(ship.getShipAI())) return;
        wrap(ship);
    }

    private static Object wrap(ShipAPI ship) {
        try {
            return AgcBridge.wrap(ship) ? wrapper(ship) : null;
        } catch (Throwable t) {
            // A changed AGC: leave both mods alone
            Global.getLogger(AgcCompat.class).warn("TCO: could not hand Advanced Gunnery Control the ship AI, turning that off", t);
            enabled = false;
            return null;
        }
    }

    /** The AGC wrapper over TCO's AI is still the ship's AI. */
    public static boolean stillWraps(ShipAPI ship, Wrapped handle) {
        Object ai = ship.getShipAI();
        return ai != null && !isBasic(ai) && handle.agc == wrapper(ship);
    }

    /** With AGC's wrapper on: true if the game's AI is under it, false if TCO's; null without one. */
    public static Boolean gameUnderAgc(ShipAPI ship) {
        if (!enabled()) return null;
        Object agc = wrapper(ship);
        if (agc == null || isBasic(ship.getShipAI())) return null;
        return agc != ship.getCustomData().get(UNDER_KEY);
    }
}
