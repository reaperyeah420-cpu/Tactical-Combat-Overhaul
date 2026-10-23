package tco.patches;

import patchlib.api.context.BeforeContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.Before;
import patchlib.api.patch.Patch;

/**
 * Skips drawing the player ship's weapon arcs in the RTS view. The arc renderer draws one weapon's arc per call;
 * its other methods (group selection, fading) run as normal, so arcs come back as they were on leaving RTS.
 */
@Patch(target = @ClassMatch(typeName = Patches.ARC_RENDERER))
public class ArcPatch {

    @Before(target = @MethodMatch(parameterTypeNames = Patches.WEAPON, returnTypeName = "void"))
    public static void beforeDrawArc(BeforeContext context) {
        if (Patches.hideArcs && Patches.arcs()) context.skipOriginal(null);
    }
}
