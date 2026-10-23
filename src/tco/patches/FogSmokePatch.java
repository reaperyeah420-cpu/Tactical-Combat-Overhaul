package tco.patches;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.ViewportAPI;

import patchlib.api.context.MethodCallContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.Patch;
import patchlib.api.patch.RedirectMethodCall;

import tco.rts.FogOfWar;

/**
 * Fog of war for damage smoke (burning hulls and engines). The emitter only spawns a puff near the screen, so a
 * puff in the fog counts as off screen.
 */
@Patch(target = @ClassMatch(typeName = Patches.SMOKE_EMITTER))
public class FogSmokePatch {

    @RedirectMethodCall(target = @MethodMatch(methodName = "emit"),
            call = @MethodMatch(methodName = "isNearViewport", parameterTypeNames = {Patches.VECTOR, "float"}),
            owner = @ClassMatch(typeName = Patches.VIEWPORT))
    public static void nearViewport(MethodCallContext context) {
        nearAndVisible(context);
    }

    static void nearAndVisible(MethodCallContext context) {
        Vector2f at = (Vector2f) context.getCallArg(0);
        float slack = (Float) context.getCallArg(1);
        boolean near = ((ViewportAPI) context.getCallReceiver()).isNearViewport(at, slack);
        if (near && FogOfWar.isActive() && Patches.fog() && !FogOfWar.isVisibleView(at, slack)) near = false;
        context.setResult(near);
    }
}
