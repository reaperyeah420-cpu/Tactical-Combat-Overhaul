package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;

/**
 * Backup Generator: after a reactor meltdown the engines keep running (shields stay down). Applied by
 * PowerCombatPlugin. The alternative to Auxiliary Generator and Secondary Energy Banks: not with either.
 */
public class BackupGenerator extends BaseHullMod {

    public static final String ID = "tco_backup_generator";

    public static boolean has(ShipVariantAPI variant) {
        return variant != null && variant.hasHullMod(ID);
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship == null || ship.isStationModule()) return false;
        ShipVariantAPI variant = ship.getVariant();
        return !AuxiliaryGenerator.has(variant) && !SecondaryEnergyBanks.has(variant);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship != null && AuxiliaryGenerator.has(ship.getVariant())) return "Not with the Auxiliary Generator";
        if (ship != null && SecondaryEnergyBanks.has(ship.getVariant())) return "Not with Secondary Energy Banks";
        return "Can't be fitted to modules";
    }
}
