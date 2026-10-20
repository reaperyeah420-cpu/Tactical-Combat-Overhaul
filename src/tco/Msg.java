package tco;

import java.awt.Color;
import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/** TCO's combat messages: coloured by side and throttled so the same message doesn't repeat. */
public final class Msg {

    public static final int PLAYER = 0;
    public static final int ENEMY = 1;
    public static final int ALLY = 2;
    public static final int NEUTRAL = -1;

    private static final Color GREEN = new Color(120, 230, 120);
    private static final Color RED = new Color(255, 105, 90);
    private static final Color YELLOW = new Color(255, 210, 90);
    private static final Color PLAIN = new Color(210, 215, 220);
    private static final float REPEAT_SECONDS = 8f;

    private static final Map<String, Float> lastShown = new HashMap<String, Float>();

    private Msg() {
    }

    public static int sideOf(ShipAPI ship) {
        if (ship == null) return NEUTRAL;
        if (ship.getOwner() == 1) return ENEMY;
        if (ship.getOwner() == 0) return ship.isAlly() ? ALLY : PLAYER;
        return NEUTRAL;
    }

    public static void about(CombatEngineAPI engine, ShipAPI ship, String text) {
        send(engine, sideOf(ship), text, text, REPEAT_SECONDS);
    }

    public static void send(CombatEngineAPI engine, int side, String text) {
        send(engine, side, text, text, REPEAT_SECONDS);
    }

    /** Messages sharing a key show at most once per that many seconds. */
    public static void send(CombatEngineAPI engine, int side, String text, String key, float seconds) {
        if (engine == null || engine.getCombatUI() == null) return;
        float now = engine.getTotalElapsedTime(false);
        Float last = lastShown.get(key);
        // A new battle restarts the clock
        if (last != null && now >= last && now - last < seconds) return;
        if (lastShown.size() > 400) lastShown.clear();
        lastShown.put(key, now);
        Color color = side == PLAYER ? GREEN : side == ENEMY ? RED : side == ALLY ? YELLOW : PLAIN;
        engine.getCombatUI().addMessage(0, color, text);
    }
}
