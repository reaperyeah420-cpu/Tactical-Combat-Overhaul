package tco.radar;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.TcoSettings;
import tco.ai.AiAwareness;
import tco.hullmods.CombatInformationCenter;

/**
 * Fleet warnings for the player while a CIC or Auxiliary CIC of theirs is in the battle (fleetWarnings): enemy radar
 * locks on their ships, guided missiles homing on them, new contacts reported to the fleet (with a compass bearing from
 * the command ship), and enemy jamming. Grouped per check and spaced out so they don't flood the message list.
 */
public class FleetWarnings extends BaseEveryFrameCombatPlugin {

    private static final int SIDE = 0;
    private static final float INTERVAL = 0.5f;
    private static final float CONTACT_WINDOW = 2f;
    private static final float REWARN_SECONDS = 20f;
    private static final Color LOCK = new Color(255, 140, 80);
    private static final Color MISSILE = new Color(255, 90, 70);
    private static final Color CONTACT = new Color(255, 210, 120);
    private static final Color JAM = new Color(255, 120, 200);

    private CombatEngineAPI engine;
    private float timer;
    // Lock pairs (radar ship -> our ship) and when they were last announced
    private final Map<String, Float> lockWarned = new HashMap<String, Float>();
    private final Set<MissileAPI> missilesSeen = new HashSet<MissileAPI>();
    private final Set<ShipAPI> contactsSeen = new HashSet<ShipAPI>();
    private final List<ShipAPI> newContacts = new ArrayList<ShipAPI>();
    private float contactWindowEnd = -1f;
    private final Set<ShipAPI> jammedSeen = new HashSet<ShipAPI>();
    private final Map<ShipAPI, Float> jammerWarned = new HashMap<ShipAPI, Float>();

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused() || engine.isCombatOver() || !TcoSettings.fleetWarnings || !TcoSettings.radarEnabled) return;
        timer -= amount;
        if (timer > 0f) return;
        timer = INTERVAL;
        if (engine.getCombatUI() == null) return;
        ShipAPI command = commandShip();
        if (command == null) return;
        RadarCombatPlugin radar = RadarCombatPlugin.get(engine);
        if (radar == null) return;
        float now = engine.getTotalElapsedTime(false);
        if (TcoSettings.fleetWarningLocks) locks(radar, now);
        missiles(radar);
        contacts(command, now);
        jamming(radar, now);
    }

    /** The player side's best live command ship, or null. */
    private ShipAPI commandShip() {
        ShipAPI best = null;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != SIDE || ship.isAlly() || !ship.isAlive() || ship.isHulk() || ship.isRetreating()) continue;
            int level = CombatInformationCenter.level(ship);
            if (level > 0 && (best == null || level > CombatInformationCenter.level(best))) best = ship;
        }
        return best;
    }

    private void message(Color color, String text) {
        engine.getCombatUI().addMessage(0, color, text);
    }

    private void locks(RadarCombatPlugin radar, float now) {
        List<String> fresh = new ArrayList<String>();
        ShipAPI lastRadar = null, lastTarget = null;
        for (ShipAPI enemy : engine.getShips()) {
            if (enemy.getOwner() != 1 - SIDE || !radar.isEmitting(enemy)) continue;
            for (ShipAPI ours : radar.heldBy(enemy)) {
                if (ours.getOwner() != SIDE || ours.isFighter() || ours.isShuttlePod()) continue;
                String key = System.identityHashCode(enemy) + ":" + System.identityHashCode(ours);
                Float last = lockWarned.get(key);
                if (last != null && now - last < REWARN_SECONDS) {
                    lockWarned.put(key, now);
                    continue;
                }
                lockWarned.put(key, now);
                fresh.add(key);
                lastRadar = enemy;
                lastTarget = ours;
            }
        }
        if (fresh.size() == 1) {
            message(LOCK, "RADAR LOCK: " + lastRadar.getHullSpec().getHullName() + " has " + lastTarget.getName());
        } else if (fresh.size() > 1) {
            message(LOCK, "RADAR LOCK: " + fresh.size() + " new enemy locks on your ships");
        }
        lockWarned.values().removeIf(t -> now - t > REWARN_SECONDS * 3f);
    }

    private void missiles(RadarCombatPlugin radar) {
        Map<MissileAPI, ShipAPI> homing = radar.homingOn(SIDE);
        Map<ShipAPI, Integer> perTarget = new LinkedHashMap<ShipAPI, Integer>();
        for (Map.Entry<MissileAPI, ShipAPI> e : homing.entrySet()) {
            if (!missilesSeen.add(e.getKey())) continue;
            if (e.getValue().isFighter()) continue;
            Integer n = perTarget.get(e.getValue());
            perTarget.put(e.getValue(), n == null ? 1 : n + 1);
        }
        for (Map.Entry<ShipAPI, Integer> e : perTarget.entrySet()) {
            int n = e.getValue();
            message(MISSILE, "MISSILE" + (n > 1 ? "S: " + n + " guided missiles" : ": a guided missile") + " inbound on "
                    + e.getKey().getName());
        }
        missilesSeen.removeIf(m -> !engine.isEntityInPlay(m));
    }

    /** New contacts reported to the fleet, gathered over CONTACT_WINDOW seconds into one line. */
    private void contacts(ShipAPI command, float now) {
        AiAwareness awareness = AiAwareness.get(engine);
        if (awareness == null) return;
        for (ShipAPI enemy : awareness.memory(SIDE).keySet()) {
            if (enemy.isFighter() || !contactsSeen.add(enemy)) continue;
            newContacts.add(enemy);
            if (contactWindowEnd < 0f) contactWindowEnd = now + CONTACT_WINDOW;
        }
        if (contactWindowEnd < 0f || now < contactWindowEnd) return;
        contactWindowEnd = -1f;
        List<ShipAPI> list = new ArrayList<ShipAPI>(newContacts);
        newContacts.clear();
        list.removeIf(s -> !s.isAlive() || !engine.isEntityInPlay(s));
        if (list.isEmpty()) return;
        int[] count = new int[4];
        Vector2f c = new Vector2f();
        for (ShipAPI s : list) {
            HullSize size = s.getHullSize();
            count[size == HullSize.CAPITAL_SHIP ? 3 : size == HullSize.CRUISER ? 2 : size == HullSize.DESTROYER ? 1 : 0]++;
            Vector2f.add(c, s.getLocation(), c);
        }
        c.scale(1f / list.size());
        String[] names = { "frigate", "destroyer", "cruiser", "capital" };
        StringBuilder b = new StringBuilder("NEW CONTACTS: ");
        boolean first = true;
        for (int i = 3; i >= 0; i--) {
            if (count[i] == 0) continue;
            if (!first) b.append(", ");
            b.append(count[i]).append(' ').append(names[i]).append(count[i] > 1 ? "s" : "");
            first = false;
        }
        int bearing = Math.round((450f - (float) Math.toDegrees(Math.atan2(c.y - command.getLocation().y,
                c.x - command.getLocation().x))) % 360f);
        b.append(", bearing ").append(String.format("%03d", bearing)).append(" from ").append(command.getName());
        message(CONTACT, b.toString());
    }

    private void jamming(RadarCombatPlugin radar, float now) {
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != SIDE || ship.isFighter() || !ship.isAlive()) continue;
            boolean jammed = EwCombatPlugin.isScrambled(engine, ship);
            if (jammed && jammedSeen.add(ship)) message(JAM, "JAMMED: " + ship.getName() + "'s radar is being scrambled");
            else if (!jammed) jammedSeen.remove(ship);
        }
        for (ShipAPI enemy : engine.getShips()) {
            if (enemy.getOwner() != 1 - SIDE || !RadarCombatPlugin.areaJamActive(engine, enemy)) continue;
            Float last = jammerWarned.get(enemy);
            if (last != null && now - last < 30f) continue;
            jammerWarned.put(enemy, now);
            // Only what the fleet can hear or knows
            if (!radar.isHeardBy(SIDE, enemy) && !AiAwareness.knows(engine, SIDE, enemy)) continue;
            message(JAM, "ENEMY JAMMING: area jamming from " + (AiAwareness.knows(engine, SIDE, enemy)
                    ? enemy.getHullSpec().getHullName() : "an unidentified ship"));
        }
        jammedSeen.removeIf(s -> !engine.isEntityInPlay(s));
        jammerWarned.keySet().removeIf(s -> !engine.isEntityInPlay(s));
    }
}
