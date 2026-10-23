package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/**
 * Blowout Panels: a magazine cook-off vents outward. The weapon is still lost, but the hull takes far less of the
 * blast and fires are less likely (ArmorEffects).
 */
public class BlowoutPanels extends BaseHullMod {

    public static final String ID = "tco_blowout_panels";

    public static boolean has(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(ID);
    }

    /** Cook-off hull damage multiplier for this ship. */
    public static float damageMult(ShipAPI ship) {
        return has(ship) ? TcoSettings.blowoutDamageMult : 1f;
    }

    /** Multiplier on the chance a cook-off starts a fire. */
    public static float fireMult(ShipAPI ship) {
        return has(ship) ? TcoSettings.blowoutFireMult : 1f;
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round((1f - TcoSettings.blowoutDamageMult) * 100f) + "%";
        if (index == 1) return Math.round((1f - TcoSettings.blowoutFireMult) * 100f) + "%";
        return null;
    }
}
