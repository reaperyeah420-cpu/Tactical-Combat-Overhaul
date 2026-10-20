package tco.ai;

import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.TcoSettings;
import tco.ammo.AmmoRules;
import tco.repair.RepairCombatPlugin;

/**
 * Keeps at least one enemy supply ship and tender on the field, brought in from reserves at the enemy's edge.
 */
public class SupportDeployment extends BaseEveryFrameCombatPlugin {

    private static final int SIDE = 1;
    private static final float FIRST_CHECK = 4f;
    private static final float INTERVAL = 3f;

    private CombatEngineAPI engine;
    private float timer = FIRST_CHECK;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused() || !TcoSettings.npcDeploySupport) return;
        if (!engine.isInCampaign() || engine.isSimulation() || engine.isCombatOver()) return;
        timer -= amount;
        if (timer > 0f) return;
        timer = INTERVAL;
        CombatFleetManagerAPI fleet = engine.getFleetManager(SIDE);
        if (fleet == null || fleet.getTaskManager(false).isInFullRetreat()) return;

        boolean supplier = false, tender = false;
        float sx = 0f, sy = 0f;
        int n = 0;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != SIDE || !ship.isAlive() || ship.isHulk() || ship.isFighter() || ship.isDrone()
                    || ship.isShuttlePod() || ship.isStationModule()) {
                continue;
            }
            if (SupportRoles.isSupplier(ship)) supplier = true;
            if (SupportRoles.isTender(ship)) tender = true;
            sx += ship.getLocation().x;
            sy += ship.getLocation().y;
            n++;
        }
        // Let the game's deployment go first
        if (n == 0 || (supplier && tender)) return;
        FleetMemberAPI wantSupply = supplier ? null : cheapest(fleet, AmmoRules.HULLMOD_ID);
        FleetMemberAPI wantTender = tender ? null : cheapest(fleet, RepairCombatPlugin.HULLMOD_ID);
        Vector2f at = spawnPoint(sx / n, sy / n);
        float facing = at.y > 0f ? 270f : 90f;
        if (wantSupply != null) bringIn(fleet, wantSupply, at, facing);
        if (wantTender != null) bringIn(fleet, wantTender, new Vector2f(at.x + 400f, at.y), facing);
    }

    private FleetMemberAPI cheapest(CombatFleetManagerAPI fleet, String hullmod) {
        FleetMemberAPI best = null;
        for (FleetMemberAPI member : fleet.getReservesCopy()) {
            if (member.isFighterWing() || member.isStation() || member.getVariant() == null) continue;
            if (!member.getVariant().hasHullMod(hullmod)) continue;
            if (best == null || member.getDeploymentPointsCost() < best.getDeploymentPointsCost()) best = member;
        }
        return best;
    }

    private Vector2f spawnPoint(float x, float y) {
        float halfW = engine.getMapWidth() / 2f, halfH = engine.getMapHeight() / 2f;
        float edge = y >= 0f ? halfH : -halfH;
        return new Vector2f(Math.max(-halfW * 0.8f, Math.min(halfW * 0.8f, x)), edge);
    }

    private void bringIn(CombatFleetManagerAPI fleet, FleetMemberAPI member, Vector2f at, float facing) {
        try {
            fleet.spawnFleetMember(member, at, facing, 1.5f);
            if (fleet.getReservesCopy().contains(member)) fleet.removeFromReserves(member);
        } catch (RuntimeException e) {
        }
    }
}
