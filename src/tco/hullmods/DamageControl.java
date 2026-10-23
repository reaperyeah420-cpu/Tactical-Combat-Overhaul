package tco.hullmods;

import com.fs.starfarer.api.combat.ShipAPI;

import tco.TcoSettings;

/**
 * TCO's additions to the game's Automated Repair Unit (`autorepair`): fires burn out faster and do less damage
 * (Fires), and once per battle a destroyed weapon or engine is patched back with no tender (RepairCombatPlugin).
 */
public final class DamageControl {

    public static final String AUTOREPAIR = "autorepair";

    private DamageControl() {
    }

    public static boolean has(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(AUTOREPAIR);
    }

    public static float fireDurationMult(ShipAPI ship) {
        return has(ship) ? TcoSettings.repairUnitFireDurationMult : 1f;
    }

    public static float fireDamageMult(ShipAPI ship) {
        return has(ship) ? TcoSettings.repairUnitFireDamageMult : 1f;
    }
}
