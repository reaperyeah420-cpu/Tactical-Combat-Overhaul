package tco.fob;

import com.fs.starfarer.api.combat.ShipAPI;

public final class SupplyFob {

    public static final String OBJECTIVE_ID = "tco_supply_fob";
    public static final String HULL_ID = "tco_supply_fob";
    public static final String MODULE_HULL_ID = "tco_fob_module";
    public static final String DEFAULT_VARIANT_ID = "tco_supply_fob_Standard";
    public static final String NAME = "Supply FOB";

    private SupplyFob() {
    }

    /** The core: holds the stock, its loss destroys the FOB. */
    public static boolean isFob(ShipAPI ship) {
        return ship != null && ship.getHullSpec() != null && HULL_ID.equals(ship.getHullSpec().getBaseHullId());
    }

    public static boolean isFobPart(ShipAPI ship) {
        if (ship == null) return false;
        if (isFob(ship) || isFob(ship.getParentStation())) return true;
        return ship.getHullSpec() != null && MODULE_HULL_ID.equals(ship.getHullSpec().getBaseHullId());
    }
}
