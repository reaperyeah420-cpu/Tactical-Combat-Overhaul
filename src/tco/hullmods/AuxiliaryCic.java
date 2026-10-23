package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/** Auxiliary CIC (cruisers): a smaller Combat Information Center that takes over when the CIC is lost. */
public class AuxiliaryCic extends BaseHullMod {

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && ship.getHullSize() == HullSize.CRUISER && CombatInformationCenter.conflict(ship) == null;
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        if (ship != null && ship.getHullSize() != HullSize.CRUISER) return "Cruisers only";
        return CombatInformationCenter.conflict(ship);
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return ThermalDispersal.times((float) Math.sqrt(TcoSettings.auxCicRadarPower));
        if (index == 1) return ThermalDispersal.times((float) Math.sqrt(TcoSettings.auxCicRadarPower * TcoSettings.sectorPowerMult));
        if (index == 2) return Math.round(TcoSettings.auxCicRelayMaxSeconds) + "";
        if (index == 3) return Math.round(TcoSettings.auxTakeoverSeconds) + "";
        return null;
    }
}
