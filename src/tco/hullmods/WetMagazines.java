package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/**
 * Wet Magazine Stowage: ammunition kept in flooded compartments rarely cooks off, and a cook-off is less likely to
 * start a fire (ArmorEffects). The flooding takes magazine space. Stacks with Blowout Panels, which cuts the blast.
 */
public class WetMagazines extends BaseHullMod {

    public static final String ID = "tco_wet_magazines";

    public static boolean has(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(ID);
    }

    /** Multiplier on the chance a breach sets off a magazine. */
    public static float chanceMult(ShipAPI ship) {
        return has(ship) ? TcoSettings.wetCookoffChanceMult : 1f;
    }

    /** Multiplier on the chance a cook-off starts a fire. */
    public static float fireMult(ShipAPI ship) {
        return has(ship) ? TcoSettings.wetFireMult : 1f;
    }

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        TcoSettings.load();
        stats.getBallisticAmmoBonus().modifyMult(id, TcoSettings.wetAmmoMult);
        stats.getMissileAmmoBonus().modifyMult(id, TcoSettings.wetAmmoMult);
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round((1f - TcoSettings.wetCookoffChanceMult) * 100f) + "%";
        if (index == 1) return Math.round((1f - TcoSettings.wetFireMult) * 100f) + "%";
        if (index == 2) return Math.round((1f - TcoSettings.wetAmmoMult) * 100f) + "%";
        return null;
    }
}
