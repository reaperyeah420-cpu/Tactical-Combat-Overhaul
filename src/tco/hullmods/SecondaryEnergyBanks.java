package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipVariantAPI;

import tco.TcoSettings;

/** Secondary Energy Banks: more power storage. Applied by PowerCombatPlugin. */
public class SecondaryEnergyBanks extends BaseHullMod {

    public static final String ID = "tco_energy_banks";

    public static boolean has(ShipVariantAPI variant) {
        return variant != null && variant.hasHullMod(ID);
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && !ship.isStationModule() && !BackupGenerator.has(ship.getVariant());
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship != null && BackupGenerator.has(ship.getVariant())) return "Not with the Backup Generator";
        return "Can't be fitted to modules";
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round(TcoSettings.energyBanksCapacityBonus * 100f) + "%";
        return null;
    }
}
