package tco.fob;

import java.util.List;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.BaseBattleObjectiveEffect;

/** The objective does nothing itself; FobCombatPlugin and AmmoCombatPlugin do the work. */
public class SupplyFobEffect extends BaseBattleObjectiveEffect {

    @Override
    public void advance(float amount) {
    }

    @Override
    public List<ShipStatusItem> getStatusItemsFor(ShipAPI ship) {
        return null;
    }

    @Override
    public String getLongDescription() {
        return "Capturing this sets up a Supply FOB behind your lines: a munitions depot that resupplies"
                + " nearby friendly ships and restocks resupply ships. The FOB stays yours until it is destroyed."
                + " While you hold the array and already have a FOB, the FOB's stock slowly refills.";
    }
}
