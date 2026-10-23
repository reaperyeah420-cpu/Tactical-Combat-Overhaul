package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;
import tco.radar.RadarCombatPlugin;

/**
 * Combat Information Center (capital ships): an AEW radar with wide and sector scans, built-in ESM, and the fleet's
 * fastest relay of sightings (AiAwareness). Losing it leaves the side without its picture for a while (decapitation).
 * The Auxiliary CIC (cruisers) is the smaller version and takes over when the CIC is lost. Electronic warfare and
 * the rest of the role come in later stages (DESIGN.md).
 */
public class CombatInformationCenter extends BaseHullMod {

    public static final String ID = "tco_cic";
    public static final String AUX_ID = "tco_aux_cic";

    public static boolean isCic(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(ID);
    }

    public static boolean isAux(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(AUX_ID);
    }

    /** 2 for a CIC, 1 for an Auxiliary CIC, 0 otherwise. */
    public static int level(ShipAPI ship) {
        return isCic(ship) ? 2 : isAux(ship) ? 1 : 0;
    }

    /** Shared rules for both: not with a stand-alone ESM, AESA or Radar Removal (it has its own radar and ESM). */
    static String conflict(ShipAPI ship) {
        if (ship == null || ship.getVariant() == null) return "No ship";
        if (ship.getVariant().hasHullMod(PassiveSensors.ID)) return "It has its own ESM: remove the Passive Sensor Suite";
        if (ship.getVariant().hasHullMod(RadarCombatPlugin.AESA_ID)) return "It has its own radar: remove the AESA Radar";
        if (ship.getVariant().hasHullMod(RadarRemoval.ID)) return "Needs a radar: remove Radar Removal";
        return null;
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && ship.getHullSize() == HullSize.CAPITAL_SHIP && conflict(ship) == null;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship != null && ship.getHullSize() != HullSize.CAPITAL_SHIP) return "Capital ships only";
        return conflict(ship);
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return ThermalDispersal.times((float) Math.sqrt(TcoSettings.cicRadarPower));
        if (index == 1) return ThermalDispersal.times((float) Math.sqrt(TcoSettings.cicRadarPower * TcoSettings.sectorPowerMult));
        if (index == 2) return Math.round(TcoSettings.sectorArc) + "";
        if (index == 3) return Math.round(TcoSettings.cicRelayMaxSeconds) + "";
        if (index == 4) return Math.round(TcoSettings.aiRelayMaxSeconds) + "";
        if (index == 5) return Math.round(TcoSettings.decapSeconds) + "";
        if (index == 6) return Math.max(1, TcoSettings.cicSpotTargets) + "";
        return null;
    }
}
