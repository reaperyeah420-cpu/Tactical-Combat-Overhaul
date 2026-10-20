package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;
import tco.boarding.BoardingCombatPlugin;

/**
 * Boarding bay upgrades, mutually exclusive, destroyers and up: High-Capacity Shuttles and
 * Efficient Boarding Bay (id tco_large_bay). Effects in BoardingCombatPlugin and MarineAllocation.
 */
public class ShuttleBayUpgrade extends BaseHullMod {

    public static final String HIGH_CAPACITY = "tco_hc_shuttles";
    public static final String LARGE_BAY = "tco_large_bay";

    private static final HullSize[] SIZES = {
        HullSize.FRIGATE, HullSize.DESTROYER, HullSize.CRUISER, HullSize.CAPITAL_SHIP
    };

    private String other(String self) {
        return HIGH_CAPACITY.equals(self) ? LARGE_BAY : HIGH_CAPACITY;
    }

    private String self() {
        return spec != null ? spec.getId() : HIGH_CAPACITY;
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        if (ship == null || ship.getVariant() == null) return false;
        if (ship.getHullSize() == HullSize.FRIGATE) return false;
        if (!ship.getVariant().hasHullMod(BoardingCombatPlugin.HULLMOD_ID)) return false;
        return !ship.getVariant().hasHullMod(other(self()));
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship != null && ship.getHullSize() == HullSize.FRIGATE) return "Frigates don't carry enough marines to need it";
        if (ship != null && ship.getVariant() != null && !ship.getVariant().hasHullMod(BoardingCombatPlugin.HULLMOD_ID)) {
            return "Requires a Boarding Shuttle Bay";
        }
        return HIGH_CAPACITY.equals(self()) ? "Incompatible with Efficient Boarding Bay" : "Incompatible with High-Capacity Shuttles";
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (HIGH_CAPACITY.equals(self())) {
            if (index == 0) return "" + Math.round(TcoSettings.marinesPerShuttle * TcoSettings.hcShuttleMarineMult);
            if (index == 1) return "" + TcoSettings.marinesPerShuttle;
            if (index == 2) return Math.round((1f - TcoSettings.hcShuttleArmorMult) * 100f) + "%";
            if (index == 3) return Math.round((TcoSettings.hcShuttleCapacityMult - 1f) * 100f) + "%";
            return null;
        }
        if (index >= 0 && index < 4) {
            return "" + (Math.round(BoardingCombatPlugin.shuttlesFor(SIZES[index]) * TcoSettings.largeBayShuttleMult)
                    + TcoSettings.efficientBayExtraShuttles);
        }
        if (index == 4) return Math.round((TcoSettings.largeBayShuttleMult - 1f) * 100f) + "%, plus " + TcoSettings.efficientBayExtraShuttles;
        if (index == 5) return "" + Math.round(TcoSettings.boardingWaveCooldown * TcoSettings.efficientBayCooldownMult);
        if (index == 6) return "" + Math.round(TcoSettings.boardingWaveCooldown);
        return null;
    }
}
