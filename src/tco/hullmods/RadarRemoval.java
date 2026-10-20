package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.radar.RadarCombatPlugin;

/**
 * Radar Removal: frees OP; the ship has no radar of its own and fires radar missiles only on Datalink tracks.
 */
public class RadarRemoval extends BaseHullMod {

    public static final String ID = "tco_radar_removal";

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && !ship.getVariant().hasHullMod(RadarCombatPlugin.AESA_ID);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return "Incompatible with AESA Radar";
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        return null;
    }
}
