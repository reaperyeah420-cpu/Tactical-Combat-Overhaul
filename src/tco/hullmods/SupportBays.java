package tco.hullmods;

import com.fs.starfarer.api.combat.ShipAPI;

/** Resupply, boarding and repair bays share the same space: one per ship (a CIC may have resupply and repair). */
final class SupportBays {

    static final String RESUPPLY = "tco_resupply_bay";
    static final String BOARDING = "tco_boarding_bay";
    static final String REPAIR = "tco_repair_bay";

    private SupportBays() {
    }

    static String conflict(ShipAPI ship, String self) {
        if (ship == null || ship.getVariant() == null) return null;
        // A CIC is the fleet's anchor: munitions and repairs both, but no boarding bay alongside them
        if (CombatInformationCenter.isCic(ship) && !BOARDING.equals(self) && !ship.getVariant().hasHullMod(BOARDING)) return null;
        if (!RESUPPLY.equals(self) && ship.getVariant().hasHullMod(RESUPPLY)) return "Munitions Resupply Bay";
        if (!BOARDING.equals(self) && ship.getVariant().hasHullMod(BOARDING)) return "Boarding Shuttle Bay";
        if (!REPAIR.equals(self) && ship.getVariant().hasHullMod(REPAIR)) return "Repair Tender Bay";
        return null;
    }

    static boolean applicable(ShipAPI ship, String self) {
        return ship != null && !ship.isFighter() && !ship.isStation() && conflict(ship, self) == null;
    }

    static String reason(ShipAPI ship, String self) {
        String other = conflict(ship, self);
        if (other != null) return "Incompatible with " + other;
        return "Can only be installed on ships";
    }
}
