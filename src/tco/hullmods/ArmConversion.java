package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/**
 * Anti-Radiation Missile Conversion: the ship's radar missiles launch at and home on enemy emissions its ESM hears
 * (radars switched on, running jammers, ECM jamming missiles) instead of radar tracks (RadarCombatPlugin). Needs the
 * Passive Sensor Suite; without it the conversion does nothing.
 */
public class ArmConversion extends BaseHullMod {

    public static final String ID = "tco_arm_conversion";

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && PassiveSensors.has(ship);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return "Requires a Passive Sensor Suite (ESM) or a CIC";
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round(TcoSettings.armGraceSeconds) + "";
        return null;
    }
}
