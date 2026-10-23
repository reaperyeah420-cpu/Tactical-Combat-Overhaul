package tco.patches;

import patchlib.api.context.MethodCallContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.Patch;
import patchlib.api.patch.RedirectMethodCall;

/** Fog of war for engine smoke and contrails, the same way as FogSmokePatch. */
@Patch(target = @ClassMatch(typeName = Patches.CONTRAIL_EMITTER))
public class FogContrailPatch {

    @RedirectMethodCall(target = @MethodMatch(methodName = "emit"),
            call = @MethodMatch(methodName = "isNearViewport", parameterTypeNames = {Patches.VECTOR, "float"}),
            owner = @ClassMatch(typeName = Patches.VIEWPORT))
    public static void nearViewport(MethodCallContext context) {
        FogSmokePatch.nearAndVisible(context);
    }
}
