package tco.patches;

import patchlib.api.context.MethodCallContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.Patch;
import patchlib.api.patch.RedirectMethodCall;

import tco.TcoSettings;

/**
 * The game lets a retreating ship leave once it is within 2000 of its map edge (and facing it, with a clear way
 * out); the player ship's retreat key asks the same. Its distance to the edge is stretched so that 2000 falls at
 * retreatEdgeDistance instead: the retreat zone becomes a strip along the edge.
 */
@Patch(target = @ClassMatch(typeName = Patches.RETREAT))
public class RetreatEdgePatch {

    private static final float VANILLA_REACH = 2000f;

    @RedirectMethodCall(
            target = @MethodMatch(methodName = "o00000", returnTypeName = "boolean", parameterCount = 3),
            call = @MethodMatch(methodName = "o00000", returnTypeName = "float", parameterCount = 2),
            owner = @ClassMatch(typeName = Patches.RETREAT))
    public static void edgeDistance(MethodCallContext context) {
        float distance = (Float) context.call();
        if (Patches.retreatEdge() && distance > 0f) {
            distance *= VANILLA_REACH / Math.max(50f, TcoSettings.retreatEdgeDistance);
        }
        context.setResult(distance);
    }
}
