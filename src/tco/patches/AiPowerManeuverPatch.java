package tco.patches;

import patchlib.api.context.MethodCallContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.Patch;
import patchlib.api.patch.RedirectMethodCall;

/** Power-aware flux reads in the AI's manoeuvres (strafing, escorting, retreating). */
@Patch(target = @ClassMatch(packageName = "com.fs.starfarer.combat.ai.movement.maneuvers"))
public class AiPowerManeuverPatch {

    @RedirectMethodCall(call = @MethodMatch(methodName = "getFluxLevel", parameterCount = 0),
            owner = @ClassMatch(typeName = Patches.SHIP))
    public static void fluxLevel(MethodCallContext context) {
        AiPower.fluxLevel(context);
    }
}
