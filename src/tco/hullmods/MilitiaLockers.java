package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import tco.TcoSettings;

/**
 * Militia Lockers: the crew defend against boarders as militia at a fraction of a marine's strength. No effect
 * outside boarding; the fighting is in BoardingCombatPlugin.
 */
public class MilitiaLockers extends BaseHullMod {

    public static final String ID = "tco_militia_lockers";

    public static boolean has(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(ID);
    }

    public static boolean has(FleetMemberAPI member) {
        return member != null && member.getVariant() != null && member.getVariant().hasHullMod(ID);
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && !ship.getVariant().hasHullMod("automated")
                && !ship.isStation() && ship.getHullSpec() != null && ship.getHullSpec().getMinCrew() > 0f;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return "Needs a crew to arm";
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round(TcoSettings.militiaStrength * 100f) + "%";
        if (index == 1) return Math.round(TcoSettings.crewStrength * 100f) + "%";
        return null;
    }
}
