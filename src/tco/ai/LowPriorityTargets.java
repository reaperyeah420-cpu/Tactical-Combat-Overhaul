package tco.ai;

import java.util.List;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.Misc;

import tco.AiUtil;
import tco.TcoSettings;
import tco.ammo.AmmoRules;
import tco.battle.DeathThroes;
import tco.dock.RepairDock;
import tco.fob.SupplyFob;
import tco.rts.RtsCombatPlugin;

/**
 * Repair Docks and Supply FOBs are low-priority targets for the game's AI: a ship that has one as its target while
 * an enemy warship it knows of is within reach turns on the warship instead, so it doesn't stand shooting a platform
 * while it is under fire. Ships on the player's RTS orders are left to those.
 */
public class LowPriorityTargets extends BaseEveryFrameCombatPlugin {

    private static final float INTERVAL = 0.25f;
    // Past the ship's longest gun, for a warship to count as a threat
    private static final float THREAT_MARGIN = 1000f;

    private CombatEngineAPI engine;
    private float timer;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    public static boolean isLowPriority(ShipAPI ship) {
        return ship != null && (RepairDock.isDock(ship) || SupplyFob.isFobPart(ship));
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused() || engine.isCombatOver() || !TcoSettings.aiLowPriorityBases) return;
        timer -= amount;
        if (timer > 0f) return;
        timer = INTERVAL;
        for (ShipAPI ship : engine.getShips()) {
            if (!ship.isAlive() || ship.isHulk() || ship.isFighter() || ship.isShuttlePod() || isLowPriority(ship)) continue;
            if (ship.getOwner() != 0 && ship.getOwner() != 1) continue;
            if (AiUtil.playerFlying(engine, ship) || RtsCombatPlugin.controls(engine, ship) || !AiUtil.isGameAi(ship)) continue;
            ShipwideAIFlags flags = ship.getAIFlags();
            Object maneuver = flags == null ? null : flags.getCustom(AIFlags.MANEUVER_TARGET);
            ShipAPI target = ship.getShipTarget();
            boolean onBase = isLowPriority(target) || (maneuver instanceof ShipAPI && isLowPriority((ShipAPI) maneuver));
            if (!onBase) continue;
            ShipAPI threat = threat(ship);
            if (threat == null) continue;
            ship.setShipTarget(threat);
            if (flags != null) flags.setFlag(AIFlags.MANEUVER_TARGET, 1f, threat);
        }
    }

    /** The nearest enemy warship the ship knows of within its longest gun's reach plus a margin. */
    private ShipAPI threat(ShipAPI ship) {
        float reach = THREAT_MARGIN;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.isDecorative() || weapon.getType() == WeaponAPI.WeaponType.MISSILE) continue;
            reach = Math.max(reach, weapon.getRange() + THREAT_MARGIN);
        }
        ShipAPI best = null;
        float bestGap = Float.MAX_VALUE;
        for (ShipAPI other : engine.getShips()) {
            if (other.getOwner() == ship.getOwner() || other.getOwner() == 100) continue;
            if (!other.isAlive() || other.isHulk() || other.isFighter() || other.isShuttlePod() || isLowPriority(other)) continue;
            if (AmmoRules.isShuttle(other) || DeathThroes.isDying(other) || other.isPhased()) continue;
            float gap = Misc.getDistance(ship.getLocation(), other.getLocation()) - other.getCollisionRadius();
            if (gap > reach || gap >= bestGap) continue;
            if (!AiAwareness.knowsShip(engine, ship, other)) continue;
            best = other;
            bestGap = gap;
        }
        return best;
    }
}
