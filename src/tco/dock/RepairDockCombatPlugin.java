package tco.dock;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.Msg;
import tco.TcoSettings;
import tco.repair.RepairCombatPlugin;
import tco.tug.TowCombatPlugin;

/**
 * Repair Dock Beacon objective: the captor gets a Repair Dock (a tender with its own stock) on its half,
 * replaced after a delay if destroyed while the side still holds the beacon.
 */
public class RepairDockCombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final float MIN_FROM_CENTRE = 0.15f;
    private static final float MAX_FROM_CENTRE = 0.35f;
    private static final float CLEARANCE = 1500f;

    private CombatEngineAPI engine;
    private final Map<Integer, ShipAPI> docks = new HashMap<Integer, ShipAPI>();
    private final Map<Integer, Vector2f> sites = new HashMap<Integer, Vector2f>();
    private final Map<Integer, Float> respawn = new HashMap<Integer, Float>();

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine != null && engine.isPaused()) amount = 0f;
        if (engine == null || !TcoSettings.dockEnabled) return;

        Iterator<Map.Entry<Integer, ShipAPI>> it = docks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ShipAPI> entry = it.next();
            ShipAPI dock = entry.getValue();
            if (!dock.isAlive() || dock.isHulk()) {
                int owner = entry.getKey();
                sites.remove(owner);
                respawn.put(owner, TcoSettings.dockRespawnSeconds);
                it.remove();
                Msg.send(engine, owner == 0 ? Msg.PLAYER : Msg.ENEMY, (owner == 0 ? "Your " : "The enemy ") + RepairDock.NAME
                        + " has been destroyed." + (owner == 0 ? " A new one can be set up in "
                        + Math.round(TcoSettings.dockRespawnSeconds) + " seconds if you hold the beacon." : ""));
                continue;
            }
            Vector2f site = sites.get(entry.getKey());
            if (site != null) dock.getLocation().set(site);
            dock.getVelocity().set(0f, 0f);
        }

        if (amount > 0f) {
            for (Map.Entry<Integer, Float> timer : respawn.entrySet()) {
                if (timer.getValue() > 0f) timer.setValue(timer.getValue() - amount);
            }
        }

        if (amount > 0f && !docks.isEmpty()) holdStranded(amount);

        for (BattleObjectiveAPI objective : engine.getObjectives()) {
            if (!RepairDock.OBJECTIVE_ID.equals(objective.getType())) continue;
            int owner = objective.getOwner();
            if (owner != 0 && owner != 1) continue;
            ShipAPI dock = docks.get(owner);
            if (dock != null) {
                // Holding the beacon restocks the dock
                if (amount > 0f) beaconRestock(dock, owner, amount);
                continue;
            }
            Float wait = respawn.get(owner);
            if (wait == null || wait <= 0f) build(owner);
        }

    }

    private final Map<Integer, Float> droneTimers = new HashMap<Integer, Float>();

    private void beaconRestock(ShipAPI dock, int owner, float amount) {
        RepairCombatPlugin repair = RepairCombatPlugin.get(engine);
        if (repair == null) return;
        float timer = (droneTimers.containsKey(owner) ? droneTimers.get(owner) : 0f) + amount;
        int drones = 0;
        if (timer >= TcoSettings.dockBeaconDroneSeconds) {
            timer -= TcoSettings.dockBeaconDroneSeconds;
            drones = 1;
        }
        droneTimers.put(owner, timer);
        repair.addStock(dock, TcoSettings.dockBeaconPartsPerSecond * amount, drones);
    }

    private void holdStranded(float amount) {
        float slow = Math.min(1f, amount * TcoSettings.dockHoldRate);
        for (ShipAPI ship : engine.getShips()) {
            ShipAPI dock = docks.get(ship.getOwner());
            if (dock == null || ship == dock || !TowCombatPlugin.isImmobilized(engine, ship)) continue;
            if (TowCombatPlugin.isTowed(engine, ship)) continue;
            float dx = ship.getLocation().x - dock.getLocation().x;
            float dy = ship.getLocation().y - dock.getLocation().y;
            float reach = TcoSettings.dockRange + dock.getCollisionRadius() + ship.getCollisionRadius();
            if (dx * dx + dy * dy > reach * reach) continue;
            Vector2f v = ship.getVelocity();
            v.set(v.x * (1f - slow), v.y * (1f - slow));
            ship.setAngularVelocity(ship.getAngularVelocity() * (1f - slow));
        }
    }

    private float sideSign(int owner) {
        float sum = 0f;
        int count = 0;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != owner || ship.isFighter() || ship.isHulk() || !ship.isAlive()) continue;
            if (ship.isStation() || ship.isStationModule() || ship.isShuttlePod()) continue;
            sum += ship.getLocation().y;
            count++;
        }
        if (count > 0 && Math.abs(sum / count) > 200f) return sum > 0f ? 1f : -1f;
        return owner == 0 ? -1f : 1f;
    }

    private Vector2f pickSite(int owner) {
        float w = engine.getMapWidth();
        float h = engine.getMapHeight();
        float sign = sideSign(owner);
        Vector2f best = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            float back = MIN_FROM_CENTRE + (float) Math.random() * (MAX_FROM_CENTRE - MIN_FROM_CENTRE);
            float across = ((float) Math.random() - 0.5f) * 0.6f;
            Vector2f candidate = new Vector2f(w * across, sign * h * back);
            if (best == null) best = candidate;
            boolean clear = true;
            for (BattleObjectiveAPI objective : engine.getObjectives()) {
                if (distance(candidate, objective.getLocation()) < CLEARANCE) clear = false;
            }
            for (ShipAPI ship : engine.getShips()) {
                if (ship.isFighter()) continue;
                if (distance(candidate, ship.getLocation()) < 700f + ship.getCollisionRadius()) clear = false;
            }
            if (clear) return candidate;
        }
        return best;
    }

    private static float distance(Vector2f a, Vector2f b) {
        return (float) Math.hypot(a.x - b.x, a.y - b.y);
    }

    private void build(int owner) {
        try {
            Vector2f site = pickSite(owner);
            CombatFleetManagerAPI fleet = engine.getFleetManager(owner);
            boolean quiet = fleet.isSuppressDeploymentMessages();
            fleet.setSuppressDeploymentMessages(true);
            ShipAPI dock;
            try {
                dock = fleet.spawnShipOrWing(RepairDock.VARIANT_ID, new Vector2f(site), 90f);
            } finally {
                fleet.setSuppressDeploymentMessages(quiet);
            }
            fleet.removeDeployed(dock, false);
            dock.setInvalidTransferCommandTarget(true);
            // Modest death blast
            dock.setExplosionScale(TcoSettings.dockExplosionScale);
            dock.setNoDamagedExplosions(true);
            docks.put(owner, dock);
            sites.put(owner, site);
            respawn.remove(owner);
            Msg.send(engine, owner == 0 ? Msg.PLAYER : Msg.ENEMY, owner == 0
                    ? "Beacon captured: a " + RepairDock.NAME + " has been set up behind your lines."
                    : "The enemy has set up a " + RepairDock.NAME + " behind their lines.");
        } catch (RuntimeException e) {
            Global.getLogger(RepairDockCombatPlugin.class).warn("TCO: could not build the Repair Dock", e);
            respawn.put(owner, 30f);
        }
    }

}
