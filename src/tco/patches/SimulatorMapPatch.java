package tco.patches;

import com.fs.starfarer.api.mission.MissionDefinitionAPI;

import patchlib.api.context.MethodCallContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.Patch;
import patchlib.api.patch.RedirectMethodCall;

import tco.TcoSettings;

/**
 * The refit simulator builds its battle itself (a 20000-unit square with nebulas and an asteroid field) instead of
 * through a mission. Its map calls are scaled like a campaign battle's.
 */
@Patch(target = @ClassMatch(typeName = Patches.SIMULATOR))
public class SimulatorMapPatch {

    private static float mult() {
        return Patches.simulatorMaps() ? TcoSettings.mapSizeMult : 1f;
    }

    @RedirectMethodCall(
            target = @MethodMatch(returnTypeName = Patches.LOADER, parameterCount = 0),
            call = @MethodMatch(methodName = "initMap", parameterTypeNames = {"float", "float", "float", "float"}),
            owner = @ClassMatch(typeName = Patches.LOADER))
    public static void initMap(MethodCallContext context) {
        float m = mult();
        ((MissionDefinitionAPI) context.getCallReceiver()).initMap((Float) context.getCallArg(0) * m,
                (Float) context.getCallArg(1) * m, (Float) context.getCallArg(2) * m, (Float) context.getCallArg(3) * m);
    }

    @RedirectMethodCall(
            target = @MethodMatch(returnTypeName = Patches.LOADER, parameterCount = 0),
            call = @MethodMatch(methodName = "addNebula", parameterTypeNames = {"float", "float", "float"}),
            owner = @ClassMatch(typeName = Patches.LOADER))
    public static void addNebula(MethodCallContext context) {
        float m = mult();
        ((MissionDefinitionAPI) context.getCallReceiver()).addNebula((Float) context.getCallArg(0) * m,
                (Float) context.getCallArg(1) * m, (Float) context.getCallArg(2) * m);
    }

    @RedirectMethodCall(
            target = @MethodMatch(returnTypeName = Patches.LOADER, parameterCount = 0),
            call = @MethodMatch(methodName = "addAsteroidField",
                    parameterTypeNames = {"float", "float", "float", "float", "float", "float", "int"}),
            owner = @ClassMatch(typeName = Patches.LOADER))
    public static void addAsteroidField(MethodCallContext context) {
        float m = mult();
        ((MissionDefinitionAPI) context.getCallReceiver()).addAsteroidField((Float) context.getCallArg(0) * m,
                (Float) context.getCallArg(1) * m, (Float) context.getCallArg(2), (Float) context.getCallArg(3) * m,
                (Float) context.getCallArg(4), (Float) context.getCallArg(5),
                Math.round((Integer) context.getCallArg(6) * m));
    }
}
