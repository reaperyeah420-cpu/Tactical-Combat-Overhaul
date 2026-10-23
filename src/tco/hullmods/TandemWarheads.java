package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/**
 * Tandem Warheads: a precursor charge on the ship's missiles defeats shields and reactive armor
 * (tco.missile.TandemCombatPlugin, ExplosiveReactiveArmor): each one it goes through costs tandemPenetrationLoss of the
 * warhead's damage, so a raised shield takes part and the rest reaches the hull.
 */
public class TandemWarheads extends BaseHullMod {

    public static final String ID = "tco_tandem_warheads";

    public static boolean has(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(ID);
    }

    /** A missile fired by a ship with Tandem Warheads (fighters' missiles count as their carrier's). */
    public static boolean fired(MissileAPI missile) {
        if (missile == null || missile.isFlare()) return false;
        ShipAPI source = missile.getSource();
        if (source != null && source.isFighter() && source.getWing() != null && source.getWing().getSourceShip() != null) {
            source = source.getWing().getSourceShip();
        }
        return has(source);
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round(TcoSettings.tandemPenetrationLoss * 100f) + "%";
        return null;
    }
}
