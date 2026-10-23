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
        return "Capture it to set up a Supply FOB: a munitions depot that rearms nearby friendly ships and restocks"
                + " supply ships. It's yours until it's destroyed, and its stock slowly refills while you hold the array.";
    }
}
