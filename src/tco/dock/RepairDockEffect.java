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
        return "Capture it to set up a Repair Dock: an unarmed, heavily armored station whose repair drones fix"
                + " reactors, weapons, engines and armor on nearby friendly ships. If the dock is destroyed, a new one"
                + " goes up after a while as long as you hold the beacon.";
    }
}
