package tco.patches;

import com.fs.starfarer.api.combat.ShipAPI;

import patchlib.api.context.BeforeContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.Before;
import patchlib.api.patch.Patch;

import tco.ammo.AmmoRules;
import tco.radar.RadarCombatPlugin;

/**
 * Keeps TCO's own craft out of the combat kill feed. The feed (the combat message widget, `class.C`) posts
 * "<name> (<class>) destroyed" from `shipDestroyed(Ship)` for every ship but fighters and station modules. Boarding
 * and supply shuttles, escape pods, rescue shuttles, repair drones, ejection pods and the RTS helpers are ships too,
 * all marked with the shuttle or helper key, and are skipped here.
 */
@Patch(target = @ClassMatch(typeName = Patches.KILL_FEED))
public class KillFeedPatch {

    @Before(target = @MethodMatch(methodName = "shipDestroyed", parameterTypeNames = Patches.SHIP))
    public static void beforeShipDestroyed(BeforeContext context) {
        if (!Patches.killFeed()) return;
        Object[] args = context.getArgs();
        if (args == null) return;
        for (Object a : args) {
            if (a instanceof ShipAPI && isCraft((ShipAPI) a)) {
                context.skipOriginal(null);
                return;
            }
        }
    }

    static boolean isCraft(ShipAPI ship) {
        return ship.getCustomData().containsKey(AmmoRules.SHUTTLE_KEY)
                || ship.getCustomData().containsKey(RadarCombatPlugin.HELPER_KEY);
    }
}
