package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/**
 * Thermal Dispersal Plating: energy bolts heat the armour less and the heat clears faster (EnergyHeat), for a
 * slightly lower armour rating.
 */
public class ThermalDispersal extends BaseHullMod {

    public static final String ID = "tco_thermal_plating";

    public static boolean has(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(ID);
    }

    /** Multiplier on the heat each energy hit adds. */
    public static float heatMult(ShipAPI ship) {
        return has(ship) ? TcoSettings.thermalHeatMult : 1f;
    }

    /** Multiplier on the time a fully heated spot takes to cool. */
    public static float coolMult(ShipAPI ship) {
        return has(ship) ? TcoSettings.thermalCoolMult : 1f;
    }

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        TcoSettings.load();
        stats.getArmorBonus().modifyMult(id, TcoSettings.thermalArmorMult);
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round((1f - TcoSettings.thermalHeatMult) * 100f) + "%";
        if (index == 1) return times(1f / Math.max(0.05f, TcoSettings.thermalCoolMult));
        if (index == 2) return Math.round((1f - TcoSettings.thermalArmorMult) * 100f) + "%";
        return null;
    }

    static String times(float value) {
        float r = Math.round(value * 10f) / 10f;
        return (r == (int) r ? String.valueOf((int) r) : String.valueOf(r)) + "x";
    }
}
