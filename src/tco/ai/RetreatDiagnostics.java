package tco.ai;

import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.TcoSettings;
import tco.power.PowerCombatPlugin;
import tco.rts.RtsCombatPlugin;

/**
 * Debug (debugRetreatLog): while a side is in full retreat, logs every 3 s what each of its ships is doing, to find
 * why retreating ships stop.
 */
public class RetreatDiagnostics extends BaseEveryFrameCombatPlugin {

    private static final float INTERVAL = 3f;

    private CombatEngineAPI engine;
    private float timer;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (!TcoSettings.debugRetreatLog || engine == null || engine.isPaused() || engine.isCombatOver()) return;
        timer -= amount;
        if (timer > 0f) return;
        timer = INTERVAL;
        for (int side = 0; side <= 1; side++) {
            CombatFleetManagerAPI fleet = engine.getFleetManager(side);
            if (fleet == null) continue;
            CombatTaskManagerAPI tasks = fleet.getTaskManager(false);
            if (tasks == null || !tasks.isInFullRetreat()) continue;
            log(side, fleet, tasks);
        }
    }

    private void log(int side, CombatFleetManagerAPI fleet, CombatTaskManagerAPI tasks) {
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        float half = engine.getMapHeight() / 2f;
        StringBuilder text = new StringBuilder("TCO retreat log, side " + side + " in full retreat at "
                + Math.round(engine.getTotalElapsedTime(false)) + " s:");
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != side || !ship.isAlive() || ship.isHulk() || ship.isFighter() || ship.isShuttlePod()) continue;
            if (fleet.getDeployedFleetMember(ship) == null) continue;
            AssignmentInfo order = tasks.getAssignmentFor(ship);
            Object ai = ship.getShipAI();
            // Side 0 leaves at the bottom (y = -half), side 1 at the top
            float toEdge = side == 0 ? ship.getLocation().y + half : half - ship.getLocation().y;
            text.append("\n  ").append(ship.getHullSpec().getHullName())
                    .append(": retreating=").append(ship.isRetreating())
                    .append(" order=").append(order == null ? "none" : order.getType())
                    .append(" ai=").append(ai == null ? "none" : ai.getClass().getName())
                    .append(" speed=").append(Math.round(ship.getVelocity().length()))
                    .append(" toEdge=").append(Math.round(toEdge))
                    .append(" flux=").append(Math.round(ship.getFluxLevel() * 100f)).append('%')
                    .append(" aiPressure=").append(Math.round(PowerCombatPlugin.aiPressure(ship) * 100f)).append('%')
                    .append(" venting=").append(ship.getFluxTracker().isVenting())
                    .append(" overloaded=").append(ship.getFluxTracker().isOverloaded())
                    .append(" enginesOut=").append(ship.getEngineController() != null
                            && ship.getEngineController().isFlamedOut())
                    .append(" meltdown=").append(power != null && power.isMeltedDown(ship))
                    .append(" rts=").append(RtsCombatPlugin.controls(engine, ship));
        }
        Global.getLogger(RetreatDiagnostics.class).info(text.toString());
    }
}
