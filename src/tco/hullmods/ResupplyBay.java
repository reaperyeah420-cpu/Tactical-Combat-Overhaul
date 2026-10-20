package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;
import tco.ammo.AmmoRules;

/** Combat resupply ship; see AmmoCombatPlugin. */
public class ResupplyBay extends BaseHullMod {

    private static final HullSize[] SIZES = {
        HullSize.FRIGATE, HullSize.DESTROYER, HullSize.CRUISER, HullSize.CAPITAL_SHIP
    };

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index >= 0 && index < 4) return "" + Math.round(AmmoRules.supplyCapacity(SIZES[index]));
        if (index >= 4 && index < 8) return "" + Math.round(AmmoRules.resupplyRange(SIZES[index - 4]));
        return null;
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return SupportBays.applicable(ship, SupportBays.RESUPPLY);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return SupportBays.reason(ship, SupportBays.RESUPPLY);
    }
}
