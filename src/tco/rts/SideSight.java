package tco.rts;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import tco.TcoSettings;
import tco.ai.AiAwareness;
import tco.radar.CommandNetwork;
import tco.radar.RadarCombatPlugin;

/**
 * What each side can see, for the engine's fog (the AI's knowledge) when the fog patch replaces its tile grid:
 * exact sight circles, the side's radar contacts (AI sides only, as before), and whatever the game or other mods
 * revealed this frame. Rebuilt once per frame, on first use.
 */
public final class SideSight {

    private static final float RADAR_REVEAL = 250f;

    private static CombatEngineAPI engine;
    private static float builtAt = -1f;
    private static final List<List<float[]>> eyes = new ArrayList<List<float[]>>();
    private static final List<List<float[]>> reveals = new ArrayList<List<float[]>>();

    static {
        for (int side = 0; side < 2; side++) {
            eyes.add(new ArrayList<float[]>());
            reveals.add(new ArrayList<float[]>());
        }
    }

    private SideSight() {
    }

    /** A reveal made through the engine's fog (kept until that side's fog is next rebuilt). */
    public static void revealed(int side, float x, float y, float radius) {
        if (side < 0 || side > 1) return;
        reveals.get(side).add(new float[] { x, y, radius });
    }

    /** The engine is rebuilding this side's fog, which drops earlier reveals. */
    public static void rebuilding(int side) {
        if (side < 0 || side > 1) return;
        reveals.get(side).clear();
    }

    public static boolean sees(int side, float x, float y) {
        if (side < 0 || side > 1) return true;
        build();
        if (inside(eyes.get(side), x, y)) return true;
        return inside(reveals.get(side), x, y);
    }

    private static boolean inside(List<float[]> circles, float x, float y) {
        for (int i = 0; i < circles.size(); i++) {
            float[] c = circles.get(i);
            float dx = x - c[0], dy = y - c[1];
            if (dx * dx + dy * dy <= c[2] * c[2]) return true;
        }
        return false;
    }

    private static void build() {
        CombatEngineAPI now = Global.getCombatEngine();
        if (now == null) return;
        float time = now.getTotalElapsedTime(false);
        if (now == engine && time == builtAt) return;
        if (now != engine) {
            for (List<float[]> list : reveals) list.clear();
        }
        engine = now;
        builtAt = time;
        int playerSide = engine.getPlayerShip() != null ? engine.getPlayerShip().getOwner() : 0;
        RadarCombatPlugin radar = TcoSettings.radarFeedsAi ? RadarCombatPlugin.get(engine) : null;
        for (int side = 0; side < 2; side++) {
            List<float[]> list = eyes.get(side);
            list.clear();
            for (ShipAPI ship : engine.getShips()) {
                if (FogOfWar.reveals(ship, side)) {
                    list.add(new float[] { ship.getLocation().x, ship.getLocation().y, FogOfWar.sightRange(ship) });
                }
            }
            // Targets a command ship sent carriers' fighters at, so they engage them
            for (ShipAPI target : CommandNetwork.directedTargets(engine, side)) {
                if (target.isAlive()) list.add(new float[] { target.getLocation().x, target.getLocation().y, RADAR_REVEAL });
            }
            // The player's side stays sight-only otherwise: its fog also drives the HUD and reticles
            if (radar == null || side == playerSide) continue;
            Set<ShipAPI> contacts = radar.contacts(side);
            if (contacts == null) continue;
            for (ShipAPI target : contacts) {
                // Only contacts relayed to the side (AiAwareness)
                if (target.isAlive() && AiAwareness.knows(engine, side, target)) {
                    list.add(new float[] { target.getLocation().x, target.getLocation().y, RADAR_REVEAL });
                }
            }
        }
    }
}
