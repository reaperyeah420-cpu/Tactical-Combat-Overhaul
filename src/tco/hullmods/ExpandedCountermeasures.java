package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/** Expanded Countermeasures: more flares (ecdFrigateMult on frigates, ecdOtherMult otherwise). */
public class ExpandedCountermeasures extends BaseHullMod {

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return "+" + Math.round((TcoSettings.ecdFrigateMult - 1f) * 100f) + "%";
        if (index == 1) return "+" + Math.round((TcoSettings.ecdOtherMult - 1f) * 100f) + "%";
        if (index == 2) return TcoSettings.flaresFrigate + "/" + TcoSettings.flaresDestroyer + "/"
                + TcoSettings.flaresCruiser + "/" + TcoSettings.flaresCapital;
        return null;
    }
}
