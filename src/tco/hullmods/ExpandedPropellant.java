package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/**
 * Expanded Propellant: bigger charges make ballistic shells fly faster, and since TCO's penetration grows with shell
 * speed, pierce better. The charges take magazine space, and a cook-off aboard is more violent (ArmorEffects).
 */
public class ExpandedPropellant extends BaseHullMod {

    public static final String ID = "tco_expanded_propellant";
    private static boolean logged;

    public static boolean has(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(ID);
    }

    /** Cook-off blast multiplier for this ship. */
    public static float cookoffMult(ShipAPI ship) {
        return has(ship) ? TcoSettings.propellantCookoffMult : 1f;
    }

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        TcoSettings.load();
        stats.getBallisticProjectileSpeedMult().modifyPercent(id, TcoSettings.propellantSpeedPercent);
        stats.getBallisticAmmoBonus().modifyMult(id, TcoSettings.propellantAmmoMult);
        if (!logged) {
            logged = true;
            com.fs.starfarer.api.Global.getLogger(ExpandedPropellant.class).info("TCO: Expanded Propellant applied, ballistic shell speed x"
                    + stats.getBallisticProjectileSpeedMult().getModifiedValue());
        }
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round(TcoSettings.propellantSpeedPercent) + "%";
        if (index == 1) return Math.round((1f - TcoSettings.propellantAmmoMult) * 100f) + "%";
        if (index == 2) return Math.round((TcoSettings.propellantCookoffMult - 1f) * 100f) + "%";
        return null;
    }
}
