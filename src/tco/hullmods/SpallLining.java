package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/** Spall Lining: shells penetrate spallPenetrationMult as well, for a little maneuverability. */
public class SpallLining extends BaseHullMod {

    public static final String ID = "tco_spall";

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        TcoSettings.load();
        float mult = 1f - TcoSettings.spallManeuverPenalty;
        stats.getAcceleration().modifyMult(id, mult);
        stats.getDeceleration().modifyMult(id, mult);
        stats.getTurnAcceleration().modifyMult(id, mult);
        stats.getMaxTurnRate().modifyMult(id, mult);
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round((1f - TcoSettings.spallPenetrationMult) * 100f) + "%";
        if (index == 1) return Math.round(TcoSettings.spallManeuverPenalty * 100f) + "%";
        return null;
    }

    public static float penetrationMult(ShipAPI ship) {
        if (ship == null || ship.getVariant() == null || !ship.getVariant().hasHullMod(ID)) return 1f;
        return Math.max(0f, TcoSettings.spallPenetrationMult);
    }
}
