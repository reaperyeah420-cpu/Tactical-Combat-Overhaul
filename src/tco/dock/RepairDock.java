package tco.dock;

import com.fs.starfarer.api.combat.ShipAPI;

public final class RepairDock {

    public static final String OBJECTIVE_ID = "tco_repair_dock";
    public static final String HULL_ID = "tco_repair_dock";
    public static final String VARIANT_ID = "tco_repair_dock_Standard";
    public static final String NAME = "Repair Dock";

    private RepairDock() {
    }

    public static boolean isDock(ShipAPI ship) {
        return ship != null && ship.getHullSpec() != null && HULL_ID.equals(ship.getHullSpec().getBaseHullId());
    }
}
