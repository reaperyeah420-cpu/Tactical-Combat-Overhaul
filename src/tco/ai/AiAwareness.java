package tco.ai;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.TcoSettings;
import tco.battle.DeathThroes;
import tco.hullmods.CombatInformationCenter;
import tco.radar.RadarCombatPlugin;
import tco.rts.FogOfWar;

/**
 * What each side knows about the other's ships.
 * <ul>
 * <li>Firsthand: one of its ships has the enemy in sight range or on its own radar (or its Datalink network).</li>
 * <li>Relayed: the side as a whole knows. A firsthand sighting reaches the side after a random delay of up to
 * aiRelayMaxSeconds (auxCicRelayMaxSeconds with an Auxiliary CIC, cicRelayMaxSeconds with a CIC); if the enemy drops
 * out of every ship's sight and radar first, the report is lost. A lost contact stays known for aiBlindGraceSeconds
 * and remembered (for the search routine) for aiSearchMemorySeconds.</li>
 * </ul>
 * A ship acts on what its side knows plus what it sees or tracks itself (knowsShip). The game's admiral picks
 * ENGAGE / INTERCEPT / HARASS targets from the whole enemy fleet, so its orders on ships the side doesn't know are
 * removed. Losing the command ship (decapitation) leaves the side without its relayed picture for decapSeconds.
 */
public class AiAwareness extends BaseEveryFrameCombatPlugin {

    public static final String ENGINE_KEY = "tco_aiAwareness";
    private static final float INTERVAL = 0.2f;

    /** Where a ship was last known to be. */
    public static final class Known {
        public final Vector2f loc = new Vector2f();
        public final Vector2f vel = new Vector2f();
        public float time;
    }

    private CombatEngineAPI engine;
    private float timer;
    private boolean logged;
    private final List<Map<ShipAPI, Known>> known = new ArrayList<Map<ShipAPI, Known>>();
    // Sightings on their way to the rest of the side: when they arrive
    private final List<Map<ShipAPI, Float>> pending = new ArrayList<Map<ShipAPI, Float>>();
    private final int[] commandLevel = new int[2];
    private final float[] decapUntil = { -1f, -1f };

    public AiAwareness() {
        for (int i = 0; i < 2; i++) {
            known.add(new HashMap<ShipAPI, Known>());
            pending.add(new HashMap<ShipAPI, Float>());
        }
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
    }

    public static AiAwareness get(CombatEngineAPI engine) {
        Object plugin = engine == null ? null : engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof AiAwareness ? (AiAwareness) plugin : null;
    }

    /** The side as a whole knows the ship is there (always for its own ships, or with aiBlindToUnseen off). */
    public static boolean knows(CombatEngineAPI engine, int side, ShipAPI target) {
        if (!TcoSettings.aiBlindToUnseen || target == null || side < 0 || side > 1) return true;
        if (target.getOwner() == side || target.getOwner() < 0 || target.getOwner() > 1) return true;
        AiAwareness awareness = get(engine);
        if (awareness == null) return true;
        if (awareness.decapitated(side)) return false;
        if (target.isFighter()) return awareness.firsthand(side, target);
        Known k = awareness.known.get(side).get(target);
        return k != null && engine.getTotalElapsedTime(false) - k.time <= TcoSettings.aiBlindGraceSeconds;
    }

    /** This ship knows the target: its side does, or it sees or tracks it itself. */
    public static boolean knowsShip(CombatEngineAPI engine, ShipAPI ship, ShipAPI target) {
        if (ship == null) return true;
        int side = ship.getOwner();
        if (knows(engine, side, target)) return true;
        if (sees(ship, target)) return true;
        RadarCombatPlugin radar = RadarCombatPlugin.get(engine);
        return radar != null && TcoSettings.radarEnabled && radar.tracks(ship, target, RadarCombatPlugin.hasDatalink(ship));
    }

    private static boolean sees(ShipAPI ship, ShipAPI target) {
        if (!ship.isAlive() || ship.isHulk()) return false;
        float r = FogOfWar.sightRange(ship) + target.getCollisionRadius() * 0.5f;
        float dx = ship.getLocation().x - target.getLocation().x, dy = ship.getLocation().y - target.getLocation().y;
        return dx * dx + dy * dy <= r * r;
    }

    /** Enemy ships this side knows (or knew within aiSearchMemorySeconds), with where and when. */
    public Map<ShipAPI, Known> memory(int side) {
        return known.get(side);
    }

    /** The side's relayed picture is down after losing its command ship. */
    public boolean decapitated(int side) {
        return side >= 0 && side <= 1 && engine != null && engine.getTotalElapsedTime(false) < decapUntil[side];
    }

    public static boolean isDecapitated(CombatEngineAPI engine, int side) {
        AiAwareness awareness = get(engine);
        return awareness != null && awareness.decapitated(side);
    }

    /** 2 with a live CIC, 1 with only an Auxiliary CIC, 0 without. */
    public static int commandLevel(CombatEngineAPI engine, int side) {
        AiAwareness awareness = get(engine);
        return awareness == null || side < 0 || side > 1 ? 0 : awareness.commandLevel[side];
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused() || !(TcoSettings.aiBlindToUnseen || TcoSettings.aiSearchEnabled)) return;
        timer -= amount;
        if (timer > 0f) return;
        timer = INTERVAL;
        float now = engine.getTotalElapsedTime(false);
        updateCommand(now);
        refresh(now);
        if (!TcoSettings.aiBlindToUnseen) return;
        // The enemy's admiral, and the admiral running the player's allies
        filterOrders(engine.getFleetManager(1), false, 1);
        filterOrders(engine.getFleetManager(0), true, 0);
        clearTargets();
    }

    /** The best live command ship per side; losing it starts a decapitation. */
    private void updateCommand(float now) {
        int[] level = new int[2];
        for (ShipAPI ship : engine.getShips()) {
            int side = ship.getOwner();
            if (side < 0 || side > 1 || !ship.isAlive() || ship.isHulk() || ship.isRetreating() || DeathThroes.isDying(ship)
                    || !engine.isEntityInPlay(ship)) {
                continue;
            }
            level[side] = Math.max(level[side], CombatInformationCenter.level(ship));
        }
        for (int side = 0; side <= 1; side++) {
            int was = commandLevel[side];
            commandLevel[side] = level[side];
            if (level[side] >= was) continue;
            // Lost the command ship: the Auxiliary CIC takes over after a short gap, without one the picture is gone
            float gap = was == 2 && level[side] == 1 ? TcoSettings.auxTakeoverSeconds
                    : was == 2 ? TcoSettings.decapSeconds : TcoSettings.decapSeconds * 0.5f;
            decapUntil[side] = Math.max(decapUntil[side], now + gap);
            for (Map.Entry<ShipAPI, Float> e : pending.get(side).entrySet()) e.setValue(Math.max(e.getValue(), decapUntil[side]));
            boolean player = engine.getPlayerShip() != null && engine.getPlayerShip().getOwner() == side;
            if (player && engine.getCombatUI() != null) {
                engine.getCombatUI().addMessage(0, new Color(255, 120, 80), level[side] == 1
                        ? "CIC lost: the Auxiliary CIC takes over in " + Math.round(gap) + " s"
                        : (was == 2 ? "CIC lost" : "Auxiliary CIC lost") + ": the fleet's picture is down for " + Math.round(gap) + " s");
            }
        }
    }

    private float relayMax(int side) {
        if (decapitated(side)) return TcoSettings.aiRelayMaxSeconds;
        int level = commandLevel[side];
        return level == 2 ? TcoSettings.cicRelayMaxSeconds : level == 1 ? TcoSettings.auxCicRelayMaxSeconds : TcoSettings.aiRelayMaxSeconds;
    }

    private void refresh(float now) {
        RadarCombatPlugin radar = RadarCombatPlugin.get(engine);
        for (int side = 0; side <= 1; side++) {
            Map<ShipAPI, Known> map = known.get(side);
            Map<ShipAPI, Float> waiting = pending.get(side);
            List<ShipAPI> eyes = new ArrayList<ShipAPI>();
            for (ShipAPI ship : engine.getShips()) {
                if (FogOfWar.reveals(ship, side)) eyes.add(ship);
            }
            Set<ShipAPI> contacts = radar != null && TcoSettings.radarEnabled ? radar.contacts(side) : null;
            boolean down = decapitated(side);
            for (ShipAPI ship : engine.getShips()) {
                if (ship.getOwner() != 1 - side || !ship.isAlive() || ship.isHulk() || ship.isFighter()) continue;
                if (!firsthand(ship, eyes, contacts)) {
                    // Gone before the report got through
                    waiting.remove(ship);
                    continue;
                }
                Known k = map.get(ship);
                if (k != null) {
                    // Already known: the picture follows it (not while the side's picture is down)
                    if (!down) update(k, ship, now);
                    continue;
                }
                Float at = waiting.get(ship);
                if (at == null) {
                    float max = Math.max(0f, relayMax(side));
                    float delay = max * (0.25f + 0.75f * (float) Math.random());
                    waiting.put(ship, Math.max(now + delay, down ? decapUntil[side] : 0f));
                } else if (now >= at && !down) {
                    waiting.remove(ship);
                    k = new Known();
                    update(k, ship, now);
                    map.put(ship, k);
                }
            }
            for (Iterator<Map.Entry<ShipAPI, Known>> it = map.entrySet().iterator(); it.hasNext();) {
                Map.Entry<ShipAPI, Known> e = it.next();
                ShipAPI ship = e.getKey();
                if (!ship.isAlive() || ship.isHulk() || ship.getOwner() != 1 - side || !engine.isEntityInPlay(ship)
                        || now - e.getValue().time > Math.max(TcoSettings.aiSearchMemorySeconds, TcoSettings.aiBlindGraceSeconds)) {
                    it.remove();
                }
            }
            final int enemy = 1 - side;
            waiting.keySet().removeIf(s -> !s.isAlive() || s.isHulk() || !engine.isEntityInPlay(s) || s.getOwner() != enemy);
        }
    }

    private static void update(Known k, ShipAPI ship, float now) {
        k.loc.set(ship.getLocation());
        k.vel.set(ship.getVelocity());
        k.time = now;
    }

    private static boolean firsthand(ShipAPI target, List<ShipAPI> eyes, Set<ShipAPI> contacts) {
        if (contacts != null && contacts.contains(target)) return true;
        for (ShipAPI eye : eyes) {
            if (sees(eye, target)) return true;
        }
        return false;
    }

    private boolean firsthand(int side, ShipAPI target) {
        List<ShipAPI> eyes = new ArrayList<ShipAPI>();
        for (ShipAPI ship : engine.getShips()) {
            if (FogOfWar.reveals(ship, side)) eyes.add(ship);
        }
        RadarCombatPlugin radar = RadarCombatPlugin.get(engine);
        return firsthand(target, eyes, radar != null && TcoSettings.radarEnabled ? radar.contacts(side) : null);
    }

    /** Drops admiral orders aimed at enemy ships the side doesn't know about; the admiral picks again. */
    private void filterOrders(CombatFleetManagerAPI fleet, boolean allies, int side) {
        if (fleet == null) return;
        CombatTaskManagerAPI tasks;
        try {
            tasks = fleet.getTaskManager(allies);
        } catch (RuntimeException e) {
            return;
        }
        if (tasks == null) return;
        for (AssignmentInfo info : new ArrayList<AssignmentInfo>(tasks.getAllAssignments())) {
            AssignmentTargetAPI target = info.getTarget();
            if (!(target instanceof DeployedFleetMemberAPI)) continue;
            ShipAPI ship = ((DeployedFleetMemberAPI) target).getShip();
            if (ship == null || ship.getOwner() == side || knows(engine, side, ship)) continue;
            try {
                tasks.removeAssignment(info);
            } catch (RuntimeException e) {
                continue;
            }
            if (!logged) {
                logged = true;
                Global.getLogger(AiAwareness.class).info("TCO AI awareness: removed a " + info.getType()
                        + " order on unseen " + ship.getHullSpec().getHullName() + " (side " + side + ")");
            }
        }
    }

    /** An admiral order aimed at an enemy ship the side doesn't know about (filterOrders removes these). */
    public static boolean isBlindOrder(CombatEngineAPI engine, int side, AssignmentInfo info) {
        if (info == null || !(info.getTarget() instanceof DeployedFleetMemberAPI)) return false;
        ShipAPI target = ((DeployedFleetMemberAPI) info.getTarget()).getShip();
        return target != null && target.getOwner() != side && !knows(engine, side, target);
    }

    /** AI ships drop a selected target they don't know about (not reported yet, and not seen or tracked themselves). */
    private void clearTargets() {
        for (ShipAPI ship : engine.getShips()) {
            int side = ship.getOwner();
            boolean ai = side == 1 || (side == 0 && ship.isAlly());
            if (!ai || !ship.isAlive() || ship.isHulk() || ship == engine.getPlayerShip()) continue;
            ShipAPI target = ship.getShipTarget();
            if (target == null || knowsShip(engine, ship, target)) continue;
            try {
                ship.setShipTarget(null);
            } catch (RuntimeException e) {
            }
        }
    }
}
