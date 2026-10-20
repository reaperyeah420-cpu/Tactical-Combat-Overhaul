package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/** IRCCM Package: IR missiles hard to decoy. The effect lives in IrCombatPlugin. */
public class Irccm extends BaseHullMod {

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round(TcoSettings.irccmChanceCold * 100f) + "%";
        if (index == 1) return String.valueOf(TcoSettings.irccmBlanketStart);
        return null;
    }
}
