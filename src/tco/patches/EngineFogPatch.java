package tco.patches;

import com.fs.starfarer.api.combat.FogOfWarAPI;

import patchlib.api.context.AfterContext;
import patchlib.api.context.BeforeContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.After;
import patchlib.api.patch.Before;
import patchlib.api.patch.Patch;

import tco.rts.SideSight;

/**
 * The engine's fog (one per side; what the AI knows, and the player side's HUD) answers with TCO's sight instead of
 * its 500-unit tiles. Every visibility check ends in isVisible(x, y). Reveals by the game and other mods are recorded
 * so they still count.
 */
@Patch(target = @ClassMatch(typeName = Patches.ENGINE_FOG))
public class EngineFogPatch {

    @After(target = @MethodMatch(methodName = "isVisible", parameterTypeNames = {"float", "float"}))
    public static void afterIsVisible(AfterContext context) {
        if (!Patches.aiFog()) return;
        int side = ((FogOfWarAPI) context.getSelf()).getPlayerId();
        context.setReturnValue(SideSight.sees(side, (Float) context.getArg(0), (Float) context.getArg(1)));
    }

    @After(target = @MethodMatch(methodName = "revealAroundPoint"))
    public static void afterReveal(AfterContext context) {
        if (!Patches.aiFog()) return;
        SideSight.revealed(((FogOfWarAPI) context.getSelf()).getPlayerId(), (Float) context.getArg(1),
                (Float) context.getArg(2), (Float) context.getArg(3));
    }

    @Before(target = @MethodMatch(methodName = "updateVisibility"))
    public static void beforeUpdate(BeforeContext context) {
        if (!Patches.aiFog()) return;
        SideSight.rebuilding(((FogOfWarAPI) context.getSelf()).getPlayerId());
    }
}
