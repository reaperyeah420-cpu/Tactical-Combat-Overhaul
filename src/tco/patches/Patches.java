package tco.patches;

import tco.TcoSettings;

/**
 * Which PatchLib patches can run. Holds no PatchLib types, so the rest of TCO can ask it with PatchLib absent:
 * everything is then off and each feature stays on its old API code path. PatchLibSetup fills it in at load.
 */
public final class Patches {

    static final String ARC_RENDERER = "com.fs.starfarer.renderers.super";
    static final String WEAPON = "com.fs.starfarer.combat.systems.o00O";
    static final String BASE_ENTITY = "com.fs.starfarer.combat.entities.BaseEntity";
    static final String RAY = "com.fs.starfarer.combat.entities.float";
    static final String LAYERS = "com.fs.starfarer.api.combat.CombatEngineLayers";
    static final String VIEWPORT = "com.fs.starfarer.combat.CombatViewport";
    static final String STATUS_OVERLAY = "com.fs.starfarer.renderers.A.null";
    static final String SHIP = "com.fs.starfarer.combat.entities.Ship";
    static final String VECTOR = "org.lwjgl.util.vector.Vector2f";
    static final String TOOLTIPS = "com.fs.starfarer.ui.impl.CargoTooltipFactory";
    static final String WEAPON_SPEC = "com.fs.starfarer.loading.specs.BaseWeaponSpec";
    static final String CHARACTER = "com.fs.starfarer.campaign.CharacterStats";
    static final String SHIP_STATS = "com.fs.starfarer.combat.entities.ship.o0OO";
    static final String TOOLTIP_MODE = "com.fs.starfarer.ui.impl.CargoTooltipFactory$o";
    static final String TOOLTIP = "com.fs.starfarer.ui.impl.StandardTooltipV2Expandable";
    static final String ENGINE_FOG = "com.fs.starfarer.combat.new.T";
    static final String LOADER = "com.fs.starfarer.title.Object.L";
    static final String MISSION_SPEC = "com.fs.starfarer.title.Object.Oo0O";
    static final String SIMULATOR = "com.fs.starfarer.coreui.refit.Q";
    static final String SMOKE_EMITTER = "com.fs.starfarer.renderers.fx.SmokeEmitter";
    static final String CONTRAIL_EMITTER = "com.fs.starfarer.combat.entities.ContrailEmitter";
    static final String REFIT_STATS = "com.fs.starfarer.coreui.refit.ooOO";
    static final String KILL_FEED = "com.fs.starfarer.class.C";
    static final String RETREAT = "com.fs.starfarer.combat.ai.movement.maneuvers.RetreatManeuver";

    static boolean arcsFound;
    static boolean fogFound;
    static boolean overlayFound;
    static boolean tooltipFound;
    static boolean engineFogFound;
    static boolean missionFound;
    static boolean simulatorFound;
    static boolean aiPowerFound;
    static boolean refitPowerFound;
    static boolean killFeedFound;
    static boolean retreatFound;
    // The game's own fog setting: with it off the engine reveals the whole map, and that stays so
    static boolean gameFog = true;

    /** Set once the tooltip patch has had ship stats to work with (until then the polling script stays on). */
    public static volatile boolean refitCardsDelivered;

    /** Set by the RTS plugin each frame: the RTS view is up and no ship is piloted. */
    public static volatile boolean hideArcs;

    private Patches() {
    }

    /** Weapon arcs hidden by the patch, so RTS mode needs no command pod. */
    public static boolean arcs() {
        return arcsFound && TcoSettings.patchWeaponArcs;
    }

    /** Fog of war also hides shots, beams, explosions and hidden ships' status overlays. */
    public static boolean fog() {
        return fogFound && TcoSettings.patchFog;
    }

    public static boolean overlay() {
        return overlayFound && TcoSettings.patchFog;
    }

    /** The engine's fog (the AI's knowledge) uses TCO's sight circles and radar instead of its tiles. */
    public static boolean aiFog() {
        return engineFogFound && gameFog && TcoSettings.patchAiFog;
    }

    /** Missions get campaign-sized maps. */
    public static boolean missionMaps() {
        return missionFound && TcoSettings.patchMissionMaps;
    }

    /** The game's AI reads low power as high flux. */
    public static boolean aiPower() {
        return aiPowerFound && TcoSettings.patchAiPower && TcoSettings.powerEnabled;
    }

    public static boolean simulatorMaps() {
        return simulatorFound && TcoSettings.patchSimulatorMaps;
    }

    /** Refit weapon cards come from the tooltip hook, not the polling script. */
    public static boolean refitCards() {
        return tooltipFound && TcoSettings.patchRefitCards;
    }

    /** TCO's craft are left out of the combat kill feed. */
    public static boolean killFeed() {
        return killFeedFound && TcoSettings.patchKillFeed;
    }

    /** Power rows in the refit screen's ship stats. */
    public static boolean refitPower() {
        return refitPowerFound && TcoSettings.patchRefitPower;
    }

    /** Ships can only retreat in a strip along their map edge. */
    public static boolean retreatEdge() {
        return retreatFound && TcoSettings.patchRetreatEdge;
    }

    /** Ship-changed card values are coloured instead of marked "(+N%)". */
    public static boolean cardColours() {
        return tooltipFound && TcoSettings.patchCardColours;
    }
}
