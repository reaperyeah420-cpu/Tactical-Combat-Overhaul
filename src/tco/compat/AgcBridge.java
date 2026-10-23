package tco.compat;

import java.util.List;

import com.dp.advancedgunnerycontrol.typesandvalues.ShipModesKt;
import com.dp.advancedgunnerycontrol.typesandvalues.Values;
import com.fs.starfarer.api.combat.ShipAPI;

/** The only class using Advanced Gunnery Control's own code; loaded only when AGC is enabled. */
final class AgcBridge {

    private AgcBridge() {
    }

    /** AGC wraps the ship's current AI with the ship's saved modes; true if it put a new wrapper on. */
    static boolean wrap(ShipAPI ship) {
        List<String> modes = ShipModesKt.loadShipModes(ship, Values.INSTANCE.getStorageIndex());
        if (modes == null || modes.isEmpty()) return false;
        Object before = AgcCompat.wrapper(ship);
        ShipModesKt.assignShipModes(modes, ship, false);
        Object after = AgcCompat.wrapper(ship);
        return after != null && after != before;
    }
}
