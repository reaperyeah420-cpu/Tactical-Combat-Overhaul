package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import tco.TcoSettings;
import tco.repair.RepairCombatPlugin;

/** Fleet tender; see RepairCombatPlugin. */
public class RepairTenderBay extends BaseHullMod {

    private static final HullSize[] SIZES = {
        HullSize.FRIGATE, HullSize.DESTROYER, HullSize.CRUISER, HullSize.CAPITAL_SHIP
    };

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index >= 0 && index < 4) return "" + RepairCombatPlugin.dronesFor(SIZES[index]);
        if (index >= 4 && index < 8) return "" + Math.round(RepairCombatPlugin.partsFor(SIZES[index - 4]));
        if (index == 8) return Math.round(TcoSettings.armorRepairCap * 100f) + "%";
        return null;
    }

    // descriptions only take ten params, so the drone reach goes here
    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width,
            boolean isForModSpec) {
        String[] ranges = new String[SIZES.length];
        for (int i = 0; i < SIZES.length; i++) ranges[i] = "" + Math.round(RepairCombatPlugin.rangeFor(SIZES[i]));
        tooltip.addPara("Drone reach: %s/%s/%s/%s units, depending on hull size.", 10f, Misc.getHighlightColor(), ranges);
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return SupportBays.applicable(ship, SupportBays.REPAIR);
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return SupportBays.reason(ship, SupportBays.REPAIR);
    }
}
