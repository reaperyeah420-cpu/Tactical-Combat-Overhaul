package tco.patches;

import patchlib.api.context.BeforeContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.Before;
import patchlib.api.patch.Patch;

import tco.rts.FogOfWar;

/**
 * Fog of war: skips drawing what the player's side can't see. Covers ships (after their fade-out, so shields,
 * weapon glows and decals go too), projectiles, missiles, explosions, EMP arcs and flares.
 */
@Patch(target = @ClassMatch(subtypeName = Patches.BASE_ENTITY))
public class FogEntityPatch {

    @Before(target = @MethodMatch(methodName = "render", parameterTypeNames = {Patches.LAYERS, Patches.VIEWPORT}))
    public static void beforeRender(BeforeContext context) {
        if (FogOfWar.isActive() && Patches.fog() && FogOfWar.hidesRender(context.getSelf())) context.skipOriginal(null);
    }
}
