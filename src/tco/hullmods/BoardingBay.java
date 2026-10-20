package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;
import tco.boarding.BoardingCombatPlugin;

/** Marks a ship able to board; see BoardingCombatPlugin. */
public class BoardingBay extends BaseHullMod {

    private static final HullSize[] SIZES = {
        HullSize.FRIGATE, HullSize.DESTROYER, HullSize.CRUISER, HullSize.CAPITAL_SHIP
    };

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index >= 0 && index < 4) return "" + BoardingCombatPlugin.shuttlesFor(SIZES[index]);
        if (index == 4) return "" + TcoSettings.marinesPerShuttle;
        if (index == 5) return "" + Math.round(TcoSettings.boardingWaveCooldown);
        return null;
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return SupportBays.applicable(ship, SupportBays.BOARDING);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return SupportBays.reason(ship, SupportBays.BOARDING);
    }
}
