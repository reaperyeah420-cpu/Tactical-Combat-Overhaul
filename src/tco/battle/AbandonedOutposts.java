package tco.battle;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.Msg;
import tco.TcoSettings;
import tco.ammo.AmmoRules;
import tco.dock.RepairDock;
import tco.fob.SupplyFob;
import tco.rescue.EscapePodPlugin;

/**
 * Scuttles a side's FOBs and docks once it has no ships left and nothing to send in, so the battle can end.
 */
public class AbandonedOutposts extends BaseEveryFrameCombatPlugin {

    private CombatEngineAPI engine;
    private final boolean[] hadShips = new boolean[2];
    private final float[] emptyFor = new float[2];
    private float timer;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused() || amount <= 0f || engine.isCombatOver()) return;
        if (!TcoSettings.outpostsDontHoldBattles) return;
        timer -= amount;
        if (timer > 0f) return;
        timer = 1f;
        for (int side = 0; side <= 1; side++) {
            List<ShipAPI> outposts = new ArrayList<ShipAPI>();
            boolean ships = false;
            for (ShipAPI ship : engine.getShips()) {
                if (ship.getOwner() != side || !ship.isAlive() || ship.isHulk()) continue;
                if (SupplyFob.isFobPart(ship) || RepairDock.isDock(ship) || RepairDock.isDock(ship.getParentStation())) {
                    outposts.add(ship);
                    continue;
                }
                if (ship.isFighter() || ship.isDrone() || ship.isShuttlePod() || AmmoRules.isShuttle(ship)) continue;
                if (ship.getCustomData().containsKey(EscapePodPlugin.POD_KEY)) continue;
                ships = true;
            }
            if (ships) {
                hadShips[side] = true;
                emptyFor[side] = 0f;
                continue;
            }
            if (outposts.isEmpty() || !hadShips[side] || !nothingToSend(side)) {
                emptyFor[side] = 0f;
                continue;
            }
            emptyFor[side] += 1f;
            if (emptyFor[side] < TcoSettings.outpostAbandonSeconds) continue;
            for (ShipAPI part : outposts) {
                if (!part.isAlive() || part.isHulk()) continue;
                engine.applyDamage(part, part.getLocation(), part.getHitpoints() * 10f + 100000f, DamageType.ENERGY, 0f,
                        true, false, null, false);
            }
            Msg.send(engine, Msg.PLAYER,
                    side == 0 ? "With no ships left to cover them, your outposts' crews scuttle them."
                            : "With no ships left to cover them, the enemy scuttles their outposts.",
                    "outposts-scuttled-" + side, 5f);
            emptyFor[side] = 0f;
        }
    }

    private boolean nothingToSend(int side) {
        CombatFleetManagerAPI fleet = engine.getFleetManager(side);
        if (fleet == null) return true;
        try {
            if (fleet.getTaskManager(false).isInFullRetreat()) return true;
        } catch (RuntimeException e) {
        }
        for (FleetMemberAPI member : fleet.getReservesCopy()) {
            if (member.isFighterWing() || member.getHullSpec() == null) continue;
            String hull = member.getHullSpec().getBaseHullId();
            if (hull != null && (hull.equals(SupplyFob.HULL_ID) || hull.equals(RepairDock.HULL_ID))) continue;
            return false;
        }
        return true;
    }
}
