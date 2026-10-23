package tco.patches;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;

import patchlib.api.context.BeforeContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.Before;
import patchlib.api.patch.Patch;

import tco.rts.FogOfWar;

/**
 * Fog of war for explosions, sparks, smoke and debris: the game and mods spawn them through these engine calls,
 * all with the location first. Ones the player's side can't see are not spawned.
 */
@Patch(target = @ClassMatch(subtype = CombatEngineAPI.class))
public class FogEffectsPatch {

    private static final float SLACK = 100f;

    private static void check(BeforeContext context) {
        if (!FogOfWar.isActive() || !Patches.fog()) return;
        Object at = context.getArg(0);
        if (at instanceof Vector2f && !FogOfWar.isVisibleView((Vector2f) at, SLACK)) context.skipOriginal(null);
    }

    @Before(target = @MethodMatch(methodName = "spawnExplosion"))
    public static void explosion(BeforeContext context) {
        check(context);
    }

    @Before(target = @MethodMatch(methodName = "addHitParticle"))
    public static void hit(BeforeContext context) {
        check(context);
    }

    @Before(target = @MethodMatch(methodName = "addSmoothParticle"))
    public static void smooth(BeforeContext context) {
        check(context);
    }

    @Before(target = @MethodMatch(methodName = "addSmokeParticle"))
    public static void smoke(BeforeContext context) {
        check(context);
    }

    @Before(target = @MethodMatch(methodName = "addNegativeParticle"))
    public static void negative(BeforeContext context) {
        check(context);
    }

    @Before(target = @MethodMatch(methodName = "addNebulaParticle"))
    public static void nebula(BeforeContext context) {
        check(context);
    }

    @Before(target = @MethodMatch(methodName = "addNebulaSmoothParticle"))
    public static void nebulaSmooth(BeforeContext context) {
        check(context);
    }

    @Before(target = @MethodMatch(methodName = "addNebulaSmokeParticle"))
    public static void nebulaSmoke(BeforeContext context) {
        check(context);
    }

    @Before(target = @MethodMatch(methodName = "addSwirlyNebulaParticle"))
    public static void swirly(BeforeContext context) {
        check(context);
    }

    @Before(target = @MethodMatch(methodName = "addNegativeNebulaParticle"))
    public static void negativeNebula(BeforeContext context) {
        check(context);
    }

    @Before(target = @MethodMatch(methodName = "addNegativeSwirlyNebulaParticle"))
    public static void negativeSwirly(BeforeContext context) {
        check(context);
    }

    @Before(target = @MethodMatch(methodName = "spawnDebrisSmall"))
    public static void debrisSmall(BeforeContext context) {
        check(context);
    }

    @Before(target = @MethodMatch(methodName = "spawnDebrisMedium"))
    public static void debrisMedium(BeforeContext context) {
        check(context);
    }

    @Before(target = @MethodMatch(methodName = "spawnDebrisLarge"))
    public static void debrisLarge(BeforeContext context) {
        check(context);
    }
}
