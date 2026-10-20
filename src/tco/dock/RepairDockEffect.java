package tco.dock;

import java.util.List;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.BaseBattleObjectiveEffect;

/** The beacon does nothing itself; RepairDockCombatPlugin does the work. */
public class RepairDockEffect extends BaseBattleObjectiveEffect {

    @Override
    public void advance(float amount) {
    }

    @Override
    public List<ShipStatusItem> getStatusItemsFor(ShipAPI ship) {
        return null;
    }

    @Override
    public String getLongDescription() {
        return "Capturing this sets up a Repair Dock behind your lines: an unarmed, heavily armoured station"
                + " with a large fleet of repair drones. Like a fleet tender, it sends them to friendly ships"
                + " within its reach to restart reactors and fix destroyed weapons, engines and armor."
                + " A destroyed dock is replaced after a while if you still hold the beacon.";
    }
}
