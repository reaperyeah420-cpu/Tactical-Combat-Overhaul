package tco.patches;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;

import patchlib.api.context.AfterContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.After;
import patchlib.api.patch.Patch;

/** Logs once that PatchLib is applying TCO's patches. */
@Patch(target = @ClassMatch(subtype = CombatEngineAPI.class))
public class PatchCheck {

    private static boolean logged;

    @After(target = @MethodMatch(methodName = "getMapWidth"))
    public static void afterGetMapWidth(AfterContext context) {
        if (logged) return;
        logged = true;
        Global.getLogger(PatchCheck.class).info("TCO: PatchLib patches active (map width " + context.getReturnValue() + ")");
    }
}
