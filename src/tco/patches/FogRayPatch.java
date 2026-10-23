package tco.patches;

import patchlib.api.context.BeforeContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.Before;
import patchlib.api.patch.Patch;

import tco.rts.FogOfWar;

/** Fog of war for beams and ray shots, which have their own base class. */
@Patch(target = @ClassMatch(subtypeName = Patches.RAY))
public class FogRayPatch {

    @Before(target = @MethodMatch(methodName = "render", parameterTypeNames = {Patches.LAYERS, Patches.VIEWPORT}))
    public static void beforeRender(BeforeContext context) {
        if (FogOfWar.isActive() && Patches.fog() && FogOfWar.hidesRender(context.getSelf())) context.skipOriginal(null);
    }
}
