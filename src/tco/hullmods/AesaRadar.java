package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

public class AesaRadar extends BaseHullMod {

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && !ship.getVariant().hasHullMod(RadarRemoval.ID);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return "Incompatible with Radar Removal";
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round((TcoSettings.aesaRadarPower - 1f) * 100f) + "%";
        if (index == 1) return Math.round(((float) Math.sqrt(TcoSettings.aesaRadarPower) - 1f) * 100f) + "%";
        return null;
    }
}
