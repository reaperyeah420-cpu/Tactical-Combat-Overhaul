package tco.patches;

import patchlib.api.context.MethodCallContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.Patch;
import patchlib.api.patch.RedirectMethodCall;

/** Power-aware flux reads in the AI's autofire (which groups may fire). */
@Patch(target = @ClassMatch(typeName = "com.fs.starfarer.combat.ai.attack.AutofireManagerV2"))
public class AiPowerAutofirePatch {

    @RedirectMethodCall(call = @MethodMatch(methodName = "getFluxLevel", parameterCount = 0),
            owner = @ClassMatch(typeName = Patches.SHIP))
    public static void fluxLevel(MethodCallContext context) {
        AiPower.fluxLevel(context);
    }
}
