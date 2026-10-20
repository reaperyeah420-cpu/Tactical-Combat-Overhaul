package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/** Tows stranded friendly ships; see TowCombatPlugin. */
public class TugRig extends BaseHullMod {

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round(TcoSettings.tugMinSpeedMult * 100f) + "%";
        return null;
    }
}
