package tco.patches;

import java.util.List;

import com.fs.starfarer.api.Global;

import patchlib.api.PatchLib;
import patchlib.api.data.ClassData;
import patchlib.api.match.MethodType;
import patchlib.api.query.ClassQuery;
import patchlib.api.query.MethodQuery;

/** Looks up each patch target through PatchLib and records what was found in Patches. */
public final class PatchLibSetup {

    private PatchLibSetup() {
    }

    /** At application load, while PatchLib can still scan. Only called with PatchLib enabled. */
    public static void init() {
        Patches.arcsFound = has(ClassQuery.create().typeName(Patches.ARC_RENDERER).hasMethod(MethodQuery.create()
                .parameterTypeNames(Patches.WEAPON).returnTypeName("void")), "weapon arcs");
        Patches.fogFound = has(ClassQuery.create().typeName(Patches.BASE_ENTITY).hasMethod(MethodQuery.create()
                .methodName("render").parameterTypeNames(Patches.LAYERS, Patches.VIEWPORT)), "fog of war (entities)")
                && has(ClassQuery.create().typeName("com.fs.starfarer.combat.entities.BeamWeaponRay").hasMethod(MethodQuery.create()
                .methodName("render").parameterTypeNames(Patches.LAYERS, Patches.VIEWPORT)), "fog of war (beams)");
        // Smoke is optional: without it the rest of the fog patch still runs
        has(ClassQuery.create().typeName(Patches.SMOKE_EMITTER).hasMethod(MethodQuery.create().methodName("emit")), "fog of war (damage smoke)");
        has(ClassQuery.create().typeName(Patches.CONTRAIL_EMITTER).hasMethod(MethodQuery.create().methodName("emit")), "fog of war (engine smoke)");
        Patches.overlayFound = has(ClassQuery.create().typeName(Patches.STATUS_OVERLAY).hasMethod(MethodQuery.create()
                .parameterTypeNames("float", "float", Patches.VECTOR, "boolean", Patches.VECTOR)), "fog of war (status overlay)");
        Patches.tooltipFound = has(ClassQuery.create().typeName(Patches.TOOLTIPS).hasMethod(MethodQuery.create()
                .parameterTypeNames(Patches.WEAPON_SPEC, Patches.CHARACTER, Patches.SHIP_STATS, Patches.TOOLTIP_MODE)), "weapon tooltips");
        Patches.engineFogFound = has(ClassQuery.create().typeName(Patches.ENGINE_FOG).hasMethod(MethodQuery.create()
                .methodName("isVisible").parameterTypeNames("float", "float")).hasMethod(MethodQuery.create()
                .methodName("updateVisibility")), "engine fog");
        Patches.missionFound = has(ClassQuery.create().typeName(Patches.LOADER).hasMethod(MethodQuery.create()
                .methodType(MethodType.CONSTRUCTOR).parameterTypeNames(Patches.MISSION_SPEC)), "mission maps");
        Patches.simulatorFound = has(ClassQuery.create().typeName(Patches.SIMULATOR).hasMethod(MethodQuery.create()
                .returnTypeName(Patches.LOADER).parameterCount(0)), "simulator maps");
        Patches.aiPowerFound = has(ClassQuery.create().typeName("com.fs.starfarer.combat.ai.attack.AttackAIModule"), "AI power")
                && has(ClassQuery.create().typeName("com.fs.starfarer.combat.ai.attack.AutofireManagerV2"), "AI power (autofire)");
        Patches.refitPowerFound = has(ClassQuery.create().typeName(Patches.REFIT_STATS).hasMethod(MethodQuery.create()
                .methodName("syncWithVariant").parameterCount(1)), "refit power stats");
        Patches.killFeedFound = has(ClassQuery.create().typeName(Patches.KILL_FEED).hasMethod(MethodQuery.create()
                .methodName("shipDestroyed").parameterTypeNames(Patches.SHIP)), "kill feed");
        Patches.retreatFound = has(ClassQuery.create().typeName(Patches.RETREAT).hasMethod(MethodQuery.create()
                .methodName("o00000").returnTypeName("boolean").parameterCount(3)).hasMethod(MethodQuery.create()
                .methodName("o00000").returnTypeName("float").parameterCount(2)), "retreat zone");
        try {
            Patches.gameFog = Global.getSettings().getBoolean("fogOfWar");
        } catch (RuntimeException e) {
            Patches.gameFog = true;
        }
    }

    private static boolean has(ClassQuery query, String what) {
        try {
            List<ClassData> found = PatchLib.scan(query.build());
            if (!found.isEmpty()) return true;
            Global.getLogger(PatchLibSetup.class).warn("TCO: PatchLib target for " + what + " not found (game update?); using the old method");
        } catch (Throwable e) {
            Global.getLogger(PatchLibSetup.class).warn("TCO: PatchLib scan for " + what + " failed; using the old method", e);
        }
        return false;
    }

}
