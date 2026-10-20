package tco.radar;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.CombatNebulaAPI;
import com.fs.starfarer.api.combat.FogOfWarAPI;
import com.fs.starfarer.api.combat.GuidedMissileAI;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;

import tco.TcoSettings;
import tco.ammo.AmmoRules;
import tco.battle.DeathThroes;
import tco.hullmods.RadarRemoval;
import tco.missile.Guidance;
import tco.power.PowerCombatPlugin;
import tco.rescue.EscapePodPlugin;
import tco.rts.FogOfWar;

/**
 * Radar.
 * <p>
 * Detection: radar range scales with sight range; a target is found when power x (R / d)^2 x RCS >= 1.
 * Datalinked ships pool signals and share tracks. Radar missiles launch and guide only on held tracks,
 * coasting when lost and going dumb after radarGraceSeconds. ECM ships jam and may spoof incoming radar
 * missiles. Contacts feed the enemy side's fog of war; the player sees them on the scope and as pings.
 */
public class RadarCombatPlugin extends BaseEveryFrameCombatPlugin {

    public static final String ENGINE_KEY = "tco_radar_plugin";
    public static final String DATALINK_ID = "tco_datalink";
    public static final String RAM_ID = "tco_ram";
    public static final String AESA_ID = "tco_aesa";
    private static final float SWEEP_INTERVAL = 0.25f;
    private static final float MISSILE_CHECK = 0.25f;
    private static final float ECM_PICK_INTERVAL = 0.5f;
    private static final float ECM_ROLL_INTERVAL = 1f;
    private static final float AI_REVEAL_RADIUS = 250f;

    private enum State { GUIDED, COASTING, DUMB }

    private static final class Track {
        ShipAPI source;
        boolean datalink;
        State state = State.GUIDED;
        ShipAPI target;
        MissileAIPlugin original;
        float since;
        float nextCheck;
        boolean eccm;
        ShipAPI jammer;
        boolean spoofed;
        float nextRoll;
        Lead lead;
    }

    private static final String VANILLA_MISSILE_AI = "com.fs.starfarer.combat.ai.missile.MissileAI";

    private static final class Ping {
        final Vector2f loc = new Vector2f();
        float size;
        float time;
        boolean craft;
    }

    private CombatEngineAPI engine;
    private float sweepTimer;
    private float ecmTimer;
    private final Map<ShipAPI, Set<ShipAPI>> own = new HashMap<ShipAPI, Set<ShipAPI>>();
    private final List<Set<ShipAPI>> network = new ArrayList<Set<ShipAPI>>();
    private final List<Set<ShipAPI>> any = new ArrayList<Set<ShipAPI>>();
    private final Map<ShipAPI, Ping> pings = new HashMap<ShipAPI, Ping>();
    private final Map<MissileAPI, Track> missiles = new WeakHashMap<MissileAPI, Track>();
    private final Set<ShipAPI> silent = new HashSet<ShipAPI>();
    /** ECM Package jammers. */
    private static final class Jammer {
        boolean active;
        float nextDecision;
        // 0 auto, 1 always on, 2 off
        int mode;
    }

    private final Map<ShipAPI, Jammer> jammers = new HashMap<ShipAPI, Jammer>();
    private static final float JAM_DECISION_INTERVAL = 0.5f;

    private final Set<ShipAPI> holdToggled = new HashSet<ShipAPI>();
    private final Map<ShipAPI, List<WeaponAPI>> radarLaunchers = new WeakHashMap<ShipAPI, List<WeaponAPI>>();
    private final Map<ShipAPI, List<MissileAPI>> jamming = new HashMap<ShipAPI, List<MissileAPI>>();

    public RadarCombatPlugin() {
        for (int i = 0; i < 2; i++) {
            network.add(new HashSet<ShipAPI>());
            any.add(new HashSet<ShipAPI>());
        }
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
    }

    public static RadarCombatPlugin get(CombatEngineAPI engine) {
        Object plugin = engine == null ? null : engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof RadarCombatPlugin ? (RadarCombatPlugin) plugin : null;
    }

    /** RCS by hull size, x ramRcsMult with RAM, 0 while phased. */
    public static float rcs(ShipAPI ship) {
        if (ship.isPhased()) return 0f;
        if (isSmallCraft(ship)) {
            float rcs = ship.isFighter() && !ship.isDrone() ? TcoSettings.rcsFighter : TcoSettings.rcsSmallCraft;
            if (ship.getVariant() != null && ship.getVariant().hasHullMod(RAM_ID)) rcs *= TcoSettings.ramRcsMult;
            return rcs;
        }
        HullSize size = ship.getHullSize();
        float rcs = size == HullSize.FIGHTER ? TcoSettings.rcsFighter : size == HullSize.FRIGATE ? TcoSettings.rcsFrigate
                : size == HullSize.DESTROYER ? TcoSettings.rcsDestroyer : size == HullSize.CRUISER ? TcoSettings.rcsCruiser
                : size == HullSize.CAPITAL_SHIP ? TcoSettings.rcsCapital : TcoSettings.rcsDestroyer;
        if (ship.isStation()) rcs = Math.max(rcs, TcoSettings.rcsCapital * 2f);
        if (ship.getVariant() != null && ship.getVariant().hasHullMod(RAM_ID)) rcs *= TcoSettings.ramRcsMult;
        return rcs;
    }

    /** Range against an RCS-1 target. */
    public static float range(ShipAPI ship) {
        return FogOfWar.sightRange(ship) * TcoSettings.radarRangeMult;
    }

    public static float power(ShipAPI ship) {
        float p = ship.isStation() ? TcoSettings.radarStationPower : 1f;
        if (ship.getVariant() != null && ship.getVariant().hasHullMod(AESA_ID)) p *= TcoSettings.aesaRadarPower;
        return p;
    }

    public static boolean hasDatalink(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(DATALINK_ID);
    }

    private static boolean hasRadar(ShipAPI ship) {
        if (ship.isFighter() || ship.isDrone() || ship.isShuttlePod() || ship.isStationModule() || ship.isPiece()) return false;
        if (ship.getVariant() != null && ship.getVariant().hasHullMod(RadarRemoval.ID)) return false;
        return !AmmoRules.isShuttle(ship) && ship.getOwner() >= 0 && ship.getOwner() <= 1;
    }

    private boolean emitting(ShipAPI ship) {
        return ship.isAlive() && !ship.isHulk() && hasRadar(ship) && !silent.contains(ship) && !DeathThroes.isDying(ship);
    }

    public static final String HELPER_KEY = "tco_rts_helper";

    /** Fighters, drones, shuttles, TCO's craft, escape pods. */
    public static boolean isSmallCraft(ShipAPI ship) {
        return ship.isFighter() || ship.isDrone() || ship.isShuttlePod() || AmmoRules.isShuttle(ship)
                || ship.getCustomData().containsKey(EscapePodPlugin.POD_KEY) || ship.getHullSize() == HullSize.FIGHTER;
    }

    private static boolean findable(ShipAPI ship) {
        return ship.isAlive() && !ship.isHulk() && ship.getOwner() >= 0 && ship.getOwner() <= 1 && !ship.isPiece()
                && !ship.getCustomData().containsKey(HELPER_KEY);
    }

    private static ShipAPI radarOwner(ShipAPI ship) {
        if (ship != null && ship.isFighter() && ship.getWing() != null && ship.getWing().getSourceShip() != null) {
            return ship.getWing().getSourceShip();
        }
        return ship;
    }

    /** Own or datalink track on a target. */
    public boolean tracks(ShipAPI ship, ShipAPI target, boolean datalink) {
        if (ship == null || target == null) return false;
        Set<ShipAPI> mine = own.get(ship);
        if (mine != null && mine.contains(target)) return true;
        int side = ship.getOwner();
        return datalink && side >= 0 && side <= 1 && network.get(side).contains(target);
    }

    /** Whether this ship could launch radar missiles at the target now. */
    public static boolean canLaunchAt(CombatEngineAPI engine, ShipAPI ship, ShipAPI target) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null || !TcoSettings.radarEnabled) return true;
        ShipAPI owner = radarOwner(ship);
        return plugin.tracks(owner, target, hasDatalink(owner));
    }

    /** Emission control toggle; returns true if now on. */
    public static boolean toggleRadar(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null) return true;
        if (!plugin.silent.remove(ship)) {
            plugin.silent.add(ship);
            return false;
        }
        return true;
    }

    /** Player ships only; held by default with radarHoldDefault. */
    public static boolean isHoldingMissiles(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null || ship == null) return false;
        ShipAPI owner = radarOwner(ship);
        if (owner == null || owner.getOwner() != 0 || owner.isAlly()) return false;
        return plugin.held(owner);
    }

    private final Set<ShipAPI> flownHold = new HashSet<ShipAPI>();

    private boolean flying(ShipAPI ship) {
        return ship != null && ship == engine.getPlayerShip() && !ship.getCustomData().containsKey(HELPER_KEY);
    }

    private boolean held(ShipAPI owner) {
        if (flying(owner)) return flownHold.contains(owner);
        return TcoSettings.radarHoldDefault != holdToggled.contains(owner);
    }

    /** Returns true if now held. */
    public static boolean setHoldingMissiles(CombatEngineAPI engine, ShipAPI ship, boolean hold) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null || ship == null) return false;
        if (plugin.flying(ship)) {
            if (hold) plugin.flownHold.add(ship);
            else plugin.flownHold.remove(ship);
        } else if (hold == TcoSettings.radarHoldDefault) plugin.holdToggled.remove(ship);
        else plugin.holdToggled.add(ship);
        return isHoldingMissiles(engine, ship);
    }

    public static boolean hasRadarMissiles(ShipAPI ship) {
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (Guidance.firesMissiles(w.getSpec()) && Guidance.of(w.getSpec()) == Guidance.Kind.RADAR) return true;
        }
        return false;
    }

    private static boolean antiCraft(WeaponAPI w) {
        return w.getSpec() != null && w.getSpec().getAIHints() != null
                && (w.getSpec().getAIHints().contains(WeaponAPI.AIHints.ANTI_FTR)
                        || w.getSpec().getAIHints().contains(WeaponAPI.AIHints.PD)
                        || w.getSpec().getAIHints().contains(WeaponAPI.AIHints.PD_ONLY));
    }

    /** Jammer radius by hull size. */
    public static float jamRadius(ShipAPI ship) {
        HullSize size = ship.getHullSize();
        return size == HullSize.CAPITAL_SHIP ? TcoSettings.jamRadiusCapital : size == HullSize.CRUISER ? TcoSettings.jamRadiusCruiser
                : size == HullSize.DESTROYER ? TcoSettings.jamRadiusDestroyer : TcoSettings.jamRadiusFrigate;
    }

    /** Cycles auto -> on -> off; returns the new mode's name. */
    public static String cycleJammer(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null || !hasEcm(ship)) return null;
        Jammer j = plugin.jammerOf(ship);
        j.mode = (j.mode + 1) % 3;
        j.nextDecision = 0f;
        return j.mode == 0 ? "automatic" : j.mode == 1 ? "always on" : "off";
    }

    /** Ship card status, or null without ECM. */
    public static String jammerStatus(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null || !TcoSettings.jamEnabled || !hasEcm(ship)) return null;
        Jammer j = plugin.jammers.get(ship);
        if (j == null) return "Jammer auto";
        if (j.active) return j.mode == 1 ? "JAMMING (on)" : "JAMMING";
        return j.mode == 2 ? "Jammer OFF" : j.mode == 1 ? "Jammer on, no power" : "Jammer auto";
    }

    private Jammer jammerOf(ShipAPI ship) {
        Jammer j = jammers.get(ship);
        if (j == null) {
            j = new Jammer();
            jammers.put(ship, j);
        }
        return j;
    }

    /** Radar switched off; false without radar. */
    public static boolean isSilent(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        return plugin != null && plugin.silent.contains(ship);
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || !TcoSettings.radarEnabled) return;
        if (!engine.isPaused() && amount > 0f) {
            if (TcoSettings.jamEnabled) jam(amount);
            sweepTimer -= amount;
            if (sweepTimer <= 0f) {
                sweepTimer = SWEEP_INTERVAL;
                sweep();
            }
            guideMissiles();
            ecm(amount);
        }
        holdLaunchers();
        feedAi();
    }

    private void sweep() {
        own.clear();
        for (Set<ShipAPI> s : network) s.clear();
        for (Set<ShipAPI> s : any) s.clear();
        List<ShipAPI> radars = new ArrayList<ShipAPI>();
        List<ShipAPI> targets = new ArrayList<ShipAPI>();
        for (ShipAPI ship : engine.getShips()) {
            if (emitting(ship)) radars.add(ship);
            if (findable(ship)) targets.add(ship);
        }
        float now = engine.getTotalElapsedTime(false);
        for (ShipAPI target : targets) {
            float rcs = rcs(target);
            if (rcs <= 0f) continue;
            // Friendly jamming raises the detection threshold
            float threshold = 1f + noiseAt(target);
            for (int side = 0; side <= 1; side++) {
                if (target.getOwner() == side) continue;
                float linked = 0f;
                boolean found = false;
                for (ShipAPI radar : radars) {
                    if (radar.getOwner() != side) continue;
                    float dx = target.getLocation().x - radar.getLocation().x;
                    float dy = target.getLocation().y - radar.getLocation().y;
                    float d2 = Math.max(1f, dx * dx + dy * dy);
                    float r = range(radar);
                    float signal = power(radar) * r * r / d2 * nebulaMult(radar.getLocation(), target.getLocation());
                    if (signal * rcs >= threshold) {
                        Set<ShipAPI> mine = own.get(radar);
                        if (mine == null) {
                            mine = new HashSet<ShipAPI>();
                            own.put(radar, mine);
                        }
                        mine.add(target);
                        found = true;
                    }
                    if (hasDatalink(radar)) linked += signal;
                }
                if (linked * rcs >= threshold) {
                    network.get(side).add(target);
                    found = true;
                }
                if (found) {
                    any.get(side).add(target);
                    if (side == 0) {
                        Ping p = pings.get(target);
                        // Blips refresh once per sweep
                        if (p == null || now - p.time >= TcoSettings.radarPingInterval) {
                            if (p == null) {
                                p = new Ping();
                                pings.put(target, p);
                            }
                            p.loc.set(target.getLocation());
                            p.craft = isSmallCraft(target);
                            p.size = p.craft ? 14f : Math.max(35f, target.getCollisionRadius() * 0.8f);
                            p.time = now;
                        }
                    }
                }
            }
        }
        for (Iterator<Map.Entry<ShipAPI, Ping>> it = pings.entrySet().iterator(); it.hasNext();) {
            Map.Entry<ShipAPI, Ping> e = it.next();
            if (!e.getKey().isAlive() || e.getKey().isHulk() || now - e.getValue().time > TcoSettings.radarPingInterval * 2.5f) {
                it.remove();
            }
        }
        silent.removeIf(s -> !engine.isEntityInPlay(s));
        holdToggled.removeIf(s -> !engine.isEntityInPlay(s));
    }

    private float noiseAt(ShipAPI target) {
        if (!TcoSettings.jamEnabled || jammers.isEmpty()) return 0f;
        float noise = 0f;
        for (Map.Entry<ShipAPI, Jammer> e : jammers.entrySet()) {
            ShipAPI j = e.getKey();
            if (!e.getValue().active || j.getOwner() != target.getOwner()) continue;
            float r = jamRadius(j);
            float f = 1f - dist(j.getLocation(), target.getLocation()) / r;
            if (f > 0f) noise += TcoSettings.jamNoise * f;
        }
        return Math.min(TcoSettings.jamNoiseMax, noise);
    }

    private float nebulaMult(Vector2f from, Vector2f to) {
        if (TcoSettings.nebulaSignalMult >= 1f || engine.getNebula() == null) return 1f;
        float mult = 1f;
        CombatNebulaAPI nebula = engine.getNebula();
        if (nebula.locationHasNebula(from.x, from.y)) mult *= TcoSettings.nebulaSignalMult;
        if (nebula.locationHasNebula((from.x + to.x) * 0.5f, (from.y + to.y) * 0.5f)) mult *= TcoSettings.nebulaSignalMult;
        if (nebula.locationHasNebula(to.x, to.y)) mult *= TcoSettings.nebulaSignalMult;
        return mult;
    }

    /**
     * Noise jamming, drawing reactor power. Automatic mode jams when threatened, between jamStartPower and
     * jamStopPower.
     */
    private void jam(float amount) {
        float now = engine.getTotalElapsedTime(false);
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        for (ShipAPI ship : engine.getShips()) {
            if (!hasEcm(ship) || ship.isStationModule()) continue;
            Jammer j = jammerOf(ship);
            if (!ship.isAlive() || ship.isHulk() || ship.isPhased() || DeathThroes.isDying(ship)
                    || ship.getFluxTracker().isOverloaded()) {
                j.active = false;
                continue;
            }
            float level = 1f;
            if (power != null && power.capacityOf(ship) > 0f) level = power.powerOf(ship) / power.capacityOf(ship);
            boolean playerShip = ship.getOwner() == 0 && !ship.isAlly();
            int mode = playerShip ? j.mode : 0;
            if (mode == 2) {
                j.active = false;
            } else if (mode == 1) {
                j.active = power == null || !power.isOutOfPower(ship);
            } else if (now >= j.nextDecision) {
                j.nextDecision = now + JAM_DECISION_INTERVAL;
                boolean threat = threatened(ship);
                if (j.active) j.active = threat && level > TcoSettings.jamStopPower;
                else j.active = threat && level > TcoSettings.jamStartPower;
            }
            if (j.active && power != null && power.capacityOf(ship) > 0f
                    && !power.draw(ship, power.capacityOf(ship) * TcoSettings.jamPowerPerSecond * amount)) {
                j.active = false;
            }
        }
        jammers.keySet().removeIf(s -> !engine.isEntityInPlay(s));
    }

    private boolean threatened(ShipAPI ship) {
        float rcs = rcs(ship);
        for (ShipAPI radar : engine.getShips()) {
            if (radar.getOwner() == ship.getOwner() || !emitting(radar)) continue;
            float reach = range(radar) * (float) Math.sqrt(power(radar) * rcs);
            if (dist(radar.getLocation(), ship.getLocation()) <= reach) return true;
        }
        float radius = jamRadius(ship);
        for (Track t : missiles.values()) {
            if (t.state == State.DUMB || t.target == null || t.target.getOwner() != ship.getOwner()) continue;
            if (t.target == ship || dist(t.target.getLocation(), ship.getLocation()) <= radius) return true;
        }
        return false;
    }

    private void holdLaunchers() {
        // Reset per-frame launch targets (guideMissiles already used them)
        launchTarget.clear();
        for (ShipAPI ship : engine.getShips()) {
            if (!ship.isAlive() || ship.isHulk()) continue;
            List<WeaponAPI> launchers = radarLaunchers.get(ship);
            if (launchers == null) {
                launchers = new ArrayList<WeaponAPI>();
                for (WeaponAPI w : ship.getAllWeapons()) {
                    if (Guidance.firesMissiles(w.getSpec()) && Guidance.of(w.getSpec()) == Guidance.Kind.RADAR) launchers.add(w);
                }
                radarLaunchers.put(ship, launchers);
            }
            if (launchers.isEmpty()) continue;
            ShipAPI owner = radarOwner(ship);
            boolean link = hasDatalink(owner);
            boolean held = owner != null && owner.getOwner() == 0 && !owner.isAlly() && held(owner);
            // Player-flown ship: the manual group ignores the hold and aims at the ship under the cursor
            boolean flown = ship == engine.getPlayerShip() && !engine.isUIAutopilotOn();
            WeaponGroupAPI selectedGroup = flown ? ship.getSelectedGroupAPI() : null;
            ShipAPI cursorTarget = null;
            boolean cursorChecked = false;
            String refusal = null;
            // Player side's fog isn't fed radar, so TCO fires its launchers at tracks beyond sight
            boolean ownSide = engine.getPlayerShip() != null && ship.getOwner() == engine.getPlayerShip().getOwner();
            for (WeaponAPI w : launchers) {
                WeaponGroupAPI group = ship.getWeaponGroupFor(w);
                boolean manual = flown && group != null && group == selectedGroup && !group.isAutofiring();
                if (held && !manual) {
                    w.setForceNoFireOneFrame(true);
                    continue;
                }
                ShipAPI target = null;
                if (group != null && group.isAutofiring()) {
                    AutofireAIPlugin ai = group.getAutofirePlugin(w);
                    if (ai != null) target = ai.getTargetShip();
                    if (target == null && ownSide && !ship.isHoldFire() && TcoSettings.radarFireBeyondSight) {
                        ShipAPI far = trackBeyondSight(ship, owner, link, w);
                        if (far != null && fireAt(ship, w, far)) {
                            launchTarget.put(ship, far);
                            target = far;
                        }
                    }
                } else {
                    target = ship.getShipTarget();
                    if (target == null && manual) {
                        ShipAPI locked = radarLock.get(ship);
                        if (locked != null && locked.isAlive() && !locked.isHulk()) target = locked;
                    }
                    if (target == null && manual) {
                        if (!cursorChecked) {
                            cursorChecked = true;
                            cursorTarget = nearestEnemyTo(ship, ship.getMouseTarget(), antiCraft(w));
                        }
                        target = cursorTarget;
                    }
                }
                // Don't waste missiles on small craft
                boolean wasted = target != null && isSmallCraft(target) && !antiCraft(w);
                boolean tracked = target != null && tracks(owner, target, link);
                if (target == null || wasted || !tracked) {
                    w.setForceNoFireOneFrame(true);
                    if (manual && refusal == null) {
                        refusal = target == null ? "No target: aim at a ship, or select one (R)"
                                : wasted ? "Radar missiles aren't wasted on small craft"
                                : "No radar track on " + target.getHullSpec().getHullName();
                    }
                } else if (manual) {
                    launchTarget.put(ship, target);
                }
            }
            if (refusal != null && Mouse.isButtonDown(0)) hint(ship, refusal);
        }
    }

    private ShipAPI trackBeyondSight(ShipAPI ship, ShipAPI owner, boolean link, WeaponAPI w) {
        ShipAPI best = null;
        float bestScore = Float.MAX_VALUE;
        float reach = w.getRange();
        for (ShipAPI other : engine.getShips()) {
            if (other.getOwner() == ship.getOwner() || other.getOwner() == 100 || !findable(other)) continue;
            boolean craft = isSmallCraft(other);
            if (craft && !antiCraft(w)) continue;
            float d = dist(ship.getLocation(), other.getLocation()) - other.getCollisionRadius();
            if (d > reach || !tracks(owner, other, link)) continue;
            float score = d + (craft ? 100000f : 0f);
            if (score < bestScore) {
                bestScore = score;
                best = other;
            }
        }
        return best;
    }

    private boolean fireAt(ShipAPI ship, WeaponAPI w, ShipAPI target) {
        if (w.isDisabled() || w.isFiring() || w.getCooldownRemaining() > 0f) return false;
        if (w.usesAmmo() && w.getAmmo() <= 0) return false;
        float want = (float) Math.toDegrees(Math.atan2(target.getLocation().y - w.getLocation().y,
                target.getLocation().x - w.getLocation().x));
        float centre = ship.getFacing() + w.getArcFacing();
        float off = Lead.angleDiff(want, centre);
        float half = w.getArc() / 2f;
        w.setCurrAngle(centre + Math.max(-half, Math.min(half, off)));
        w.setForceFireOneFrame(true);
        return true;
    }

    private final Map<ShipAPI, ShipAPI> radarLock = new WeakHashMap<ShipAPI, ShipAPI>();

    private void lock(ShipAPI ship, ShipAPI target) {
        radarLock.put(ship, target);
        try {
            ship.setShipTarget(target);
        } catch (RuntimeException e) {
            // The game may refuse an unseen target; TCO's lock is enough
        }
    }

    /** Drops the radar lock (and the target, if it was the lock); returns whether there was one. */
    public static boolean unlockRadar(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null || ship == null) return false;
        ShipAPI was = plugin.radarLock.remove(ship);
        if (was == null) return false;
        if (ship.getShipTarget() == was) {
            try {
                ship.setShipTarget(null);
            } catch (RuntimeException e) {
            }
        }
        return true;
    }

    public static ShipAPI radarLockOf(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null) return null;
        ShipAPI t = plugin.radarLock.get(ship);
        return t != null && t.isAlive() && !t.isHulk() ? t : null;
    }

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        ShipAPI ship = engine == null ? null : engine.getPlayerShip();
        ShipAPI t = ship == null ? null : radarLockOf(engine, ship);
        if (t == null || !TcoSettings.radarEnabled) return;
        ShipAPI owner = radarOwner(ship);
        boolean tracked = tracks(owner, t, hasDatalink(owner));
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2f);
        if (tracked) GL11.glColor4f(1f, 0.85f, 0.25f, 0.9f);
        else GL11.glColor4f(0.6f, 0.6f, 0.6f, 0.6f);
        float r = t.getCollisionRadius() + 35f;
        brackets(t.getLocation().x, t.getLocation().y, r);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(t.getLocation().x, t.getLocation().y + r * 0.5f);
        GL11.glVertex2f(t.getLocation().x + r * 0.5f, t.getLocation().y);
        GL11.glVertex2f(t.getLocation().x, t.getLocation().y - r * 0.5f);
        GL11.glVertex2f(t.getLocation().x - r * 0.5f, t.getLocation().y);
        GL11.glEnd();
        GL11.glPopAttrib();
    }

    // ship -> world x, y, time painted, frozen by a lock (1)
    private final Map<ShipAPI, float[]> painted = new WeakHashMap<ShipAPI, float[]>();
    private float lastScopeTime = -1f;
    private ShipAPI lastScopeShip;
    private static final float SCOPE_SWEEP_DEG = 120f;

    private final List<Object[]> scopeBlips = new ArrayList<Object[]>();
    private LabelAPI scopeLabel;
    private String scopeText;
    private LabelAPI lockLabel;
    private String lockText;

    private ShipAPI scopeShip() {
        if (engine == null || !TcoSettings.radarEnabled || !TcoSettings.radarScope || FogOfWar.isShading()) return null;
        if (engine.getCombatUI() == null || engine.getCombatUI().isShowingCommandUI() || engine.isUIShowingDialog()
                || engine.isCombatOver()) {
            return null;
        }
        ShipAPI ship = engine.getPlayerShip();
        if (ship == null || !ship.isAlive() || !hasRadar(ship) || ship.getCustomData().containsKey(HELPER_KEY)) return null;
        return ship;
    }

    private float scopeX() {
        return Global.getSettings().getScreenWidth() - TcoSettings.radarScopeRight - TcoSettings.radarScopeSize / 2f;
    }

    private float scopeY() {
        return TcoSettings.radarScopeBottom + TcoSettings.radarScopeSize / 2f;
    }

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        ShipAPI ship = scopeShip();
        if (ship == null) {
            scopeLmb = scopeRmb = false;
            return;
        }
        float cx = scopeX(), cy = scopeY(), r = TcoSettings.radarScopeSize / 2f;
        for (InputEventAPI event : events) {
            if (event.isLMBUpEvent()) scopeLmb = false;
            if (event.isRMBUpEvent()) scopeRmb = false;
            if (event.isConsumed() || !(event.isLMBDownEvent() || event.isRMBDownEvent() || event.isLMBUpEvent()
                    || event.isRMBUpEvent())) {
                continue;
            }
            float mx = Global.getSettings().getMouseX(), my = Global.getSettings().getMouseY();
            float reach = r + 10f;
            if ((mx - cx) * (mx - cx) + (my - cy) * (my - cy) > reach * reach) continue;
            if (event.isLMBDownEvent()) scopeLmb = true;
            if (event.isRMBDownEvent()) scopeRmb = true;
            if (event.isRMBDownEvent() && unlockRadar(engine, ship)) {
                engine.getCombatUI().addMessage(0, new Color(255, 215, 110), "Radar lock released");
            }
            if (event.isLMBDownEvent()) {
                ShipAPI best = null;
                float bestD = 14f;
                for (Object[] b : scopeBlips) {
                    ShipAPI s = (ShipAPI) b[0];
                    if (isSmallCraft(s)) continue;
                    float d = (float) Math.hypot(mx - (Float) b[1], my - (Float) b[2]);
                    if (d < bestD) {
                        best = s;
                        bestD = d;
                    }
                }
                if (best != null) {
                    lock(ship, best);
                    engine.getCombatUI().addMessage(0, new Color(255, 215, 110), "Radar lock: " + best.getHullSpec().getHullName()
                            + ", " + Math.round(dist(ship.getLocation(), best.getLocation())) + " away");
                }
            }
            event.consume();
        }
        // Fire and shield read the mouse directly, so block them while a scope press is held
        if (scopeLmb) ship.blockCommandForOneFrame(ShipCommand.FIRE);
        if (scopeRmb) ship.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK);
    }

    private boolean scopeLmb, scopeRmb;

    /**
     * Repaints contacts the sweep passed since last frame. A lock stops the sweep: only the lock is followed.
     */
    private void updatePainted(ShipAPI ship, ShipAPI owner, boolean link, boolean on, boolean stt, ShipAPI locked,
            float range, float now) {
        if (ship != lastScopeShip) {
            painted.clear();
            lastScopeShip = ship;
            lastScopeTime = -1f;
        }
        float swept = lastScopeTime < 0f || now < lastScopeTime ? 360f : (now - lastScopeTime) * SCOPE_SWEEP_DEG;
        float from = -(lastScopeTime < 0f ? now : lastScopeTime) * SCOPE_SWEEP_DEG;
        lastScopeTime = now;
        for (Iterator<Map.Entry<ShipAPI, float[]>> it = painted.entrySet().iterator(); it.hasNext();) {
            ShipAPI s = it.next().getKey();
            if (!s.isAlive() || s.isHulk() || !engine.isEntityInPlay(s)) it.remove();
        }
        Vector2f c = ship.getLocation();
        for (ShipAPI other : engine.getShips()) {
            if (other == ship || !findable(other) || other.getCustomData().containsKey(HELPER_KEY)) continue;
            boolean friend = other.getOwner() == ship.getOwner();
            if (!friend && other.getOwner() == 100) continue;
            float[] p = painted.get(other);
            if (stt) {
                if (other == locked) painted.put(other, new float[] {other.getLocation().x, other.getLocation().y, now, 0f});
                else if (p != null) p[3] = 1f;
                continue;
            }
            if (p != null) p[3] = 0f;
            if (!on) continue;
            float dx = other.getLocation().x - c.x, dy = other.getLocation().y - c.y;
            float bearing = (float) Math.toDegrees(Math.atan2(dy, dx));
            // Sweep turns clockwise
            float behind = ((from - bearing) % 360f + 360f) % 360f;
            if (swept < 360f && behind > swept) continue;
            boolean inRange = dx * dx + dy * dy <= range * range;
            // Datalink contacts beyond the scope stay as rim markers
            boolean held = friend ? inRange : tracks(owner, other, link) && (inRange || !isSmallCraft(other));
            if (held) painted.put(other, new float[] {other.getLocation().x, other.getLocation().y, now, 0f});
            else painted.remove(other);
        }
    }

    private void edgeMarker(ShipAPI other, float[] p, float cx, float cy, float r, float dx, float dy, boolean isLock, float now) {
        double a = Math.atan2(dy, dx);
        float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
        float glow = isLock ? 1f : p[3] > 0f ? 0.3f : 0.35f + 0.65f * (float) Math.exp(-(now - p[2]) / 1.2f);
        float size = other.getHullSize() == HullSize.CAPITAL_SHIP ? 8f : other.getHullSize() == HullSize.CRUISER ? 7f
                : other.getHullSize() == HullSize.DESTROYER ? 6f : 5f;
        float tipX = cx + cos * (r + 6f), tipY = cy + sin * (r + 6f);
        float baseX = cx + cos * (r - size), baseY = cy + sin * (r - size);
        if (isLock) GL11.glColor4f(1f, 0.85f, 0.25f, 0.95f);
        else GL11.glColor4f(1f, 0.3f, 0.25f, 0.95f * glow);
        GL11.glLineWidth(1.5f);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(tipX, tipY);
        GL11.glVertex2f(baseX - sin * size * 0.7f, baseY + cos * size * 0.7f);
        GL11.glVertex2f(baseX + sin * size * 0.7f, baseY - cos * size * 0.7f);
        GL11.glEnd();
        scopeBlips.add(new Object[] {other, cx + cos * (r - size * 0.3f), cy + sin * (r - size * 0.3f)});
    }

    private void drawScope(ShipAPI ship) {
        scopeBlips.clear();
        float cx = scopeX(), cy = scopeY(), r = TcoSettings.radarScopeSize / 2f;
        float range = range(ship) * (float) Math.sqrt(power(ship));
        float now = engine.getTotalElapsedTime(true);
        ShipAPI owner = radarOwner(ship);
        boolean link = hasDatalink(owner);
        boolean on = !silent.contains(ship);
        Vector2f c = ship.getLocation();

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // Screen
        GL11.glColor4f(0.01f, 0.06f, 0.03f, 0.82f);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= 64; i++) {
            double a = Math.PI * 2.0 * i / 64;
            GL11.glVertex2f(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
        }
        GL11.glEnd();
        GL11.glLineWidth(1f);
        GL11.glColor4f(0.3f, 0.9f, 0.45f, 0.18f);
        for (int ring = 1; ring <= 3; ring++) circle(cx, cy, r * ring / 4f, 48);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx - r, cy);
        GL11.glVertex2f(cx + r, cy);
        GL11.glVertex2f(cx, cy - r);
        GL11.glVertex2f(cx, cy + r);
        GL11.glEnd();
        // Sight range
        float sight = FogOfWar.sightRange(ship) / range * r;
        if (sight < r) {
            GL11.glColor4f(0.55f, 0.8f, 1f, 0.25f);
            dashed(cx, cy, sight);
        }
        GL11.glColor4f(0.3f, 0.9f, 0.45f, 0.55f);
        circle(cx, cy, r, 64);
        // Sweep (stopped on a lock)
        ShipAPI locked = radarLockOf(engine, ship);
        boolean stt = on && locked != null && tracks(owner, locked, link);
        if (on && !stt) {
            double sweep = Math.toRadians(-(now * SCOPE_SWEEP_DEG) % 360f);
            for (int i = 0; i < 10; i++) {
                double a = sweep + i * 0.05;
                GL11.glColor4f(0.35f, 1f, 0.5f, 0.35f * (1f - i / 10f));
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(cx, cy);
                GL11.glVertex2f(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
                GL11.glEnd();
            }
        } else if (stt) {
            float lx = locked.getLocation().x - c.x, ly = locked.getLocation().y - c.y;
            double a = Math.atan2(ly, lx);
            GL11.glColor4f(1f, 0.85f, 0.25f, 0.3f);
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(cx, cy);
            GL11.glVertex2f(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
            GL11.glEnd();
        }
        updatePainted(ship, owner, link, on, stt, locked, range, now);
        // Enemy jammers
        GL11.glPointSize(2f);
        GL11.glBegin(GL11.GL_POINTS);
        for (Map.Entry<ShipAPI, Jammer> e : jammers.entrySet()) {
            if (!e.getValue().active || e.getKey().getOwner() == ship.getOwner()) continue;
            Vector2f at = e.getKey().getLocation();
            float jr = jamRadius(e.getKey()) / range * r;
            float jx = cx + (at.x - c.x) / range * r, jy = cy + (at.y - c.y) / range * r;
            if (Math.hypot(jx - cx, jy - cy) > r + jr) continue;
            for (int i = 0; i < 25; i++) {
                double a = Math.random() * Math.PI * 2.0;
                float d = jr * (float) Math.sqrt(Math.random());
                float px = jx + (float) Math.cos(a) * d, py = jy + (float) Math.sin(a) * d;
                if (Math.hypot(px - cx, py - cy) > r) continue;
                GL11.glColor4f(1f, 0.45f, 0.75f, 0.35f + 0.4f * (float) Math.random());
                GL11.glVertex2f(px, py);
            }
        }
        GL11.glEnd();
        // Blips
        for (Map.Entry<ShipAPI, float[]> e : painted.entrySet()) {
            ShipAPI other = e.getKey();
            float[] p = e.getValue();
            float dx = p[0] - c.x, dy = p[1] - c.y;
            boolean friend = other.getOwner() == ship.getOwner();
            if (dx * dx + dy * dy > range * range) {
                if (!friend) edgeMarker(other, p, cx, cy, r, dx, dy, other == locked, now);
                continue;
            }
            float bx = cx + dx / range * r, by = cy + dy / range * r;
            boolean craft = isSmallCraft(other);
            float size = craft ? 2f : other.getHullSize() == HullSize.CAPITAL_SHIP ? 7f : other.getHullSize() == HullSize.CRUISER ? 6f
                    : other.getHullSize() == HullSize.DESTROYER ? 5f : 4f;
            float glow = other == locked && stt ? 1f
                    : p[3] > 0f ? 0.3f : 0.35f + 0.65f * (float) Math.exp(-(now - p[2]) / 1.2f);
            if (friend) GL11.glColor4f(0.4f, 1f, 0.55f, (craft ? 0.35f : 0.6f) * glow);
            else if (craft) GL11.glColor4f(1f, 0.6f, 0.3f, 0.75f * glow);
            else GL11.glColor4f(1f, 0.3f, 0.25f, 0.95f * glow);
            GL11.glPointSize(size);
            GL11.glBegin(GL11.GL_POINTS);
            GL11.glVertex2f(bx, by);
            GL11.glEnd();
            if (!friend) scopeBlips.add(new Object[] {other, bx, by});
            if (other == locked) {
                GL11.glColor4f(1f, 0.85f, 0.25f, 0.95f);
                circle(bx, by, size + 5f, 16);
            }
        }
        // Own missiles
        GL11.glPointSize(2.5f);
        GL11.glBegin(GL11.GL_POINTS);
        for (MissileAPI m : engine.getMissiles()) {
            if (m.isFlare() || m.isFizzling() || m.didDamage() || radarOwner(m.getSource()) != ship) continue;
            float dx = m.getLocation().x - c.x, dy = m.getLocation().y - c.y;
            if (dx * dx + dy * dy > range * range) continue;
            GL11.glColor4f(0.45f, 0.9f, 1f, m.isFading() ? 0.4f : 0.9f);
            GL11.glVertex2f(cx + dx / range * r, cy + dy / range * r);
        }
        GL11.glEnd();
        // Own ship
        double f = Math.toRadians(ship.getFacing());
        GL11.glColor4f(0.6f, 1f, 0.7f, 0.95f);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(cx + (float) Math.cos(f) * 7f, cy + (float) Math.sin(f) * 7f);
        GL11.glVertex2f(cx + (float) Math.cos(f + 2.5) * 5f, cy + (float) Math.sin(f + 2.5) * 5f);
        GL11.glVertex2f(cx + (float) Math.cos(f - 2.5) * 5f, cy + (float) Math.sin(f - 2.5) * 5f);
        GL11.glEnd();
        GL11.glPopAttrib();

        String text = on ? "RADAR  " + Math.round(range) : "RADAR OFF (N)";
        try {
            if (scopeLabel == null) scopeLabel = Global.getSettings().createLabel(text, Fonts.DEFAULT_SMALL);
            if (!text.equals(scopeText)) {
                scopeLabel.setText(text);
                scopeText = text;
            }
            scopeLabel.setColor(on ? new Color(120, 230, 150) : new Color(255, 170, 90));
            scopeLabel.getPosition().setSize(TcoSettings.radarScopeSize, 16f);
            scopeLabel.getPosition().setLocation(cx - r + 6f, cy - r - 18f);
            scopeLabel.render(1f);
            if (locked != null) {
                boolean held = tracks(owner, locked, link);
                String lock = "LOCK  " + locked.getHullSpec().getHullName() + "  "
                        + Math.round(dist(c, locked.getLocation()) / 10f) * 10 + (held ? "" : "  (track lost)");
                if (lockLabel == null) lockLabel = Global.getSettings().createLabel(lock, Fonts.DEFAULT_SMALL);
                if (!lock.equals(lockText)) {
                    lockLabel.setText(lock);
                    lockText = lock;
                }
                lockLabel.setColor(held ? new Color(255, 215, 110) : new Color(170, 170, 170));
                lockLabel.getPosition().setSize(TcoSettings.radarScopeSize + 60f, 16f);
                lockLabel.getPosition().setLocation(cx - r + 6f, cy - r - 36f);
                lockLabel.render(1f);
            }
        } catch (RuntimeException e) {
        }
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        ShipAPI scope = scopeShip();
        if (scope != null) drawScope(scope);
        else scopeBlips.clear();
        ShipAPI ship = engine == null ? null : engine.getPlayerShip();
        ShipAPI t = ship == null ? null : radarLockOf(engine, ship);
        if (t == null || !TcoSettings.radarEnabled) return;
        if (viewport.isNearViewport(t.getLocation(), -50f)) return;
        // Off-screen lock arrow
        float w = Global.getSettings().getScreenWidth(), h = Global.getSettings().getScreenHeight();
        float sx = viewport.convertWorldXtoScreenX(t.getLocation().x), sy = viewport.convertWorldYtoScreenY(t.getLocation().y);
        float cx = w / 2f, cy = h / 2f;
        float dx = sx - cx, dy = sy - cy;
        float scale = Math.min((w / 2f - 40f) / Math.max(1f, Math.abs(dx)), (h / 2f - 40f) / Math.max(1f, Math.abs(dy)));
        float ax = cx + dx * scale, ay = cy + dy * scale;
        double a = Math.atan2(dy, dx);
        ShipAPI owner = radarOwner(ship);
        boolean tracked = tracks(owner, t, hasDatalink(owner));
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (tracked) GL11.glColor4f(1f, 0.85f, 0.25f, 0.9f);
        else GL11.glColor4f(0.6f, 0.6f, 0.6f, 0.7f);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(ax + (float) Math.cos(a) * 16f, ay + (float) Math.sin(a) * 16f);
        GL11.glVertex2f(ax + (float) Math.cos(a + 2.5) * 12f, ay + (float) Math.sin(a + 2.5) * 12f);
        GL11.glVertex2f(ax + (float) Math.cos(a - 2.5) * 12f, ay + (float) Math.sin(a - 2.5) * 12f);
        GL11.glEnd();
        GL11.glPopAttrib();
    }

    private final Map<ShipAPI, ShipAPI> launchTarget = new WeakHashMap<ShipAPI, ShipAPI>();
    private float nextHint;

    private ShipAPI nearestEnemyTo(ShipAPI ship, Vector2f point, boolean craftToo) {
        if (point == null) return null;
        ShipAPI best = null;
        float bestDist = 800f;
        for (ShipAPI other : engine.getShips()) {
            if (other.getOwner() == ship.getOwner() || other.getOwner() == 100 || !findable(other)) continue;
            float d = dist(point, other.getLocation()) - other.getCollisionRadius();
            if (!craftToo && isSmallCraft(other)) d += 400f;
            if (d < bestDist) {
                best = other;
                bestDist = d;
            }
        }
        return best;
    }

    private void hint(ShipAPI ship, String text) {
        float now = engine.getTotalElapsedTime(true);
        if (now < nextHint) return;
        nextHint = now + 2f;
        engine.addFloatingText(ship.getLocation(), text, 22f, new Color(255, 190, 110), ship, 0.5f, 1.5f);
    }

    private void feedAi() {
        if (!TcoSettings.radarFeedsAi) return;
        // Only the enemy side's fog: the player side's also drives the HUD and reticles, so it stays
        // sight-only
        int playerSide = engine.getPlayerShip() != null ? engine.getPlayerShip().getOwner() : 0;
        for (int side = 0; side <= 1; side++) {
            if (side == playerSide) continue;
            FogOfWarAPI fog = engine.getFogOfWar(side);
            if (fog == null) continue;
            for (ShipAPI target : any.get(side)) {
                if (!target.isAlive()) continue;
                fog.revealAroundPoint(target, target.getLocation().x, target.getLocation().y, AI_REVEAL_RADIUS);
            }
        }
    }

    private void guideMissiles() {
        float now = engine.getTotalElapsedTime(false);
        for (MissileAPI missile : engine.getMissiles()) {
            if (missile.isFizzling() || missile.isFading() || missile.isFlare() || missile.didDamage()) continue;
            if (missile.getWeaponSpec() == null || Guidance.of(missile.getWeaponSpec()) != Guidance.Kind.RADAR) continue;
            Track t = missiles.get(missile);
            if (t == null) {
                t = new Track();
                t.source = radarOwner(missile.getSource());
                t.datalink = hasDatalink(t.source);
                t.eccm = t.source != null && t.source.getVariant() != null && t.source.getVariant().hasHullMod("eccm");
                t.nextCheck = now;
                missiles.put(missile, t);
                // Replace vanilla's pursuit AI with lead guidance (other mods' AIs are left alone)
                MissileAIPlugin own = missile.getUnwrappedMissileAI();
                ShipAPI checked = t.source != null ? launchTarget.get(t.source) : null;
                if (checked != null && (!checked.isAlive() || checked.isHulk())) checked = null;
                if (TcoSettings.radarLeadGuidance && own != null && VANILLA_MISSILE_AI.equals(own.getClass().getName())) {
                    t.lead = new Lead(missile);
                    t.lead.target = checked != null ? checked : targetOf(missile);
                    missile.setMissileAI(t.lead);
                } else if (checked != null && own instanceof GuidedMissileAI) {
                    ((GuidedMissileAI) own).setTarget(checked);
                }
            }
            if (t.state == State.DUMB || now < t.nextCheck) continue;
            t.nextCheck = now + MISSILE_CHECK;
            if (t.lead != null && (t.lead.target == null || !t.lead.target.isAlive() || t.lead.target.isHulk())) {
                // Target gone: nearest tracked enemy ahead
                if (t.state == State.GUIDED) t.lead.target = pickTarget(missile, t);
            }
            if (t.state == State.GUIDED) {
                ShipAPI target = t.lead != null ? t.lead.target : targetOf(missile);
                if (target == null) continue;
                t.target = target;
                if (!held(t, target)) {
                    // Lost: coast
                    if (t.lead != null) {
                        t.lead.coast = true;
                    } else {
                        MissileAIPlugin ai = missile.getUnwrappedMissileAI();
                        t.original = ai != null ? ai : missile.getMissileAI();
                        missile.setMissileAI(new Coast(missile));
                    }
                    t.state = State.COASTING;
                    t.since = now;
                }
            } else if (t.state == State.COASTING) {
                if (t.target != null && t.target.isAlive() && held(t, t.target)) {
                    if (t.lead != null) {
                        t.lead.coast = false;
                        t.lead.target = t.target;
                    } else {
                        missile.setMissileAI(t.original);
                        MissileAIPlugin ai = missile.getUnwrappedMissileAI();
                        if (!(ai instanceof GuidedMissileAI)) ai = missile.getMissileAI();
                        if (ai instanceof GuidedMissileAI) ((GuidedMissileAI) ai).setTarget(t.target);
                    }
                    t.state = State.GUIDED;
                } else if (now - t.since >= TcoSettings.radarGraceSeconds) {
                    t.state = State.DUMB;
                    t.original = null;
                }
            }
        }
    }

    private boolean held(Track t, ShipAPI target) {
        if (t.spoofed) return false;
        boolean launcher = t.source != null && t.source.isAlive() && !t.source.isHulk() && tracks(t.source, target, false);
        if (launcher) return true;
        return t.datalink && target.getOwner() >= 0 && target.getOwner() <= 1
                && network.get(1 - target.getOwner()).contains(target);
    }

    private ShipAPI pickTarget(MissileAPI missile, Track t) {
        ShipAPI best = null;
        float bestDist = Float.MAX_VALUE;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() == missile.getOwner() || ship.getOwner() == 100 || !findable(ship)) continue;
            // Small craft only if no ship is nearly as close
            float d = dist(missile.getLocation(), ship.getLocation()) * (isSmallCraft(ship) ? 3f : 1f);
            if (d >= bestDist) continue;
            float to = (float) Math.toDegrees(Math.atan2(ship.getLocation().y - missile.getLocation().y,
                    ship.getLocation().x - missile.getLocation().x));
            if (Math.abs(Lead.angleDiff(to, missile.getFacing())) > 60f || !held(t, ship)) continue;
            best = ship;
            bestDist = d;
        }
        return best;
    }

    private static ShipAPI targetOf(MissileAPI missile) {
        MissileAIPlugin ai = missile.getUnwrappedMissileAI();
        if (!(ai instanceof GuidedMissileAI)) ai = missile.getMissileAI();
        if (ai instanceof GuidedMissileAI) {
            CombatEntityAPI target = ((GuidedMissileAI) ai).getTarget();
            if (target instanceof ShipAPI) return (ShipAPI) target;
        }
        return null;
    }

    public static int ecmChannels(ShipAPI ship) {
        HullSize size = ship.getHullSize();
        return size == HullSize.CAPITAL_SHIP ? TcoSettings.ecmCapital : size == HullSize.CRUISER ? TcoSettings.ecmCruiser
                : size == HullSize.DESTROYER ? TcoSettings.ecmDestroyer : TcoSettings.ecmFrigate;
    }

    private static boolean hasEcm(ShipAPI ship) {
        return ship.getVariant() != null && ship.getVariant().hasHullMod("ecm") && !ship.isFighter();
    }

    private void ecm(float amount) {
        if (!TcoSettings.ecmEnabled) return;
        float now = engine.getTotalElapsedTime(false);
        ecmTimer -= amount;
        boolean pick = ecmTimer <= 0f;
        if (pick) ecmTimer = ECM_PICK_INTERVAL;
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);

        if (pick) {
            // Reassign jammers to the nearest inbound missiles
            for (Track t : missiles.values()) t.jammer = null;
            jamming.clear();
            List<MissileAPI> incoming = new ArrayList<MissileAPI>();
            for (Map.Entry<MissileAPI, Track> e : missiles.entrySet()) {
                MissileAPI m = e.getKey();
                if (e.getValue().state != State.DUMB && engine.isEntityInPlay(m) && !m.isFading()) incoming.add(m);
            }
            for (ShipAPI ship : engine.getShips()) {
                if (!ship.isAlive() || ship.isHulk() || !hasEcm(ship) || DeathThroes.isDying(ship)) continue;
                if (power != null && power.isOutOfPower(ship)) continue;
                List<MissileAPI> mine = new ArrayList<MissileAPI>();
                List<MissileAPI> near = new ArrayList<MissileAPI>();
                for (MissileAPI m : incoming) {
                    Track t = missiles.get(m);
                    if (t.jammer != null || m.getOwner() == ship.getOwner() || t.target == null) continue;
                    if (dist(m.getLocation(), ship.getLocation()) > TcoSettings.ecmRange) continue;
                    if (t.target == ship) mine.add(m);
                    else if (t.target.getOwner() == ship.getOwner()
                            && dist(t.target.getLocation(), ship.getLocation()) <= TcoSettings.ecmRange) near.add(m);
                }
                sortByDistance(mine, ship.getLocation());
                sortByDistance(near, ship.getLocation());
                mine.addAll(near);
                List<MissileAPI> taken = new ArrayList<MissileAPI>();
                for (MissileAPI m : mine) {
                    if (taken.size() >= ecmChannels(ship)) break;
                    Track t = missiles.get(m);
                    t.jammer = ship;
                    if (t.nextRoll < now) t.nextRoll = now + ECM_ROLL_INTERVAL;
                    taken.add(m);
                }
                if (!taken.isEmpty()) jamming.put(ship, taken);
            }
            // Unjammed missiles are no longer fooled
            for (Track t : missiles.values()) {
                if (t.jammer == null) t.spoofed = false;
            }
        }

        for (Iterator<Map.Entry<ShipAPI, List<MissileAPI>>> it = jamming.entrySet().iterator(); it.hasNext();) {
            Map.Entry<ShipAPI, List<MissileAPI>> e = it.next();
            ShipAPI ship = e.getKey();
            List<MissileAPI> held = e.getValue();
            // Power per jammed missile
            if (power != null && power.capacityOf(ship) > 0f) {
                float cost = power.capacityOf(ship) * TcoSettings.ecmPowerPerMissile * held.size() * amount;
                if (!power.draw(ship, cost)) {
                    for (MissileAPI m : held) {
                        Track t = missiles.get(m);
                        if (t != null) {
                            t.jammer = null;
                            t.spoofed = false;
                        }
                    }
                    it.remove();
                    continue;
                }
            }
            for (MissileAPI m : held) {
                Track t = missiles.get(m);
                if (t == null || t.spoofed || t.state == State.DUMB || now < t.nextRoll) continue;
                t.nextRoll = now + ECM_ROLL_INTERVAL;
                if (Math.random() < spoofChance(t)) t.spoofed = true;
            }
        }
    }

    /** Spoof chance per second: easier on small targets, harder with ECCM. */
    public static float spoofChance(float targetRcs, boolean eccm) {
        float chance = TcoSettings.ecmSpoofChance * (1f - TcoSettings.ecmRcsWeight * Math.min(1f, targetRcs));
        if (eccm) chance *= TcoSettings.eccmSpoofMult;
        return Math.max(0f, Math.min(1f, chance));
    }

    private static float spoofChance(Track t) {
        return spoofChance(t.target == null ? 1f : rcs(t.target), t.eccm);
    }

    private static void sortByDistance(List<MissileAPI> list, final Vector2f from) {
        list.sort((a, b) -> Float.compare(dist(a.getLocation(), from), dist(b.getLocation(), from)));
    }

    private static float dist(Vector2f a, Vector2f b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Fog pings, radar rings, track marks and jamming lines. */
    public static void render(CombatEngineAPI engine, ViewportAPI viewport, Collection<ShipAPI> selected) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null || !TcoSettings.radarEnabled) return;
        plugin.draw(viewport, selected);
    }

    private void draw(ViewportAPI viewport, Collection<ShipAPI> selected) {
        float now = engine.getTotalElapsedTime(false);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // Pings
        if (FogOfWar.isShading()) {
            GL11.glLineWidth(1.5f);
            for (Map.Entry<ShipAPI, Ping> e : pings.entrySet()) {
                if (!FogOfWar.isHidden(e.getKey())) continue;
                Ping p = e.getValue();
                if (!viewport.isNearViewport(p.loc, p.size * 2f)) continue;
                float age = (now - p.time) / Math.max(0.1f, TcoSettings.radarPingInterval);
                float alpha = Math.max(0.15f, 0.9f - 0.5f * age);
                if (p.craft) {
                    // Small craft
                    GL11.glColor4f(1f, 0.6f, 0.3f, alpha * 0.85f);
                    GL11.glBegin(GL11.GL_LINE_LOOP);
                    GL11.glVertex2f(p.loc.x, p.loc.y + p.size);
                    GL11.glVertex2f(p.loc.x + p.size, p.loc.y);
                    GL11.glVertex2f(p.loc.x, p.loc.y - p.size);
                    GL11.glVertex2f(p.loc.x - p.size, p.loc.y);
                    GL11.glEnd();
                    GL11.glPointSize(3f);
                    GL11.glBegin(GL11.GL_POINTS);
                    GL11.glVertex2f(p.loc.x, p.loc.y);
                    GL11.glEnd();
                    continue;
                }
                GL11.glColor4f(1f, 0.35f, 0.25f, alpha);
                circle(p.loc.x, p.loc.y, p.size, 20);
                GL11.glColor4f(1f, 0.35f, 0.25f, alpha * 0.4f);
                circle(p.loc.x, p.loc.y, p.size * (1f + Math.min(1f, age) * 0.6f), 20);
                GL11.glPointSize(4f);
                GL11.glColor4f(1f, 0.45f, 0.3f, alpha);
                GL11.glBegin(GL11.GL_POINTS);
                GL11.glVertex2f(p.loc.x, p.loc.y);
                GL11.glEnd();
            }
        }

        if (selected != null && !selected.isEmpty()) {
            // Radar rings
            GL11.glLineWidth(1f);
            for (ShipAPI ship : selected) {
                if (!hasRadar(ship) || !ship.isAlive()) continue;
                boolean on = !silent.contains(ship);
                float r = range(ship) * (float) Math.sqrt(power(ship));
                GL11.glColor4f(0.35f, 1f, 0.55f, on ? 0.22f : 0.08f);
                dashed(ship.getLocation().x, ship.getLocation().y, r);
            }
            // Track marks
            GL11.glLineWidth(1.5f);
            for (ShipAPI target : any.get(0)) {
                if (FogOfWar.isHidden(target)) continue;
                boolean tracked = false;
                for (ShipAPI ship : selected) {
                    if (tracks(ship, target, hasDatalink(ship))) {
                        tracked = true;
                        break;
                    }
                }
                if (!tracked || !viewport.isNearViewport(target.getLocation(), 300f)) continue;
                boolean craft = isSmallCraft(target);
                GL11.glColor4f(0.4f, 1f, 0.6f, craft ? 0.6f : 0.85f);
                brackets(target.getLocation().x, target.getLocation().y, target.getCollisionRadius() + (craft ? 8f : 20f));
            }
        }

        // Jamming clouds
        if (TcoSettings.jamEnabled) {
            GL11.glPointSize(2f);
            for (Map.Entry<ShipAPI, Jammer> e : jammers.entrySet()) {
                Jammer j = e.getValue();
                if (!j.active) continue;
                ShipAPI ship = e.getKey();
                float r = jamRadius(ship);
                if (!viewport.isNearViewport(ship.getLocation(), r * 1.5f)) continue;
                if (ship.getOwner() == 0) {
                    GL11.glLineWidth(1f);
                    GL11.glColor4f(0.65f, 0.45f, 1f, 0.18f);
                    circle(ship.getLocation().x, ship.getLocation().y, r, 64);
                    continue;
                }
                float cx = ship.getLocation().x, cy = ship.getLocation().y;
                GL11.glColor4f(0.85f, 0.3f, 0.6f, 0.06f);
                GL11.glBegin(GL11.GL_TRIANGLE_FAN);
                GL11.glVertex2f(cx, cy);
                for (int i = 0; i <= 40; i++) {
                    double a = Math.PI * 2.0 * i / 40;
                    GL11.glVertex2f(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
                }
                GL11.glEnd();
                GL11.glBegin(GL11.GL_POINTS);
                for (int i = 0; i < 90; i++) {
                    double a = Math.random() * Math.PI * 2.0;
                    float d = r * (float) Math.sqrt(Math.random());
                    float bright = 0.25f + 0.5f * (1f - d / r);
                    GL11.glColor4f(1f, 0.45f, 0.75f, bright * (0.4f + 0.6f * (float) Math.random()));
                    GL11.glVertex2f(cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d);
                }
                GL11.glEnd();
            }
        }

        // Jamming lines
        GL11.glLineWidth(1f);
        for (Map.Entry<ShipAPI, List<MissileAPI>> e : jamming.entrySet()) {
            ShipAPI ship = e.getKey();
            if (ship.getOwner() != 0 && FogOfWar.isHidden(ship)) continue;
            for (MissileAPI m : e.getValue()) {
                Track t = missiles.get(m);
                if (t == null || !engine.isEntityInPlay(m)) continue;
                float flicker = 0.25f + 0.25f * (float) Math.random();
                if (t.spoofed) GL11.glColor4f(0.85f, 0.5f, 1f, flicker + 0.25f);
                else GL11.glColor4f(0.6f, 0.45f, 1f, flicker);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(ship.getLocation().x, ship.getLocation().y);
                GL11.glVertex2f(m.getLocation().x, m.getLocation().y);
                GL11.glEnd();
            }
        }
        GL11.glPopAttrib();
    }

    private static void circle(float x, float y, float r, int steps) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < steps; i++) {
            double a = Math.PI * 2.0 * i / steps;
            GL11.glVertex2f(x + (float) Math.cos(a) * r, y + (float) Math.sin(a) * r);
        }
        GL11.glEnd();
    }

    private static void dashed(float x, float y, float r) {
        GL11.glBegin(GL11.GL_LINES);
        int steps = 96;
        for (int i = 0; i < steps; i += 2) {
            double a0 = Math.PI * 2.0 * i / steps, a1 = Math.PI * 2.0 * (i + 1) / steps;
            GL11.glVertex2f(x + (float) Math.cos(a0) * r, y + (float) Math.sin(a0) * r);
            GL11.glVertex2f(x + (float) Math.cos(a1) * r, y + (float) Math.sin(a1) * r);
        }
        GL11.glEnd();
    }

    private static void brackets(float x, float y, float r) {
        float l = r * 0.35f;
        GL11.glBegin(GL11.GL_LINES);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                GL11.glVertex2f(x + sx * r, y + sy * r);
                GL11.glVertex2f(x + sx * (r - l), y + sy * r);
                GL11.glVertex2f(x + sx * r, y + sy * r);
                GL11.glVertex2f(x + sx * r, y + sy * (r - l));
            }
        }
        GL11.glEnd();
    }

    /**
     * Lead guidance for radar missiles: steers for the intercept point with a damped turn; flies straight
     * without a track.
     */
    static final class Lead implements MissileAIPlugin, GuidedMissileAI {
        private final MissileAPI missile;
        ShipAPI target;
        boolean coast;

        Lead(MissileAPI missile) {
            this.missile = missile;
        }

        @Override
        public void advance(float amount) {
            missile.giveCommand(ShipCommand.ACCELERATE);
            if (coast || target == null) return;
            Vector2f m = missile.getLocation();
            Vector2f aim = intercept(m, Math.max(50f, missile.getMaxSpeed()), target.getLocation(), target.getVelocity());
            float desired = (float) Math.toDegrees(Math.atan2(aim.y - m.y, aim.x - m.x));
            if (TcoSettings.missileAvoidAsteroids) {
                Vector2f around = aroundAsteroid(m, desired, (float) Math.sqrt((aim.x - m.x) * (aim.x - m.x) + (aim.y - m.y) * (aim.y - m.y)));
                if (around != null) desired = (float) Math.toDegrees(Math.atan2(around.y - m.y, around.x - m.x));
            }
            float diff = angleDiff(desired, missile.getFacing());
            float spin = missile.getAngularVelocity();
            float stop = spin * Math.abs(spin) / (2f * Math.max(1f, missile.getTurnAcceleration()));
            float error = diff - stop;
            if (error > 0.5f) missile.giveCommand(ShipCommand.TURN_LEFT);
            else if (error < -0.5f) missile.giveCommand(ShipCommand.TURN_RIGHT);
        }

        private Vector2f aroundAsteroid(Vector2f m, float heading, float toTarget) {
            CombatEngineAPI engine = Global.getCombatEngine();
            if (engine == null) return null;
            List<CombatEntityAPI> asteroids = engine.getAsteroids();
            if (asteroids == null || asteroids.isEmpty()) return null;
            float look = Math.min(toTarget, Math.max(250f, missile.getVelocity().length() * 1.2f));
            double h = Math.toRadians(heading);
            float dx = (float) Math.cos(h), dy = (float) Math.sin(h);
            CombatEntityAPI nearest = null;
            float nearestAlong = Float.MAX_VALUE, nearestSide = 0f;
            for (CombatEntityAPI a : asteroids) {
                float rx = a.getLocation().x - m.x, ry = a.getLocation().y - m.y;
                float reach = look + a.getCollisionRadius();
                if (rx * rx + ry * ry > reach * reach) continue;
                float along = rx * dx + ry * dy;
                if (along <= 0f || along > look) continue;
                float side = dx * ry - dy * rx;
                float clearance = a.getCollisionRadius() + missile.getCollisionRadius() + 25f;
                if (Math.abs(side) >= clearance || along >= nearestAlong) continue;
                nearest = a;
                nearestAlong = along;
                nearestSide = side;
            }
            if (nearest == null) return null;
            // Pass on the side it's already on
            float sign = nearestSide >= 0f ? -1f : 1f;
            float clear = nearest.getCollisionRadius() + missile.getCollisionRadius() + 45f;
            return new Vector2f(nearest.getLocation().x - dy * clear * sign, nearest.getLocation().y + dx * clear * sign);
        }

        static Vector2f intercept(Vector2f from, float speed, Vector2f at, Vector2f vel) {
            float rx = at.x - from.x, ry = at.y - from.y;
            float a = vel.x * vel.x + vel.y * vel.y - speed * speed;
            float b = 2f * (rx * vel.x + ry * vel.y);
            float c = rx * rx + ry * ry;
            float t;
            if (Math.abs(a) < 0.001f) {
                t = b != 0f ? -c / b : 0f;
            } else {
                float disc = b * b - 4f * a * c;
                if (disc < 0f) return new Vector2f(at);
                float s = (float) Math.sqrt(disc);
                float t1 = (-b - s) / (2f * a), t2 = (-b + s) / (2f * a);
                t = Math.min(t1, t2) > 0f ? Math.min(t1, t2) : Math.max(t1, t2);
            }
            if (!(t > 0f)) return new Vector2f(at);
            t = Math.min(t, 6f);
            return new Vector2f(at.x + vel.x * t, at.y + vel.y * t);
        }

        static float angleDiff(float a, float b) {
            float d = (a - b) % 360f;
            if (d > 180f) d -= 360f;
            if (d < -180f) d += 360f;
            return d;
        }

        @Override
        public CombatEntityAPI getTarget() {
            return target;
        }

        @Override
        public void setTarget(CombatEntityAPI target) {
            if (target instanceof ShipAPI) this.target = (ShipAPI) target;
        }
    }

    /** Radar missile without a track: flies straight. */
    private static final class Coast implements MissileAIPlugin, GuidedMissileAI {
        private final MissileAPI missile;

        Coast(MissileAPI missile) {
            this.missile = missile;
        }

        @Override
        public void advance(float amount) {
            missile.giveCommand(ShipCommand.ACCELERATE);
        }

        @Override
        public CombatEntityAPI getTarget() {
            return null;
        }

        @Override
        public void setTarget(CombatEntityAPI target) {
        }
    }
}
