package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/** Radar Absorbing Materials: ramArmorHullMult armor and hull, RCS x ramRcsMult. */
public class RadarAbsorbing extends BaseHullMod {

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        TcoSettings.load();
        stats.getArmorBonus().modifyMult(id, TcoSettings.ramArmorHullMult);
        stats.getHullBonus().modifyMult(id, TcoSettings.ramArmorHullMult);
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round((1f - TcoSettings.ramRcsMult) * 100f) + "%";
        if (index == 1) return Math.round((1f - (float) Math.sqrt(TcoSettings.ramRcsMult)) * 100f) + "%";
        if (index == 2) return Math.round((1f - TcoSettings.ramArmorHullMult) * 100f) + "%";
        return null;
    }
}
