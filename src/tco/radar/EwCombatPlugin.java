package tco.radar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.TcoSettings;
import tco.ai.AiAwareness;
import tco.battle.DeathThroes;
import tco.hullmods.CombatInformationCenter;
import tco.power.PowerCombatPlugin;

/**
 * Electronic warfare beside the radar plugin's own area and missile jamming (RadarCombatPlugin: the ECM Package's noise
 * jammer and ECM, a command ship's barrage and missile defence; each auto / on / off).
 * <ul>
 * <li>Spot jamming: a ship with the ECM Package scrambles one enemy radar, an Auxiliary CIC two, a CIC
 * cicSpotTargets. A scrambled radar only works out to burnThroughFraction of its range (RadarCombatPlugin.sweep).</li>
 * <li>Fire-control disruption (command ships, auto / on / off): enemy ships nearby lose weapon range and accuracy.
 * Auto runs it while the ship has the power for it.</li>
 * <li>Fleet emission control: every radar of the side off; switching it back restores the ones it turned off.</li>
 * </ul>
 * Spot jamming draws power per scrambled radar and fire control while it disrupts someone; a ship that can't pay pauses
 * both for a few seconds. The player's ships are set on the EW panel (CicPanel); AI ships use auto.
 */
public class EwCombatPlugin extends BaseEveryFrameCombatPlugin {

    public static final String ENGINE_KEY = "tco_ewPlugin";
    private static final float THINK_INTERVAL = 0.5f;
    private static final float SUSPEND_SECONDS = 3f;
    private static final String FCD_ID = "tco_fcd";

    private CombatEngineAPI engine;
    private float thinkTimer;
    // Fire control per command ship: 0 auto, 1 on, 2 off
    private final Map<ShipAPI, Integer> fireControlMode = new HashMap<ShipAPI, Integer>();
    // Command ships disrupting someone right now
    private final Set<ShipAPI> fireControlActive = new HashSet<ShipAPI>();
    // Ships with spot jamming switched off
    private final Set<ShipAPI> spotOff = new HashSet<ShipAPI>();
    private final Map<ShipAPI, List<ShipAPI>> spotTargets = new HashMap<ShipAPI, List<ShipAPI>>();
    private final Map<ShipAPI, ShipAPI> scrambledBy = new HashMap<ShipAPI, ShipAPI>();
    private final Map<ShipAPI, Float> suspendedUntil = new HashMap<ShipAPI, Float>();
    private final Set<ShipAPI> fcdAffected = new HashSet<ShipAPI>();
    private final boolean[] emcon = new boolean[2];
    private final Set<ShipAPI> emconSilenced = new HashSet<ShipAPI>();
    // AI emission control against anti-radiation missiles, until
    private final float[] aiEmconUntil = { -1f, -1f };

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
    }

    public static EwCombatPlugin get(CombatEngineAPI engine) {
        Object plugin = engine == null ? null : engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof EwCombatPlugin ? (EwCombatPlugin) plugin : null;
    }

    // ------------------------------------------------------------------ queries

    public static boolean isScrambled(CombatEngineAPI engine, ShipAPI radar) {
        EwCombatPlugin ew = get(engine);
        return ew != null && ew.scrambledBy.containsKey(radar);
    }

    public static List<ShipAPI> spotTargetsOf(CombatEngineAPI engine, ShipAPI jammer) {
        EwCombatPlugin ew = get(engine);
        List<ShipAPI> list = ew == null ? null : ew.spotTargets.get(jammer);
        return list == null ? Collections.<ShipAPI>emptyList() : list;
    }

    /** How many radars this ship can scramble: CIC cicSpotTargets, Auxiliary CIC 2, ECM Package 1. */
    public static int spotCapacity(ShipAPI ship) {
        int level = CombatInformationCenter.level(ship);
        if (level == 2) return Math.max(1, TcoSettings.cicSpotTargets);
        if (level == 1) return 2;
        return ship.getVariant() != null && ship.getVariant().hasHullMod("ecm") ? 1 : 0;
    }

    public static float spotRange(ShipAPI ship) {
        int level = CombatInformationCenter.level(ship);
        return level == 2 ? TcoSettings.spotRangeCic : level == 1 ? TcoSettings.spotRangeAux : TcoSettings.spotRangeEcm;
    }

    public static boolean isSpotOff(CombatEngineAPI engine, ShipAPI ship) {
        EwCombatPlugin ew = get(engine);
        return ew != null && ew.spotOff.contains(ship);
    }

    /** Fire control mode: 0 auto, 1 on, 2 off. */
    public static int fireControlMode(CombatEngineAPI engine, ShipAPI ship) {
        EwCombatPlugin ew = get(engine);
        Integer m = ew == null ? null : ew.fireControlMode.get(ship);
        return m == null ? 0 : m;
    }

    public static boolean fireControlActive(CombatEngineAPI engine, ShipAPI ship) {
        EwCombatPlugin ew = get(engine);
        return ew != null && ew.fireControlActive.contains(ship);
    }

    /** Enemy ships under this command ship's fire-control disruption. */
    public static int disruptedBy(CombatEngineAPI engine, ShipAPI ship) {
        EwCombatPlugin ew = get(engine);
        if (ew == null || !ew.fireControlActive.contains(ship)) return 0;
        int n = 0;
        float range = fireControlRange(ship);
        for (ShipAPI other : ew.fcdAffected) {
            if (other.getOwner() != ship.getOwner() && dist(ship.getLocation(), other.getLocation()) <= range) n++;
        }
        return n;
    }

    public static boolean isEmcon(CombatEngineAPI engine, int side) {
        EwCombatPlugin ew = get(engine);
        return ew != null && side >= 0 && side <= 1 && ew.emcon[side];
    }

    public static boolean isSuspended(CombatEngineAPI engine, ShipAPI ship) {
        EwCombatPlugin ew = get(engine);
        return ew != null && ew.suspended(ship);
    }

    public static float fireControlRange(ShipAPI ship) {
        return CombatInformationCenter.level(ship) == 2 ? TcoSettings.fireControlRangeCic : TcoSettings.fireControlRangeAux;
    }

    // ------------------------------------------------------------------ player controls

    public static void setFireControlMode(CombatEngineAPI engine, ShipAPI ship, int mode) {
        EwCombatPlugin ew = get(engine);
        if (ew == null || CombatInformationCenter.level(ship) == 0) return;
        ew.fireControlMode.put(ship, Math.max(0, Math.min(2, mode)));
        ew.suspendedUntil.remove(ship);
    }

    public static void setSpotJamming(CombatEngineAPI engine, ShipAPI ship, boolean on) {
        EwCombatPlugin ew = get(engine);
        if (ew == null) return;
        if (on) ew.spotOff.remove(ship);
        else {
            ew.spotOff.add(ship);
            ew.release(ship);
        }
    }

    /** Fleet emission control for a side: every radar off, or back on (only those it switched off). */
    public static void setEmcon(CombatEngineAPI engine, int side, boolean on) {
        EwCombatPlugin ew = get(engine);
        if (ew == null || side < 0 || side > 1 || ew.emcon[side] == on) return;
        ew.emcon[side] = on;
        if (!on) {
            for (Iterator<ShipAPI> it = ew.emconSilenced.iterator(); it.hasNext();) {
                ShipAPI ship = it.next();
                if (ship.getOwner() != side) continue;
                RadarCombatPlugin.setSilent(engine, ship, false);
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ the frame

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused() || !TcoSettings.radarEnabled) return;
        if (!TcoSettings.ewEnabled) {
            clearAll();
            return;
        }
        payPower(amount);
        thinkTimer -= amount;
        if (thinkTimer > 0f) return;
        thinkTimer = THINK_INTERVAL;
        aiEmcon();
        applyEmcon();
        pickSpotTargets();
        fireControl();
        cleanUp();
    }

    private boolean suspended(ShipAPI ship) {
        Float until = suspendedUntil.get(ship);
        return until != null && engine.getTotalElapsedTime(false) < until;
    }

    private boolean able(ShipAPI ship) {
        return ship.isAlive() && !ship.isHulk() && !ship.isPhased() && !DeathThroes.isDying(ship)
                && !ship.getFluxTracker().isOverloaded() && engine.isEntityInPlay(ship);
    }

    private boolean playerShip(ShipAPI ship) {
        return ship.getOwner() == 0 && !ship.isAlly();
    }

    /** Spot jamming per radar, fire control while it disrupts; a ship that can't pay pauses for SUSPEND_SECONDS. */
    private void payPower(float amount) {
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        if (power == null) return;
        Set<ShipAPI> payers = new HashSet<ShipAPI>(fireControlActive);
        payers.addAll(spotTargets.keySet());
        for (ShipAPI ship : payers) {
            if (suspended(ship) || !able(ship)) continue;
            float perSecond = fireControlActive.contains(ship) ? TcoSettings.fireControlPowerPerSecond : 0f;
            List<ShipAPI> spots = spotTargets.get(ship);
            if (spots != null) perSecond += TcoSettings.spotJamPowerPerSecond * spots.size();
            float capacity = power.capacityOf(ship);
            if (perSecond <= 0f || capacity <= 0f) continue;
            if (!power.draw(ship, capacity * perSecond * amount)) {
                suspendedUntil.put(ship, engine.getTotalElapsedTime(false) + SUSPEND_SECONDS);
                release(ship);
                fireControlActive.remove(ship);
            }
        }
    }

    private float powerLevel(ShipAPI ship) {
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        return power != null && power.capacityOf(ship) > 0f ? power.powerOf(ship) / power.capacityOf(ship) : 1f;
    }

    /**
     * AI sides with a command ship go quiet while aiEmconArmCount or more anti-radiation missiles home on them, and stay
     * quiet aiEmconSeconds after the last one.
     */
    private void aiEmcon() {
        RadarCombatPlugin radar = RadarCombatPlugin.get(engine);
        if (radar == null || TcoSettings.aiEmconArmCount <= 0) return;
        float now = engine.getTotalElapsedTime(false);
        int player = engine.getPlayerShip() != null ? engine.getPlayerShip().getOwner() : 0;
        for (int side = 0; side <= 1; side++) {
            if (side == player || AiAwareness.commandLevel(engine, side) == 0) continue;
            if (radar.armsInbound(side) >= TcoSettings.aiEmconArmCount) {
                if (!emcon[side]) setEmcon(engine, side, true);
                aiEmconUntil[side] = now + TcoSettings.aiEmconSeconds;
            } else if (emcon[side] && aiEmconUntil[side] > 0f && now > aiEmconUntil[side]) {
                setEmcon(engine, side, false);
                aiEmconUntil[side] = -1f;
            }
        }
    }

    /** Keeps the side's radars off while its emission control is on (new arrivals too). */
    private void applyEmcon() {
        for (int side = 0; side <= 1; side++) {
            if (!emcon[side]) continue;
            for (ShipAPI ship : engine.getShips()) {
                if (ship.getOwner() != side || !ship.isAlive() || ship.isHulk() || ship.isFighter()) continue;
                if (RadarCombatPlugin.isSilent(engine, ship)) continue;
                if (RadarCombatPlugin.setSilent(engine, ship, true)) emconSilenced.add(ship);
            }
        }
    }

    /**
     * Spot jamming: each able jammer picks up to its capacity of enemy radars that are on, in its range, known to it
     * (seen, tracked, reported or heard), not already jammed by another ship: radars that hold one of its side's ships
     * first, then the nearest.
     */
    private void pickSpotTargets() {
        RadarCombatPlugin radar = RadarCombatPlugin.get(engine);
        if (radar == null) return;
        scrambledBy.clear();
        for (ShipAPI jammer : engine.getShips()) {
            int capacity = spotCapacity(jammer);
            if (capacity <= 0) continue;
            if (!able(jammer) || suspended(jammer) || spotOff.contains(jammer)) {
                spotTargets.remove(jammer);
                continue;
            }
            final ShipAPI from = jammer;
            List<ShipAPI> candidates = new ArrayList<ShipAPI>();
            float range = spotRange(jammer);
            for (ShipAPI target : engine.getShips()) {
                if (target.getOwner() == jammer.getOwner() || target.getOwner() < 0 || target.getOwner() > 1) continue;
                if (!radar.isEmitting(target) || scrambledBy.containsKey(target)) continue;
                if (dist(jammer.getLocation(), target.getLocation()) > range) continue;
                if (!AiAwareness.knowsShip(engine, jammer, target) && !radar.hears(jammer.getLocation(), target)) continue;
                candidates.add(target);
            }
            if (candidates.isEmpty()) {
                spotTargets.remove(jammer);
                continue;
            }
            final RadarCombatPlugin r = radar;
            Collections.sort(candidates, (a, b) -> {
                boolean ta = r.holdsAnyOf(a, from.getOwner()), tb = r.holdsAnyOf(b, from.getOwner());
                if (ta != tb) return ta ? -1 : 1;
                return Float.compare(dist(from.getLocation(), a.getLocation()), dist(from.getLocation(), b.getLocation()));
            });
            List<ShipAPI> picked = new ArrayList<ShipAPI>(candidates.subList(0, Math.min(capacity, candidates.size())));
            for (ShipAPI t : picked) scrambledBy.put(t, jammer);
            spotTargets.put(jammer, picked);
        }
    }

    /**
     * Fire-control disruption: command ships on ON, or on AUTO with more than ewAiMinPower, disrupt the known enemies in
     * reach: weapon range and accuracy down. It only runs (and costs power) while someone is in reach.
     */
    private void fireControl() {
        Set<ShipAPI> now = new HashSet<ShipAPI>();
        fireControlActive.clear();
        for (ShipAPI ship : engine.getShips()) {
            if (CombatInformationCenter.level(ship) == 0 || !able(ship) || suspended(ship)) continue;
            Integer m = fireControlMode.get(ship);
            int mode = m == null || !playerShip(ship) ? 0 : m;
            if (mode == 2 || (mode == 0 && powerLevel(ship) <= TcoSettings.ewAiMinPower)) continue;
            float range = fireControlRange(ship);
            boolean any = false;
            for (ShipAPI other : engine.getShips()) {
                if (other.getOwner() == ship.getOwner() || other.getOwner() < 0 || other.getOwner() > 1 || !other.isAlive()
                        || other.isHulk()) {
                    continue;
                }
                if (dist(ship.getLocation(), other.getLocation()) > range) continue;
                if (!AiAwareness.knowsShip(engine, ship, other)) continue;
                now.add(other);
                any = true;
            }
            if (any) fireControlActive.add(ship);
        }
        for (ShipAPI ship : now) {
            if (fcdAffected.add(ship)) disrupt(ship, true);
        }
        for (Iterator<ShipAPI> it = fcdAffected.iterator(); it.hasNext();) {
            ShipAPI ship = it.next();
            if (now.contains(ship)) continue;
            disrupt(ship, false);
            it.remove();
        }
    }

    private static void disrupt(ShipAPI ship, boolean on) {
        MutableShipStatsAPI stats = ship.getMutableStats();
        if (on) {
            float range = Math.max(0.05f, 1f - TcoSettings.fireControlRangePenalty);
            stats.getBallisticWeaponRangeBonus().modifyMult(FCD_ID, range);
            stats.getEnergyWeaponRangeBonus().modifyMult(FCD_ID, range);
            stats.getMissileWeaponRangeBonus().modifyMult(FCD_ID, range);
            stats.getAutofireAimAccuracy().modifyFlat(FCD_ID, -TcoSettings.fireControlAccuracyPenalty);
            stats.getRecoilPerShotMult().modifyMult(FCD_ID, 1f + TcoSettings.fireControlRecoilPenalty);
            stats.getMaxRecoilMult().modifyMult(FCD_ID, 1f + TcoSettings.fireControlRecoilPenalty);
        } else {
            stats.getBallisticWeaponRangeBonus().unmodify(FCD_ID);
            stats.getEnergyWeaponRangeBonus().unmodify(FCD_ID);
            stats.getMissileWeaponRangeBonus().unmodify(FCD_ID);
            stats.getAutofireAimAccuracy().unmodify(FCD_ID);
            stats.getRecoilPerShotMult().unmodify(FCD_ID);
            stats.getMaxRecoilMult().unmodify(FCD_ID);
        }
    }

    private void release(ShipAPI jammer) {
        List<ShipAPI> spots = spotTargets.remove(jammer);
        if (spots == null) return;
        for (ShipAPI t : spots) {
            if (scrambledBy.get(t) == jammer) scrambledBy.remove(t);
        }
    }

    private void cleanUp() {
        fireControlMode.keySet().removeIf(s -> !engine.isEntityInPlay(s) || !s.isAlive());
        spotOff.removeIf(s -> !engine.isEntityInPlay(s));
        suspendedUntil.keySet().removeIf(s -> !engine.isEntityInPlay(s));
        emconSilenced.removeIf(s -> !engine.isEntityInPlay(s));
        fcdAffected.removeIf(s -> !engine.isEntityInPlay(s));
    }

    private void clearAll() {
        if (spotTargets.isEmpty() && fcdAffected.isEmpty() && !emcon[0] && !emcon[1]) return;
        for (ShipAPI ship : fcdAffected) disrupt(ship, false);
        fcdAffected.clear();
        fireControlActive.clear();
        spotTargets.clear();
        scrambledBy.clear();
        setEmcon(engine, 0, false);
        setEmcon(engine, 1, false);
    }

    static float dist(Vector2f a, Vector2f b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
