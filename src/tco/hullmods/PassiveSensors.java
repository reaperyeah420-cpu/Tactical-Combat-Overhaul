package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/**
 * Passive Sensor Suite (ESM): listens for enemy radars and jammers. Emitters it hears show as vague contacts
 * (RadarCombatPlugin.listen); they are not radar tracks.
 */
public class PassiveSensors extends BaseHullMod {

    public static final String ID = "tco_esm";

    /** ESM fitted, or built into a CIC / Auxiliary CIC. */
    public static boolean has(ShipAPI ship) {
        return ship != null && ship.getVariant() != null
                && (ship.getVariant().hasHullMod(ID) || CombatInformationCenter.level(ship) > 0);
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && CombatInformationCenter.level(ship) == 0;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return "The CIC has its own ESM";
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return ThermalDispersal.times(TcoSettings.esmRangeMult);
        if (index == 1) return Math.round(TcoSettings.esmErrorFraction * 100f) + "%";
        return null;
    }
}
