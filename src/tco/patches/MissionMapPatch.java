package tco.patches;

import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.api.mission.MissionDefinitionPlugin;

import patchlib.api.context.MethodCallContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.match.MethodType;
import patchlib.api.patch.Patch;
import patchlib.api.patch.RedirectMethodCall;

import tco.TcoSettings;
import tco.battle.ScalingLoader;

/**
 * Missions: the game builds a mission's battle by handing its definition loader to the mission's defineMission.
 * The loader is wrapped in the same coordinate scaler campaign battles use. Campaign battles build their loader
 * with the other constructor, so they are never scaled twice.
 */
@Patch(target = @ClassMatch(typeName = Patches.LOADER))
public class MissionMapPatch {

    @RedirectMethodCall(
            target = @MethodMatch(methodType = MethodType.CONSTRUCTOR, parameterTypeNames = Patches.MISSION_SPEC),
            call = @MethodMatch(methodName = "defineMission"))
    public static void scaleMission(MethodCallContext context) {
        MissionDefinitionPlugin mission = (MissionDefinitionPlugin) context.getCallReceiver();
        MissionDefinitionAPI api = (MissionDefinitionAPI) context.getCallArg(0);
        if (Patches.missionMaps() && TcoSettings.mapSizeMult != 1f) api = new ScalingLoader(api, TcoSettings.mapSizeMult);
        mission.defineMission(api);
    }
}
