package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;
import tco.boarding.BoardingCombatPlugin;

/**
 * Breaching Charges: boarding shuttles blow their way in. Each docking shuttle damages the target's hull (never below
 * breachHullFloor of it, so the prize survives), and a ship's first wave onto a target fights harder for a while,
 * taking the first decks faster (BoardingCombatPlugin). Needs a Boarding Shuttle Bay.
 */
public class BreachingCharges extends BaseHullMod {

    public static final String ID = "tco_breaching_charges";

    public static boolean has(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(ID);
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(BoardingCombatPlugin.HULLMOD_ID);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return "Needs a Boarding Shuttle Bay";
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return String.valueOf(Math.round(TcoSettings.breachHullDamage));
        if (index == 1) return Math.round(TcoSettings.breachAttackBonus * 100f) + "%";
        if (index == 2) return String.valueOf(Math.round(TcoSettings.breachSeconds));
        return null;
    }
}
