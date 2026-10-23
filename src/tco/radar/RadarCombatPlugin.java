package tco.radar;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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

import tco.AiUtil;
import tco.TcoSettings;
import tco.Typing;
import tco.ammo.AmmoRules;
import tco.battle.DeathThroes;
import tco.AiUtil;
import tco.ai.AiAwareness;
import tco.hullmods.ArmConversion;
import tco.hullmods.CombatInformationCenter;
import tco.hullmods.PassiveSensors;
import tco.hullmods.RadarRemoval;
import tco.missile.Guidance;
import tco.patches.Patches;
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
        // Anti-radiation: homes on emissions the missile hears, not on tracks
        boolean arm;
        MissileAPI missile;
        // Terminal sprint started
        boolean sprint;
    }

    private static final String VANILLA_MISSILE_AI = "com.fs.starfarer.combat.ai.missile.MissileAI";

    static final class Ping {
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
    // Passive Sensor Suite: emitters each side hears, and where each side's ESM puts them (loc off by up to size)
    private final List<Set<ShipAPI>> heard = new ArrayList<Set<ShipAPI>>();
    private final List<Map<ShipAPI, Ping>> esmPings = new ArrayList<Map<ShipAPI, Ping>>();
    // Real time of the last RTS draw, so the debug overlays know whether to draw themselves
    private long lastRtsDraw;
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
            heard.add(new HashSet<ShipAPI>());
            esmPings.add(new HashMap<ShipAPI, Ping>());
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
        // The command ship's antenna farm
        if (CombatInformationCenter.level(ship) > 0) rcs *= TcoSettings.cicRcsMult;
        return rcs;
    }

    /** Range against an RCS-1 target. */
    public static float range(ShipAPI ship) {
        return FogOfWar.sightRange(ship) * TcoSettings.radarRangeMult;
    }

    public static float power(ShipAPI ship) {
        if (SensorPickets.isPicket(ship)) return TcoSettings.picketRadarPower;
        float p = ship.isStation() ? TcoSettings.radarStationPower : 1f;
        if (ship.getVariant() != null && ship.getVariant().hasHullMod(AESA_ID)) p *= TcoSettings.aesaRadarPower;
        int level = CombatInformationCenter.level(ship);
        if (level == 2) p *= TcoSettings.cicRadarPower;
        else if (level == 1) p *= TcoSettings.auxCicRadarPower;
        return p;
    }

    // Command ships on sector scan, with the cone's aim in degrees
    private final Map<ShipAPI, Float> sectorAim = new HashMap<ShipAPI, Float>();
    // Player ships switched by hand (AI ones pick their scan themselves)
    private final Set<ShipAPI> scanChosen = new HashSet<ShipAPI>();
    // Sector scans aimed by hand (the EW panel's AIM): degrees from the ship's facing, so the cone turns with the ship
    private final Map<ShipAPI, Float> sectorBearing = new HashMap<ShipAPI, Float>();

    /**
     * Aims a command ship's sector scan towards a clicked point, kept as a direction relative to the ship: the cone
     * turns with it instead of staying on the point. Null goes back to aiming by itself.
     */
    public static void setSectorAim(CombatEngineAPI engine, ShipAPI ship, Vector2f point) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null || CombatInformationCenter.level(ship) == 0) return;
        if (point == null) {
            plugin.sectorBearing.remove(ship);
            return;
        }
        float bearing = (float) Math.toDegrees(Math.atan2(point.y - ship.getLocation().y, point.x - ship.getLocation().x));
        plugin.sectorBearing.put(ship, Lead.angleDiff(bearing, ship.getFacing()));
    }

    public static boolean hasSectorAim(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        return plugin != null && plugin.sectorBearing.containsKey(ship);
    }

    public boolean isSector(ShipAPI ship) {
        return sectorAim.containsKey(ship);
    }

    /** Power towards a point: on sector scan, x sectorPowerMult inside the cone and x sectorOutsideMult outside. */
    public float powerToward(ShipAPI radar, Vector2f point) {
        float p = power(radar);
        Float aim = sectorAim.get(radar);
        if (aim == null || point == null) return p;
        float bearing = (float) Math.toDegrees(Math.atan2(point.y - radar.getLocation().y, point.x - radar.getLocation().x));
        boolean inside = Math.abs(Lead.angleDiff(bearing, aim)) <= TcoSettings.sectorArc / 2f;
        return p * (inside ? TcoSettings.sectorPowerMult : TcoSettings.sectorOutsideMult);
    }

    /** Wide <-> sector scan for a CIC or Auxiliary CIC; the new mode's name, or null for other ships. */
    public static String toggleScan(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null || CombatInformationCenter.level(ship) == 0) return null;
        plugin.scanChosen.add(ship);
        if (plugin.sectorAim.remove(ship) != null) return "wide scan";
        plugin.sectorAim.put(ship, ship.getFacing());
        return "sector scan";
    }

    /**
     * Sector aim: the flown ship's at the mouse; others at the nearest enemy their side knows, else towards the enemy's
     * edge. AI command ships use sector scan while their side knows of no enemy, wide scan once it does.
     */
    private void aimSectors() {
        AiAwareness awareness = AiAwareness.get(engine);
        float now = engine.getTotalElapsedTime(false);
        for (ShipAPI ship : engine.getShips()) {
            if (CombatInformationCenter.level(ship) == 0 || !ship.isAlive() || ship.isHulk()) continue;
            int side = ship.getOwner();
            if (side < 0 || side > 1) continue;
            ShipAPI nearest = null;
            float best = Float.MAX_VALUE;
            if (awareness != null) {
                for (Map.Entry<ShipAPI, AiAwareness.Known> e : awareness.memory(side).entrySet()) {
                    if (now - e.getValue().time > TcoSettings.aiBlindGraceSeconds) continue;
                    float d = dist(ship.getLocation(), e.getValue().loc);
                    if (d < best) {
                        best = d;
                        nearest = e.getKey();
                    }
                }
            }
            boolean playerSide = engine.getPlayerShip() != null && side == engine.getPlayerShip().getOwner() && !ship.isAlly();
            if (!playerSide && !scanChosen.contains(ship)) {
                if (nearest == null) {
                    if (!sectorAim.containsKey(ship)) sectorAim.put(ship, ship.getFacing());
                } else {
                    sectorAim.remove(ship);
                }
            }
            if (!sectorAim.containsKey(ship)) continue;
            Float relative = sectorBearing.get(ship);
            if (relative != null) {
                sectorAim.put(ship, ship.getFacing() + relative);
                continue;
            }
            Vector2f to;
            if (AiUtil.playerFlying(engine, ship) && ship.getMouseTarget() != null) to = ship.getMouseTarget();
            else if (nearest != null) to = nearest.getLocation();
            else to = new Vector2f(ship.getLocation().x, side == 1 ? -engine.getMapHeight() / 2f : engine.getMapHeight() / 2f);
            sectorAim.put(ship, (float) Math.toDegrees(Math.atan2(to.y - ship.getLocation().y, to.x - ship.getLocation().x)));
        }
        sectorAim.keySet().removeIf(s -> !s.isAlive() || !engine.isEntityInPlay(s));
        sectorBearing.keySet().removeIf(s -> !s.isAlive() || !engine.isEntityInPlay(s));
        scanChosen.removeIf(s -> !engine.isEntityInPlay(s));
    }

    public static boolean hasDatalink(ShipAPI ship) {
        if (SensorPickets.isPicket(ship)) return true;
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(DATALINK_ID);
    }

    private static boolean hasRadar(ShipAPI ship) {
        if (SensorPickets.isPicket(ship)) return ship.getOwner() >= 0 && ship.getOwner() <= 1;
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

    /** Everything this side's radars hold. */
    public Set<ShipAPI> contacts(int side) {
        return side >= 0 && side < any.size() ? any.get(side) : null;
    }

    /** Enemy radars and jammers this side's Passive Sensor Suites hear (not tracks). */
    public Set<ShipAPI> heard(int side) {
        return side >= 0 && side < heard.size() ? heard.get(side) : null;
    }

    /** Anti-Radiation Missile Conversion, with the ESM it needs. */
    public static boolean isArm(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(ArmConversion.ID)
                && PassiveSensors.has(ship);
    }

    /**
     * How far a passive receiver hears this ship: esmRangeMult x the reach of its radar while on (against a capital),
     * a running jammer's radius, or ecmRange while its ECM is jamming missiles; 0 when it is quiet.
     */
    private float emissionReach(ShipAPI ship) {
        return emissionReach(ship, null);
    }

    /** As above, towards a listener (a sector scan is louder inside its cone); null: the loudest direction. */
    private float emissionReach(ShipAPI ship, Vector2f toward) {
        float p = toward != null ? powerToward(ship, toward)
                : power(ship) * (sectorAim.containsKey(ship) ? Math.max(1f, TcoSettings.sectorPowerMult) : 1f);
        float reach = emitting(ship) ? range(ship) * (float) Math.sqrt(p) : 0f;
        Jammer j = jammers.get(ship);
        if (j != null && j.active) reach = Math.max(reach, areaRadius(ship));
        if (jamming.containsKey(ship)) reach = Math.max(reach, ecmRangeOf(ship));
        return reach * TcoSettings.esmRangeMult;
    }

    /** A passive receiver at this point hears the ship. */
    boolean hears(Vector2f from, ShipAPI emitter) {
        if (from == null || emitter == null || !findable(emitter)) return false;
        float reach = emissionReach(emitter, from);
        return reach > 0f && dist(from, emitter.getLocation())
                <= reach * (float) Math.sqrt(nebulaMult(from, emitter.getLocation()));
    }

    /** Listening rather than looking: ESM, and no radar or the radar switched off. */
    private boolean listening(ShipAPI ship) {
        return PassiveSensors.has(ship) && (!hasRadar(ship) || silent.contains(ship));
    }

    /**
     * Whether radar missiles from this ship at the target home on its emissions (anti-radiation) rather than on a
     * radar track. Only ARM ships. Listening: always. Radar on: the lock decides (made on the ESM monitor, or on the
     * radar scope); with no lock on the target, a radar track first, emissions otherwise.
     */
    private boolean armHoming(ShipAPI owner, ShipAPI target) {
        if (!isArm(owner)) return false;
        if (listening(owner) || !hasRadar(owner)) return true;
        if (target != null && target == radarLock.get(owner)) return esmLock.contains(owner);
        return target == null || !(tracks(owner, target, hasDatalink(owner)) || commandTrack(owner, target, owner.getLocation()));
    }

    /** What a radar launcher needs before it fires: a radar track, or for anti-radiation homing, the target heard. */
    private boolean canEngage(ShipAPI owner, ShipAPI target, boolean link) {
        if (armHoming(owner, target)) return hears(owner.getLocation(), target);
        return tracks(owner, target, link) || commandTrack(owner, target, owner.getLocation());
    }

    /**
     * Third-party guidance: a friendly command ship whose own radar holds the target, within its guidance reach of the
     * point (the launcher before launch, the missile in flight), guides the missile. Not while the side is decapitated.
     */
    public boolean commandTrack(ShipAPI friend, ShipAPI target, Vector2f at) {
        if (friend == null || target == null || at == null || TcoSettings.cicGuidanceRange <= 0f) return false;
        int side = friend.getOwner();
        if (side < 0 || side > 1 || AiAwareness.isDecapitated(engine, side)) return false;
        for (Map.Entry<ShipAPI, Set<ShipAPI>> e : own.entrySet()) {
            ShipAPI command = e.getKey();
            int level = CombatInformationCenter.level(command);
            if (level == 0 || command.getOwner() != side || !e.getValue().contains(target) || !emitting(command)) continue;
            float reach = level == 2 ? TcoSettings.cicGuidanceRange : TcoSettings.auxGuidanceRange;
            if (dist(command.getLocation(), at) <= reach) return true;
        }
        return false;
    }

    /** A heard emitter as the ESM monitor shows it. */
    static final class EsmContact {
        ShipAPI ship;
        Vector2f guess;
        float size;
        float time;
        float reach;
        boolean radar, jammer, ecm;
        // Its radar holds the viewing ship
        boolean tracksMe;
    }

    /** The viewer's side's ESM contacts, with what each one is emitting now. */
    List<EsmContact> esmContacts(ShipAPI viewer) {
        List<EsmContact> list = new ArrayList<EsmContact>();
        int side = viewer.getOwner();
        if (side < 0 || side > 1) return list;
        for (Map.Entry<ShipAPI, Ping> e : esmPings.get(side).entrySet()) {
            ShipAPI ship = e.getKey();
            if (!heard.get(side).contains(ship)) continue;
            EsmContact c = new EsmContact();
            c.ship = ship;
            c.guess = new Vector2f(e.getValue().loc);
            c.size = e.getValue().size;
            c.time = e.getValue().time;
            c.reach = emissionReach(ship);
            c.radar = emitting(ship);
            Jammer j = jammers.get(ship);
            c.jammer = j != null && j.active;
            c.ecm = jamming.containsKey(ship);
            Set<ShipAPI> held = own.get(ship);
            c.tracksMe = c.radar && held != null && held.contains(viewer);
            list.add(c);
        }
        return list;
    }

    /** The flown ship listens rather than looks: ESM, and no radar or the radar switched off. */
    private boolean esmMode(ShipAPI ship) {
        return listening(ship);
    }

    /** Where this side's ESM puts a heard emitter, or null if it doesn't hear it. */
    public Vector2f esmGuess(int side, ShipAPI emitter) {
        if (side < 0 || side > 1) return null;
        Ping p = esmPings.get(side).get(emitter);
        return p == null || !heard.get(side).contains(emitter) ? null : new Vector2f(p.loc);
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
        return plugin.canEngage(owner, target, hasDatalink(owner));
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
        return ship != null && AiUtil.playerFlying(engine, ship) && !ship.getCustomData().containsKey(HELPER_KEY);
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

    /** Area jamming: the ECM Package's noise jammer, or a command ship's barrage. */
    public static boolean hasAreaJammer(ShipAPI ship) {
        return ship != null && !ship.isFighter() && (hasEcm(ship) || CombatInformationCenter.level(ship) > 0);
    }

    /** Area jamming radius: a command ship's barrage covers barrageRadiusMult times the jammer radius. */
    public static float areaRadius(ShipAPI ship) {
        return jamRadius(ship) * (CombatInformationCenter.level(ship) > 0 ? TcoSettings.barrageRadiusMult : 1f);
    }

    private static float areaStrength(ShipAPI ship) {
        return CombatInformationCenter.level(ship) > 0 ? TcoSettings.barrageNoiseMult : 1f;
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
        if (plugin == null || !hasAreaJammer(ship)) return null;
        Jammer j = plugin.jammerOf(ship);
        j.mode = (j.mode + 1) % 3;
        j.nextDecision = 0f;
        return j.mode == 0 ? "automatic" : j.mode == 1 ? "always on" : "off";
    }

    /** Ship card status, or null without ECM. */
    public static String jammerStatus(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null || !TcoSettings.jamEnabled || !hasAreaJammer(ship)) return null;
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

    /** Switches a ship's radar off or on; true if that changed anything. */
    public static boolean setSilent(CombatEngineAPI engine, ShipAPI ship, boolean off) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null || ship == null || !hasRadar(ship)) return false;
        return off ? plugin.silent.add(ship) : plugin.silent.remove(ship);
    }

    /** Area jamming mode for the EW panel: 0 auto, 1 on, 2 off. */
    public static int areaJamMode(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        Jammer j = plugin == null ? null : plugin.jammers.get(ship);
        return j == null ? 0 : j.mode;
    }

    public static boolean areaJamActive(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        Jammer j = plugin == null ? null : plugin.jammers.get(ship);
        return j != null && j.active;
    }

    public static void setAreaJamMode(CombatEngineAPI engine, ShipAPI ship, int mode) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null || !hasAreaJammer(ship)) return;
        Jammer j = plugin.jammerOf(ship);
        j.mode = Math.max(0, Math.min(2, mode));
        j.nextDecision = 0f;
    }

    // Missile jamming per ship: 0 auto (keeps a power reserve), 1 on, 2 off
    private final Map<ShipAPI, Integer> missileJam = new HashMap<ShipAPI, Integer>();

    public static int missileJamMode(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        Integer m = plugin == null ? null : plugin.missileJam.get(ship);
        return m == null ? 0 : m;
    }

    public static void setMissileJamMode(CombatEngineAPI engine, ShipAPI ship, int mode) {
        RadarCombatPlugin plugin = get(engine);
        if (plugin == null || !isEcmShip(ship)) return;
        plugin.missileJam.put(ship, Math.max(0, Math.min(2, mode)));
    }

    /** Radar missiles this ship is jamming now. */
    public static int missilesJammedBy(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        List<MissileAPI> list = plugin == null ? null : plugin.jamming.get(ship);
        return list == null ? 0 : list.size();
    }

    public boolean isEmitting(ShipAPI ship) {
        return emitting(ship);
    }

    /** This ship's own radar holds a ship of the given side. */
    public boolean holdsAnyOf(ShipAPI radarShip, int side) {
        Set<ShipAPI> mine = own.get(radarShip);
        if (mine == null) return false;
        for (ShipAPI s : mine) {
            if (s.getOwner() == side) return true;
        }
        return false;
    }

    /** This side's ESM hears the ship. */
    public boolean isHeardBy(int side, ShipAPI ship) {
        return side >= 0 && side <= 1 && heard.get(side).contains(ship);
    }

    /** The enemy ships this radar holds itself (not through Datalink). */
    public Set<ShipAPI> heldBy(ShipAPI radarShip) {
        Set<ShipAPI> mine = own.get(radarShip);
        return mine == null ? Collections.<ShipAPI>emptySet() : mine;
    }

    /** Guided radar and anti-radiation missiles homing on ships of the side: missile -> target. */
    public Map<MissileAPI, ShipAPI> homingOn(int side) {
        Map<MissileAPI, ShipAPI> map = new HashMap<MissileAPI, ShipAPI>();
        for (Map.Entry<MissileAPI, Track> e : missiles.entrySet()) {
            Track t = e.getValue();
            if (t.state != State.GUIDED || t.target == null || t.target.getOwner() != side) continue;
            if (!engine.isEntityInPlay(e.getKey())) continue;
            map.put(e.getKey(), t.target);
        }
        return map;
    }

    /** Anti-radiation missiles homing on ships of the side. */
    public int armsInbound(int side) {
        int n = 0;
        for (Map.Entry<MissileAPI, Track> e : missiles.entrySet()) {
            Track t = e.getValue();
            if (t.arm && t.state == State.GUIDED && t.target != null && t.target.getOwner() == side && engine.isEntityInPlay(e.getKey())) n++;
        }
        return n;
    }

    /** Guided radar missiles (not anti-radiation) homing on ships of the side within radius of a point. */
    public int radarMissilesInbound(int side, Vector2f near, float radius) {
        int n = 0;
        for (Track t : missiles.values()) {
            if (t.state == State.DUMB || t.arm || t.target == null || t.target.getOwner() != side) continue;
            if (dist(t.target.getLocation(), near) <= radius) n++;
        }
        return n;
    }

    /** Radar switched off; false without radar. */
    public static boolean isSilent(CombatEngineAPI engine, ShipAPI ship) {
        RadarCombatPlugin plugin = get(engine);
        return plugin != null && plugin.silent.contains(ship);
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null) return;
        dropHiddenTarget();
        if (!TcoSettings.radarEnabled) return;
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
        aimSectors();
        boolean[] down = { AiAwareness.isDecapitated(engine, 0), AiAwareness.isDecapitated(engine, 1) };
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
                    float signal = powerToward(radar, target.getLocation()) * r * r / d2 * nebulaMult(radar.getLocation(), target.getLocation());
                    // Spot jammed: only burns through up close
                    if (EwCombatPlugin.isScrambled(engine, radar)) {
                        float burn = TcoSettings.burnThroughFraction * r;
                        if (d2 > burn * burn) signal *= TcoSettings.scramblePowerMult;
                    }
                    if (signal * rcs >= threshold) {
                        Set<ShipAPI> mine = own.get(radar);
                        if (mine == null) {
                            mine = new HashSet<ShipAPI>();
                            own.put(radar, mine);
                        }
                        mine.add(target);
                        found = true;
                    }
                    // The network is down while the side has lost its command ship
                    if (hasDatalink(radar) && !down[side]) linked += signal;
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
        listen(now);
        for (Map<ShipAPI, Ping> map : Arrays.asList(pings, esmPings.get(0), esmPings.get(1))) {
            float interval = map == pings ? TcoSettings.radarPingInterval : TcoSettings.esmRefreshSeconds;
            for (Iterator<Map.Entry<ShipAPI, Ping>> it = map.entrySet().iterator(); it.hasNext();) {
                Map.Entry<ShipAPI, Ping> e = it.next();
                if (!e.getKey().isAlive() || e.getKey().isHulk() || now - e.getValue().time > interval * 2.5f) {
                    it.remove();
                }
            }
        }
        silent.removeIf(s -> !engine.isEntityInPlay(s));
        holdToggled.removeIf(s -> !engine.isEntityInPlay(s));
    }

    /**
     * Passive Sensor Suite: an enemy radar that is on is heard out to esmRangeMult x its reach against a capital, an
     * active jammer out to esmRangeMult x its radius. The player side gets a contact off by up to esmErrorFraction of
     * the distance to the nearest listener.
     */
    private void listen(float now) {
        for (Set<ShipAPI> s : heard) s.clear();
        if (TcoSettings.esmRangeMult <= 0f) return;
        List<ShipAPI> listeners = new ArrayList<ShipAPI>();
        for (ShipAPI ship : engine.getShips()) {
            if (!PassiveSensors.has(ship) || !ship.isAlive() || ship.isHulk() || ship.isFighter()
                    || DeathThroes.isDying(ship) || ship.getOwner() < 0 || ship.getOwner() > 1) {
                continue;
            }
            listeners.add(ship);
        }
        if (listeners.isEmpty()) return;
        for (ShipAPI emitter : engine.getShips()) {
            if (!findable(emitter)) continue;
            float reach = emissionReach(emitter);
            if (reach <= 0f) continue;
            for (int side = 0; side <= 1; side++) {
                if (emitter.getOwner() == side) continue;
                float nearest = Float.MAX_VALUE;
                List<ShipAPI> ears = new ArrayList<ShipAPI>();
                for (ShipAPI ear : listeners) {
                    if (ear.getOwner() != side) continue;
                    float d = dist(ear.getLocation(), emitter.getLocation());
                    float toEar = sectorAim.containsKey(emitter) ? emissionReach(emitter, ear.getLocation()) : reach;
                    if (d > toEar * (float) Math.sqrt(nebulaMult(ear.getLocation(), emitter.getLocation()))) continue;
                    if (d < nearest) nearest = d;
                    if (ears.size() < 8) ears.add(ear);
                }
                if (nearest == Float.MAX_VALUE) continue;
                heard.get(side).add(emitter);
                Ping p = esmPings.get(side).get(emitter);
                if (p != null && now - p.time < TcoSettings.esmRefreshSeconds) continue;
                if (p == null) {
                    p = new Ping();
                    esmPings.get(side).put(emitter, p);
                }
                float error = Math.max(TcoSettings.esmMinError, TcoSettings.esmErrorFraction * nearest * directionFinding(side, emitter, ears));
                double a = Math.random() * Math.PI * 2.0;
                float off = error * (float) Math.sqrt(Math.random());
                p.loc.set(emitter.getLocation().x + (float) Math.cos(a) * off, emitter.getLocation().y + (float) Math.sin(a) * off);
                p.size = error;
                p.time = now;
            }
        }
    }

    /**
     * Direction finding: a side with a command ship combines its listeners' bearings. The widest angle between two
     * of them, seen from the emitter, shrinks the error: x (1 - dfReduction x sin(angle)), down to 1 - dfReduction
     * for listeners at right angles (dfReductionCic, dfReductionAux).
     */
    private float directionFinding(int side, ShipAPI emitter, List<ShipAPI> ears) {
        int level = AiAwareness.commandLevel(engine, side);
        if (level == 0 || ears.size() < 2 || AiAwareness.isDecapitated(engine, side)) return 1f;
        float best = 0f;
        Vector2f e = emitter.getLocation();
        for (int i = 0; i < ears.size(); i++) {
            for (int k = i + 1; k < ears.size(); k++) {
                Vector2f a = ears.get(i).getLocation(), b = ears.get(k).getLocation();
                float ax = a.x - e.x, ay = a.y - e.y, bx = b.x - e.x, by = b.y - e.y;
                float la = (float) Math.hypot(ax, ay), lb = (float) Math.hypot(bx, by);
                if (la < 1f || lb < 1f) continue;
                float sin = Math.abs(ax * by - ay * bx) / (la * lb);
                best = Math.max(best, sin);
            }
        }
        float reduction = level == 2 ? TcoSettings.dfReductionCic : TcoSettings.dfReductionAux;
        return Math.max(0.05f, 1f - reduction * best);
    }

    /** While piloting: the side's ESM contacts that the fog hides, as in RTS mode. */
    private void drawPilotEsm(ViewportAPI viewport, int side) {
        if (side < 0 || side > 1 || esmPings.get(side).isEmpty()) return;
        float now = engine.getTotalElapsedTime(false);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(1.5f);
        for (Map.Entry<ShipAPI, Ping> e : esmPings.get(side).entrySet()) {
            if (!heard.get(side).contains(e.getKey()) || !FogOfWar.isHidden(e.getKey())) continue;
            esmCircle(viewport, e.getValue(), now, 1f, 0.8f, 0.3f, null);
        }
        GL11.glPopAttrib();
    }

    /** The heard emitter whose ESM circle is under (or nearest) the point, within the circle plus 300. */
    private ShipAPI esmContactAt(ShipAPI ship, Vector2f point) {
        int side = ship.getOwner();
        if (point == null || side < 0 || side > 1) return null;
        ShipAPI best = null;
        float bestD = Float.MAX_VALUE;
        for (Map.Entry<ShipAPI, Ping> e : esmPings.get(side).entrySet()) {
            if (!heard.get(side).contains(e.getKey())) continue;
            float d = dist(point, e.getValue().loc);
            if (d <= e.getValue().size + 300f && d < bestD) {
                bestD = d;
                best = e.getKey();
            }
        }
        return best;
    }

    private void esmCircle(ViewportAPI viewport, Ping p, float now, float r, float g, float b, ShipAPI real) {
        if (!viewport.isNearViewport(p.loc, p.size * 1.5f)) return;
        float age = (now - p.time) / Math.max(0.1f, TcoSettings.esmRefreshSeconds);
        float alpha = Math.max(0.2f, 0.75f - 0.3f * age);
        GL11.glColor4f(r, g, b, alpha);
        dashed(p.loc.x, p.loc.y, p.size);
        float c = Math.min(40f, p.size * 0.3f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(p.loc.x - c, p.loc.y);
        GL11.glVertex2f(p.loc.x + c, p.loc.y);
        GL11.glVertex2f(p.loc.x, p.loc.y - c);
        GL11.glVertex2f(p.loc.x, p.loc.y + c);
        if (real != null) {
            // Debug: from the guess to the real ship
            GL11.glColor4f(r, g, b, alpha * 0.5f);
            GL11.glVertex2f(p.loc.x, p.loc.y);
            GL11.glVertex2f(real.getLocation().x, real.getLocation().y);
        }
        GL11.glEnd();
    }

    /**
     * Debug overlays (the Debug settings tab): every ESM guess of both sides with a line to the real ship, enemy
     * radar ranges, and how far each enemy radar would find the selected ships.
     */
    private void drawDebug(ViewportAPI viewport, Collection<ShipAPI> selected) {
        if (!TcoSettings.radarEnabled) return;
        boolean esm = TcoSettings.debugEsmAlways, ranges = TcoSettings.debugEnemyRadarRange;
        boolean detection = TcoSettings.debugRadarDetection && selected != null && !selected.isEmpty();
        if (!esm && !ranges && !detection) return;
        int mine = engine.getPlayerShip() != null ? engine.getPlayerShip().getOwner() : 0;
        if (mine < 0 || mine > 1) mine = 0;
        float now = engine.getTotalElapsedTime(false);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(1.5f);
        if (esm) {
            // Yours amber, the enemy's (where it thinks your ships are) magenta
            for (int side = 0; side <= 1; side++) {
                boolean own = side == mine;
                for (Map.Entry<ShipAPI, Ping> e : esmPings.get(side).entrySet()) {
                    if (!heard.get(side).contains(e.getKey())) continue;
                    esmCircle(viewport, e.getValue(), now, 1f, own ? 0.8f : 0.3f, own ? 0.3f : 0.9f, e.getKey());
                }
            }
        }
        if (ranges) {
            GL11.glLineWidth(1f);
            for (ShipAPI ship : engine.getShips()) {
                if (ship.getOwner() == mine || !hasRadar(ship) || !ship.isAlive() || ship.isHulk()) continue;
                float r = range(ship) * (float) Math.sqrt(power(ship));
                if (!viewport.isNearViewport(ship.getLocation(), r)) continue;
                boolean on = emitting(ship);
                GL11.glColor4f(1f, 0.3f, 0.3f, on ? 0.3f : 0.1f);
                dashed(ship.getLocation().x, ship.getLocation().y, r);
            }
        }
        if (detection) {
            GL11.glLineWidth(1.5f);
            for (ShipAPI target : selected) {
                if (target == null || !target.isAlive() || target.getOwner() != mine) continue;
                float rcs = rcs(target);
                float threshold = 1f + noiseAt(target);
                for (ShipAPI radar : engine.getShips()) {
                    if (radar.getOwner() == mine || radar.getOwner() < 0 || radar.getOwner() > 1 || !emitting(radar)) continue;
                    // The distance at which this radar alone finds this ship (RCS, jamming, nebulae)
                    float r = rcs <= 0f ? 0f : range(radar) * (float) Math.sqrt(power(radar) * rcs / threshold
                            * nebulaMult(radar.getLocation(), target.getLocation()));
                    if (r <= 0f || !viewport.isNearViewport(radar.getLocation(), r)) continue;
                    boolean finds = dist(radar.getLocation(), target.getLocation()) <= r;
                    if (finds) GL11.glColor4f(1f, 0.2f, 0.2f, 0.7f);
                    else GL11.glColor4f(1f, 0.6f, 0.2f, 0.35f);
                    circle(radar.getLocation().x, radar.getLocation().y, r, 64);
                }
                // On the enemy's radar now (its own or datalinked): red brackets
                if (any.get(1 - mine).contains(target)) {
                    GL11.glColor4f(1f, 0.2f, 0.2f, 0.9f);
                    brackets(target.getLocation().x, target.getLocation().y, target.getCollisionRadius() + 30f);
                }
            }
        }
        GL11.glPopAttrib();
    }

    private float noiseAt(ShipAPI target) {
        if (!TcoSettings.jamEnabled || jammers.isEmpty()) return 0f;
        float noise = 0f, cap = TcoSettings.jamNoiseMax;
        for (Map.Entry<ShipAPI, Jammer> e : jammers.entrySet()) {
            ShipAPI j = e.getKey();
            if (!e.getValue().active || j.getOwner() != target.getOwner()) continue;
            float f = 1f - dist(j.getLocation(), target.getLocation()) / areaRadius(j);
            if (f <= 0f) continue;
            noise += TcoSettings.jamNoise * areaStrength(j) * f;
            cap = Math.max(cap, TcoSettings.jamNoiseMax * areaStrength(j));
        }
        return Math.min(cap, noise);
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
            if (!hasAreaJammer(ship) || ship.isStationModule()) continue;
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
            // A command ship's barrage costs more
            float perSecond = CombatInformationCenter.level(ship) > 0 ? TcoSettings.barragePowerPerSecond : TcoSettings.jamPowerPerSecond;
            if (j.active && power != null && power.capacityOf(ship) > 0f
                    && !power.draw(ship, power.capacityOf(ship) * perSecond * amount)) {
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
            boolean flown = AiUtil.playerFlying(engine, ship);
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
                boolean arm = isArm(owner);
                if (group != null && group.isAutofiring()) {
                    AutofireAIPlugin ai = group.getAutofirePlugin(w);
                    if (ai != null) target = ai.getTargetShip();
                    // ARMs on either side: the game's AI only targets what its side sees, ESM hears further
                    if (target == null && (ownSide || arm) && !ship.isHoldFire() && TcoSettings.radarFireBeyondSight) {
                        ShipAPI far = trackBeyondSight(ship, owner, link, w);
                        if (far != null && fireAt(ship, w, far)) {
                            launchTarget.put(ship, far);
                            target = far;
                        }
                    }
                } else {
                    target = ship.getShipTarget();
                    if (target == null && (arm || ownSide) && !flown && !ship.isHoldFire() && TcoSettings.radarFireBeyondSight) {
                        // An AI-flown ship with nothing targeted (the game's AI only targets what it sees): the nearest
                        // radar track in reach, or for ARMs the nearest heard emitter. Your side's manual missile
                        // groups too, not only autofire ones
                        ShipAPI far = trackBeyondSight(ship, owner, link, w);
                        if (far != null && fireAt(ship, w, far)) {
                            launchTarget.put(ship, far);
                            target = far;
                        }
                    }
                    if (target == null && manual) {
                        ShipAPI locked = radarLock.get(ship);
                        if (locked != null && locked.isAlive() && !locked.isHulk()) target = locked;
                    }
                    if (target == null && manual) {
                        if (!cursorChecked) {
                            cursorChecked = true;
                            // ARMs: the ESM contact under the cursor first
                            cursorTarget = isArm(owner) && listening(owner) ? esmContactAt(ship, ship.getMouseTarget()) : null;
                            if (cursorTarget == null) cursorTarget = nearestEnemyTo(ship, ship.getMouseTarget(), antiCraft(w));
                        }
                        target = cursorTarget;
                    }
                }
                // Don't waste missiles on small craft
                boolean wasted = target != null && isSmallCraft(target) && !antiCraft(w);
                boolean tracked = target != null && canEngage(owner, target, link);
                if (target == null || wasted || !tracked) {
                    w.setForceNoFireOneFrame(true);
                    if (TcoSettings.debugRadarLaunchLog) logHeld(ship, owner, w, target, manual, wasted);
                    if (manual && refusal == null) {
                        refusal = target == null ? "No target: aim at a ship, or select one (R)"
                                : wasted ? "Radar missiles aren't wasted on small craft"
                                : armHoming(owner, target) ? "No emissions heard from " + target.getHullSpec().getHullName()
                                : "No radar track on " + target.getHullSpec().getHullName();
                    }
                } else if (manual) {
                    launchTarget.put(ship, target);
                }
            }
            if (refusal != null && Mouse.isButtonDown(0)) hint(ship, refusal);
        }
    }

    private final Map<ShipAPI, Float> launchLogged = new WeakHashMap<ShipAPI, Float>();

    /** Debug: why a radar launcher was held back (once a second per ship). */
    private void logHeld(ShipAPI ship, ShipAPI owner, WeaponAPI w, ShipAPI target, boolean manual, boolean wasted) {
        float now = engine.getTotalElapsedTime(true);
        Float last = launchLogged.get(ship);
        if (last != null && now - last < 1f) return;
        launchLogged.put(ship, now);
        StringBuilder b = new StringBuilder("TCO launch held: ").append(ship.getHullSpec().getHullName())
                .append(" (side ").append(ship.getOwner()).append(manual ? ", manual" : ", auto")
                .append(armHoming(owner, target) ? ", ARM" : ", radar").append(") ").append(w.getId()).append(" -> ");
        if (target == null) {
            WeaponGroupAPI group = ship.getWeaponGroupFor(w);
            b.append("no target (ship target ").append(ship.getShipTarget() == null ? "none" : ship.getShipTarget().getHullSpec().getHullName())
                    .append(", lock ").append(radarLock.get(ship) == null ? "none" : radarLock.get(ship).getHullSpec().getHullName())
                    .append(", group ").append(group == null ? "none" : group.isAutofiring() ? "autofire" : "manual")
                    .append(", hold fire ").append(ship.isHoldFire()).append(")");
        } else {
            float d = dist(owner.getLocation(), target.getLocation());
            Jammer j = jammers.get(target);
            b.append(target.getHullSpec().getHullName()).append(" at ").append(Math.round(d))
                    .append(wasted ? ", small craft" : "")
                    .append(", radar ").append(hasRadar(target) ? (silent.contains(target) ? "off" : emitting(target) ? "on" : "not emitting") : "none")
                    .append(", jammer ").append(j != null && j.active).append(", ecm ").append(jamming.containsKey(target))
                    .append(", heard within ").append(Math.round(emissionReach(target)))
                    .append(", hears ").append(hears(owner.getLocation(), target))
                    .append(", tracks ").append(tracks(owner, target, hasDatalink(owner)))
                    .append(", owner has ESM ").append(PassiveSensors.has(owner))
                    .append(" ARM mod ").append(owner.getVariant() != null && owner.getVariant().hasHullMod(ArmConversion.ID));
        }
        Global.getLogger(RadarCombatPlugin.class).info(b.toString());
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
            if (d > reach || !canEngage(owner, other, link)) continue;
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
    // Where each ship's lock was last tracked or seen
    private final Map<ShipAPI, Vector2f> lockSeen = new WeakHashMap<ShipAPI, Vector2f>();
    // Ships whose lock target was dropped because the track was lost in the fog
    private final Set<ShipAPI> lockDropped = new HashSet<ShipAPI>();

    /** Where the lock is drawn: on the target while tracked or in sight, else where the track was lost. */
    private Vector2f lockPoint(ShipAPI ship, ShipAPI t, boolean tracked) {
        Vector2f last = lockSeen.get(ship);
        boolean exact = tracked || !FogOfWar.isHidden(t);
        // Only heard: where the ESM puts it
        Vector2f guess = exact ? null : esmGuess(ship.getOwner(), t);
        if (last == null) {
            last = new Vector2f(exact || guess == null ? t.getLocation() : guess);
            lockSeen.put(ship, last);
        } else if (exact) {
            last.set(t.getLocation());
        } else if (guess != null) {
            last.set(guess);
        }
        return last;
    }

    /**
     * The game keeps the player's target on screen (reticle, target panel) wherever it goes, so a target that
     * slips into the fog without a radar track is dropped. A dropped radar lock is retargeted once tracked again.
     */
    private void dropHiddenTarget() {
        ShipAPI ship = engine.getPlayerShip();
        if (ship == null) return;
        ShipAPI owner = radarOwner(ship);
        boolean link = hasDatalink(owner);
        ShipAPI target = ship.getShipTarget();
        if (FogOfWar.isActive() && target != null && target.getOwner() != ship.getOwner() && FogOfWar.isHidden(target)
                && !(TcoSettings.radarEnabled && tracks(owner, target, link))) {
            try {
                ship.setShipTarget(null);
                if (target == radarLock.get(ship)) lockDropped.add(ship);
            } catch (RuntimeException e) {
            }
            return;
        }
        if (!lockDropped.contains(ship)) return;
        ShipAPI locked = radarLockOf(engine, ship);
        if (locked == null || ship.getShipTarget() != null) {
            lockDropped.remove(ship);
        } else if (tracks(owner, locked, link) || !FogOfWar.isHidden(locked)) {
            lockDropped.remove(ship);
            try {
                ship.setShipTarget(locked);
            } catch (RuntimeException e) {
            }
        }
    }

    // Ships whose lock was made on the ESM monitor (anti-radiation homing), not the radar scope
    private final Set<ShipAPI> esmLock = new HashSet<ShipAPI>();

    private void lock(ShipAPI ship, ShipAPI target, boolean esm) {
        if (esm) esmLock.add(ship);
        else esmLock.remove(ship);
        if (radarLock.put(ship, target) != target) lockSeen.remove(ship);
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
        plugin.esmLock.remove(ship);
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
        // Not in RTS mode: the debug overlays and ESM contacts draw here, for the flown ship
        if (engine != null && System.nanoTime() - lastRtsDraw > 200_000_000L) {
            ShipAPI flown = engine.getPlayerShip();
            drawDebug(viewport, flown == null ? null : Collections.singletonList(flown));
            if (flown != null && TcoSettings.radarEnabled && FogOfWar.isActive() && !TcoSettings.debugEsmAlways) {
                drawPilotEsm(viewport, flown.getOwner());
            }
        }
        ShipAPI ship = engine == null ? null : engine.getPlayerShip();
        ShipAPI t = ship == null ? null : radarLockOf(engine, ship);
        if (t == null || !TcoSettings.radarEnabled) return;
        ShipAPI owner = radarOwner(ship);
        boolean tracked = tracks(owner, t, hasDatalink(owner));
        boolean heardOnly = !tracked && esmGuess(ship.getOwner(), t) != null;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2f);
        if (tracked) GL11.glColor4f(1f, 0.85f, 0.25f, 0.9f);
        else if (heardOnly) GL11.glColor4f(1f, 0.7f, 0.3f, 0.8f);
        else GL11.glColor4f(0.6f, 0.6f, 0.6f, 0.6f);
        float r = t.getCollisionRadius() + 35f;
        Vector2f at = lockPoint(ship, t, tracked);
        brackets(at.x, at.y, r);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(at.x, at.y + r * 0.5f);
        GL11.glVertex2f(at.x + r * 0.5f, at.y);
        GL11.glVertex2f(at.x, at.y - r * 0.5f);
        GL11.glVertex2f(at.x - r * 0.5f, at.y);
        GL11.glEnd();
        GL11.glPopAttrib();
    }

    // ship -> world x, y, time painted, frozen by a lock (1)
    private final Map<ShipAPI, float[]> painted = new WeakHashMap<ShipAPI, float[]>();
    private float lastScopeTime = -1f;
    private ShipAPI lastScopeShip;
    private static final float SCOPE_SWEEP_DEG = 120f;

    private final List<Object[]> scopeBlips = new ArrayList<Object[]>();
    private final EsmMonitor esmMonitor = new EsmMonitor(this);
    // The ESM monitor was drawn last frame (a square, for clicks)
    private boolean monitorShown;
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
        if (ship == null || !ship.isAlive() || ship.getCustomData().containsKey(HELPER_KEY)) return null;
        if (!hasRadar(ship) && !PassiveSensors.has(ship)) return null;
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
        if (ship == null || Typing.active()) {
            scopeLmb = scopeRmb = false;
            return;
        }
        float cx = scopeX(), cy = scopeY(), r = TcoSettings.radarScopeSize / 2f;
        boolean switchShown = hasSwitch(ship);
        for (InputEventAPI event : events) {
            if (event.isLMBUpEvent()) scopeLmb = false;
            if (event.isRMBUpEvent()) scopeRmb = false;
            if (event.isConsumed() || !(event.isLMBDownEvent() || event.isRMBDownEvent() || event.isLMBUpEvent()
                    || event.isRMBUpEvent())) {
                continue;
            }
            float mx = Global.getSettings().getMouseX(), my = Global.getSettings().getMouseY();
            // RADAR | ESM switch above the panel
            if (switchShown && Math.abs(mx - cx) <= r && my >= cy + r + SWITCH_GAP && my <= cy + r + SWITCH_GAP + SWITCH_H) {
                if (event.isLMBDownEvent()) {
                    scopeLmb = true;
                    setListening(ship, mx > cx);
                }
                event.consume();
                continue;
            }
            float reach = r + 10f;
            if (monitorShown ? Math.abs(mx - cx) > reach || Math.abs(my - cy) > reach
                    : (mx - cx) * (mx - cx) + (my - cy) * (my - cy) > reach * reach) {
                continue;
            }
            if (event.isLMBDownEvent()) scopeLmb = true;
            if (event.isRMBDownEvent()) scopeRmb = true;
            if (event.isRMBDownEvent() && unlockRadar(engine, ship)) {
                engine.getCombatUI().addMessage(0, new Color(255, 215, 110), "Radar lock released");
            }
            if (event.isLMBDownEvent()) {
                ShipAPI best = null;
                float bestD = Float.MAX_VALUE;
                for (Object[] b : scopeBlips) {
                    ShipAPI s = (ShipAPI) b[0];
                    if (isSmallCraft(s)) continue;
                    float d = (float) Math.hypot(mx - (Float) b[1], my - (Float) b[2]);
                    float pick = b.length > 3 ? Math.max(14f, (Float) b[3] + 4f) : 14f;
                    if (d <= pick && d < bestD) {
                        best = s;
                        bestD = d;
                    }
                }
                if (best != null) {
                    // Locked on the ESM monitor: anti-radiation homing; on the radar scope: the radar track
                    lock(ship, best, monitorShown);
                    ShipAPI lockOwner = radarOwner(ship);
                    if (!monitorShown && tracks(lockOwner, best, hasDatalink(lockOwner))) {
                        engine.getCombatUI().addMessage(0, new Color(255, 215, 110), "Radar lock: " + best.getHullSpec().getHullName()
                                + ", " + Math.round(dist(ship.getLocation(), best.getLocation())) + " away");
                    } else {
                        // Heard, not tracked: no exact range to give
                        engine.getCombatUI().addMessage(0, new Color(255, 190, 90), "ESM lock: " + best.getHullSpec().getHullName()
                                + (armHoming(lockOwner, best) && hears(lockOwner.getLocation(), best)
                                        ? ", anti-radiation missiles can fire" : ", no radar track"));
                    }
                }
            }
            event.consume();
        }
        // Fire and shield read the mouse directly, so block them while a scope press is held
        if (scopeLmb) ship.blockCommandForOneFrame(ShipCommand.FIRE);
        if (scopeRmb) ship.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK);
    }

    private boolean scopeLmb, scopeRmb;
    private static final float SWITCH_GAP = 6f;
    private static final float SWITCH_H = 18f;
    private LabelAPI switchRadarLabel, switchEsmLabel;

    /** Ships with a radar and ESM get the RADAR | ESM switch. */
    private static boolean hasSwitch(ShipAPI ship) {
        return ship != null && hasRadar(ship) && PassiveSensors.has(ship);
    }

    /** ESM: the radar goes off (listening); RADAR: back on. The same state N toggles. */
    private void setListening(ShipAPI ship, boolean esm) {
        if (esm == silent.contains(ship)) return;
        if (esm) silent.add(ship);
        else silent.remove(ship);
        engine.getCombatUI().addMessage(0, esm ? new Color(255, 200, 100) : new Color(120, 230, 150),
                esm ? "Radar off: listening on ESM" : "Radar on");
    }

    private void drawSwitch(ShipAPI ship) {
        float cx = scopeX(), cy = scopeY(), r = TcoSettings.radarScopeSize / 2f;
        boolean esm = silent.contains(ship);
        float y0 = cy + r + SWITCH_GAP, y1 = y0 + SWITCH_H;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (int i = 0; i < 2; i++) {
            boolean right = i == 1;
            boolean active = right == esm;
            float x0 = right ? cx + 2f : cx - r, x1 = right ? cx + r : cx - 2f;
            float cr = right ? 1f : 0.3f, cg = right ? 0.75f : 0.9f, cb = right ? 0.3f : 0.45f;
            GL11.glColor4f(cr * 0.25f, cg * 0.25f, cb * 0.25f, active ? 0.85f : 0.5f);
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glVertex2f(x0, y0);
            GL11.glVertex2f(x1, y0);
            GL11.glVertex2f(x1, y1);
            GL11.glVertex2f(x0, y1);
            GL11.glEnd();
            GL11.glLineWidth(active ? 2f : 1f);
            GL11.glColor4f(cr, cg, cb, active ? 0.95f : 0.35f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(x0, y0);
            GL11.glVertex2f(x1, y0);
            GL11.glVertex2f(x1, y1);
            GL11.glVertex2f(x0, y1);
            GL11.glEnd();
        }
        GL11.glPopAttrib();
        try {
            if (switchRadarLabel == null) switchRadarLabel = Global.getSettings().createLabel("RADAR", Fonts.DEFAULT_SMALL);
            if (switchEsmLabel == null) switchEsmLabel = Global.getSettings().createLabel("ESM", Fonts.DEFAULT_SMALL);
            switchRadarLabel.setColor(esm ? new Color(90, 140, 100) : new Color(140, 255, 170));
            switchEsmLabel.setColor(esm ? new Color(255, 210, 120) : new Color(150, 120, 80));
            float lh = 14f;
            switchRadarLabel.getPosition().setSize(60f, lh);
            switchRadarLabel.getPosition().setLocation(cx - r / 2f - switchRadarLabel.computeTextWidth("RADAR") / 2f,
                    y0 + (SWITCH_H - lh) / 2f);
            switchRadarLabel.render(1f);
            switchEsmLabel.getPosition().setSize(60f, lh);
            switchEsmLabel.getPosition().setLocation(cx + r / 2f - switchEsmLabel.computeTextWidth("ESM") / 2f,
                    y0 + (SWITCH_H - lh) / 2f);
            switchEsmLabel.render(1f);
        } catch (RuntimeException e) {
        }
    }

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
        boolean radar = hasRadar(ship);
        boolean on = radar && !silent.contains(ship);
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
        // Spot jammed: snow on the screen
        boolean scrambled = EwCombatPlugin.isScrambled(engine, ship);
        if (scrambled) {
            GL11.glPointSize(2f);
            GL11.glBegin(GL11.GL_POINTS);
            for (int i = 0; i < 220; i++) {
                double a = Math.random() * Math.PI * 2.0;
                float d = r * (float) Math.sqrt(Math.random());
                GL11.glColor4f(0.6f, 1f, 0.7f, 0.15f + 0.45f * (float) Math.random());
                GL11.glVertex2f(cx + (float) Math.cos(a) * d, cy + (float) Math.sin(a) * d);
            }
            GL11.glEnd();
        }
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
        Float aim = sectorAim.get(ship);
        if (aim != null && on) {
            // Sector scan: the cone's edges
            GL11.glColor4f(0.35f, 1f, 0.5f, 0.45f);
            GL11.glBegin(GL11.GL_LINES);
            for (int k = -1; k <= 1; k += 2) {
                double a = Math.toRadians(aim + k * TcoSettings.sectorArc / 2f);
                GL11.glVertex2f(cx, cy);
                GL11.glVertex2f(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r);
            }
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
        // ESM contacts the radar doesn't show: amber circles (on the rim when beyond the scope), click to lock
        int side = ship.getOwner();
        if (side >= 0 && side <= 1) {
            GL11.glLineWidth(1f);
            for (Map.Entry<ShipAPI, Ping> e : esmPings.get(side).entrySet()) {
                ShipAPI other = e.getKey();
                if (!heard.get(side).contains(other) || painted.containsKey(other)) continue;
                Ping p = e.getValue();
                float dx = p.loc.x - c.x, dy = p.loc.y - c.y;
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                boolean rim = d > range;
                float bx = rim ? cx + dx / d * (r - 5f) : cx + dx / range * r;
                float by = rim ? cy + dy / d * (r - 5f) : cy + dy / range * r;
                float er = rim ? 4f : Math.max(4f, Math.min(r * 0.4f, p.size / range * r));
                boolean isLock = other == locked;
                GL11.glColor4f(1f, isLock ? 0.9f : 0.75f, 0.3f, isLock ? 1f : 0.75f);
                dashed(bx, by, er);
                GL11.glPointSize(3f);
                GL11.glBegin(GL11.GL_POINTS);
                GL11.glVertex2f(bx, by);
                GL11.glEnd();
                scopeBlips.add(new Object[] {other, bx, by, er});
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

        String text = !radar ? "NO RADAR  -  ESM" : on && scrambled ? "RADAR JAMMED  -  burn-through "
                + Math.round(TcoSettings.burnThroughFraction * range(ship)) : on ? "RADAR  " + Math.round(range) : "RADAR OFF (N)";
        try {
            if (scopeLabel == null) scopeLabel = Global.getSettings().createLabel(text, Fonts.DEFAULT_SMALL);
            if (!text.equals(scopeText)) {
                scopeLabel.setText(text);
                scopeText = text;
            }
            scopeLabel.setColor(on && scrambled ? new Color(255, 110, 170) : on ? new Color(120, 230, 150)
                    : !radar ? new Color(255, 200, 90) : new Color(255, 170, 90));
            scopeLabel.getPosition().setSize(TcoSettings.radarScopeSize, 16f);
            scopeLabel.getPosition().setLocation(cx - r + 6f, cy - r - 18f);
            scopeLabel.render(1f);
            if (locked != null) {
                boolean held = tracks(owner, locked, link);
                Vector2f guess = held ? null : esmGuess(ship.getOwner(), locked);
                String lock = "LOCK  " + locked.getHullSpec().getHullName() + "  "
                        + (held ? Math.round(dist(c, lockPoint(ship, locked, true)) / 10f) * 10 + ""
                        : guess != null ? "ESM ~" + Math.round(dist(c, guess) / 100f) * 100 : "(track lost)");
                if (lockLabel == null) lockLabel = Global.getSettings().createLabel(lock, Fonts.DEFAULT_SMALL);
                if (!lock.equals(lockText)) {
                    lockLabel.setText(lock);
                    lockText = lock;
                }
                lockLabel.setColor(held ? new Color(255, 215, 110) : guess != null ? new Color(255, 190, 90) : new Color(170, 170, 170));
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
        monitorShown = scope != null && esmMode(scope);
        if (monitorShown) {
            esmMonitor.draw(engine, scope, scopeX(), scopeY(), TcoSettings.radarScopeSize, scopeBlips,
                    radarLockOf(engine, scope), hasRadar(scope));
        } else if (scope != null) {
            drawScope(scope);
        }
        else scopeBlips.clear();
        if (scope != null && hasSwitch(scope)) drawSwitch(scope);
        ShipAPI ship = engine == null ? null : engine.getPlayerShip();
        ShipAPI t = ship == null ? null : radarLockOf(engine, ship);
        if (t == null || !TcoSettings.radarEnabled) return;
        ShipAPI owner = radarOwner(ship);
        boolean tracked = tracks(owner, t, hasDatalink(owner));
        Vector2f at = lockPoint(ship, t, tracked);
        if (viewport.isNearViewport(at, -50f)) return;
        // Off-screen lock arrow
        float w = Global.getSettings().getScreenWidth(), h = Global.getSettings().getScreenHeight();
        float sx = viewport.convertWorldXtoScreenX(at.x), sy = viewport.convertWorldYtoScreenY(at.y);
        float cx = w / 2f, cy = h / 2f;
        float dx = sx - cx, dy = sy - cy;
        float scale = Math.min((w / 2f - 40f) / Math.max(1f, Math.abs(dx)), (h / 2f - 40f) / Math.max(1f, Math.abs(dy)));
        float ax = cx + dx * scale, ay = cy + dy * scale;
        double a = Math.atan2(dy, dx);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        if (tracked) GL11.glColor4f(1f, 0.85f, 0.25f, 0.9f);
        else if (esmGuess(ship.getOwner(), t) != null) GL11.glColor4f(1f, 0.7f, 0.3f, 0.8f);
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
        // With the sight patch the engine fog asks SideSight, which reads the contacts directly
        if (!TcoSettings.radarFeedsAi || Patches.aiFog()) return;
        // Only the enemy side's fog: the player side's also drives the HUD and reticles, so it stays
        // sight-only
        int playerSide = engine.getPlayerShip() != null ? engine.getPlayerShip().getOwner() : 0;
        for (int side = 0; side <= 1; side++) {
            FogOfWarAPI fog = engine.getFogOfWar(side);
            if (fog == null) continue;
            // Targets a command ship sent carriers' fighters at, so they engage them
            for (ShipAPI target : CommandNetwork.directedTargets(engine, side)) {
                if (target.isAlive()) fog.revealAroundPoint(target, target.getLocation().x, target.getLocation().y, AI_REVEAL_RADIUS);
            }
            if (side == playerSide) continue;
            for (ShipAPI target : any.get(side)) {
                // Only what has been relayed to the side
                if (!target.isAlive() || !AiAwareness.knows(engine, side, target)) continue;
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
                t.missile = missile;
                t.nextCheck = now;
                missiles.put(missile, t);
                // Replace vanilla's pursuit AI with lead guidance (other mods' AIs are left alone)
                MissileAIPlugin own = missile.getUnwrappedMissileAI();
                ShipAPI checked = t.source != null ? launchTarget.get(t.source) : null;
                if (checked != null && (!checked.isAlive() || checked.isHulk())) checked = null;
                // Radar or anti-radiation homing, as decided for the target it was fired at
                t.arm = armHoming(t.source, checked != null ? checked : targetOf(missile));
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
            sprint(missile, t);
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
                } else if (now - t.since >= (t.arm ? TcoSettings.armGraceSeconds : TcoSettings.radarGraceSeconds)) {
                    t.state = State.DUMB;
                    t.original = null;
                }
            }
        }
    }

    /**
     * Terminal sprint: a guided radar missile within missileSprintRange of its target goes to missileSprintSpeedMult
     * speed and missileSprintAccelMult acceleration for the rest of its flight, so point defence and shields get less
     * warning at the end of a long flight.
     */
    private void sprint(MissileAPI missile, Track t) {
        if (t.sprint || !TcoSettings.missileSprintEnabled || t.state != State.GUIDED) return;
        ShipAPI target = t.lead != null ? t.lead.target : t.target;
        if (target == null || !target.isAlive()) return;
        float reach = TcoSettings.missileSprintRange + target.getCollisionRadius();
        if (dist(missile.getLocation(), target.getLocation()) > reach) return;
        t.sprint = true;
        try {
            missile.getEngineStats().getMaxSpeed().modifyMult(SPRINT_ID, TcoSettings.missileSprintSpeedMult);
            missile.getEngineStats().getAcceleration().modifyMult(SPRINT_ID, TcoSettings.missileSprintAccelMult);
        } catch (RuntimeException e) {
            return;
        }
        if (!FogOfWar.isDrawnHidden(target) || missile.getOwner() == 0) {
            engine.addHitParticle(missile.getLocation(), missile.getVelocity(), 30f, 0.8f, 0.2f, new Color(255, 230, 180));
        }
    }

    private static final String SPRINT_ID = "tco_sprint";

    private boolean held(Track t, ShipAPI target) {
        // An ARM's own seeker: it homes while it hears the target, and ECM only feeds it
        if (t.arm) return t.missile != null && hears(t.missile.getLocation(), target);
        if (t.spoofed) return false;
        boolean launcher = t.source != null && t.source.isAlive() && !t.source.isHulk() && tracks(t.source, target, false);
        if (launcher) return true;
        // A command ship guiding it
        if (t.source != null && t.missile != null && commandTrack(t.source, target, t.missile.getLocation())) return true;
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
        if (missileDefence(ship)) {
            return CombatInformationCenter.level(ship) == 2 ? TcoSettings.missileDefenceChannelsCic : TcoSettings.missileDefenceChannelsAux;
        }
        HullSize size = ship.getHullSize();
        return size == HullSize.CAPITAL_SHIP ? TcoSettings.ecmCapital : size == HullSize.CRUISER ? TcoSettings.ecmCruiser
                : size == HullSize.DESTROYER ? TcoSettings.ecmDestroyer : TcoSettings.ecmFrigate;
    }

    private static boolean hasEcm(ShipAPI ship) {
        return ship.getVariant() != null && ship.getVariant().hasHullMod("ecm") && !ship.isFighter();
    }

    /** Command ships jam missiles harder: more channels, twice the range (missile defence). */
    private static boolean missileDefence(ShipAPI ship) {
        return CombatInformationCenter.level(ship) > 0;
    }

    /** Can jam incoming radar missiles: the ECM Package, or a command ship. */
    public static boolean isEcmShip(ShipAPI ship) {
        return ship != null && !ship.isFighter() && (hasEcm(ship) || missileDefence(ship));
    }

    /** Missile jamming switched on for this ship now: not OFF, and on AUTO only with a power reserve. */
    private boolean missileJamOn(ShipAPI ship, PowerCombatPlugin power) {
        Integer m = missileJam.get(ship);
        int mode = m == null || ship.getOwner() != 0 || ship.isAlly() ? 0 : m;
        if (mode == 2) return false;
        if (mode == 1 || power == null || power.capacityOf(ship) <= 0f) return true;
        return power.powerOf(ship) / power.capacityOf(ship) > TcoSettings.missileJamAutoMinPower;
    }

    private static float ecmRangeOf(ShipAPI ship) {
        return missileDefence(ship) ? TcoSettings.ecmRange * TcoSettings.missileDefenceRangeMult : TcoSettings.ecmRange;
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
                if (!ship.isAlive() || ship.isHulk() || !isEcmShip(ship) || DeathThroes.isDying(ship)) continue;
                if (!missileJamOn(ship, power)) continue;
                if (power != null && power.isOutOfPower(ship)) continue;
                List<MissileAPI> mine = new ArrayList<MissileAPI>();
                List<MissileAPI> near = new ArrayList<MissileAPI>();
                for (MissileAPI m : incoming) {
                    Track t = missiles.get(m);
                    if (t.jammer != null || t.arm || m.getOwner() == ship.getOwner() || t.target == null) continue;
                    float ecmReach = ecmRangeOf(ship);
                    if (dist(m.getLocation(), ship.getLocation()) > ecmReach) continue;
                    if (t.target == ship) mine.add(m);
                    else if (t.target.getOwner() == ship.getOwner()
                            && dist(t.target.getLocation(), ship.getLocation()) <= ecmReach) near.add(m);
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
                float perMissile = missileDefence(ship) ? TcoSettings.missileDefencePowerPerMissile : TcoSettings.ecmPowerPerMissile;
                float cost = power.capacityOf(ship) * perMissile * held.size() * amount;
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
        lastRtsDraw = System.nanoTime();
        drawDebug(viewport, selected);
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
            // Passive Sensor Suite contacts: somewhere in the dashed circle, unless radar already has it
            if (!TcoSettings.debugEsmAlways) {
                for (Map.Entry<ShipAPI, Ping> e : esmPings.get(0).entrySet()) {
                    if (!FogOfWar.isHidden(e.getKey()) || pings.containsKey(e.getKey())) continue;
                    esmCircle(viewport, e.getValue(), now, 1f, 0.8f, 0.3f, null);
                }
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
                Float aim = sectorAim.get(ship);
                if (aim == null) {
                    dashed(ship.getLocation().x, ship.getLocation().y, r);
                } else {
                    sectorRing(ship.getLocation(), aim, r * (float) Math.sqrt(TcoSettings.sectorPowerMult),
                            r * (float) Math.sqrt(TcoSettings.sectorOutsideMult));
                }
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
                float r = areaRadius(ship);
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

        // Spot jamming: a jagged line from each jammer to the radar it scrambles (ones involving your side, in sight)
        GL11.glLineWidth(1.5f);
        for (ShipAPI jammer : engine.getShips()) {
            for (ShipAPI t : EwCombatPlugin.spotTargetsOf(engine, jammer)) {
                if (jammer.getOwner() != 0 && t.getOwner() != 0) continue;
                if (FogOfWar.isDrawnHidden(jammer) || FogOfWar.isDrawnHidden(t)) continue;
                GL11.glColor4f(1f, 0.35f, 0.75f, 0.35f + 0.25f * (float) Math.random());
                GL11.glBegin(GL11.GL_LINE_STRIP);
                Vector2f a = jammer.getLocation(), b = t.getLocation();
                float nx = -(b.y - a.y), ny = b.x - a.x, len = (float) Math.max(1.0, Math.hypot(nx, ny));
                for (int i = 0; i <= 12; i++) {
                    float f = i / 12f, j = (i == 0 || i == 12) ? 0f : ((float) Math.random() - 0.5f) * 60f;
                    GL11.glVertex2f(a.x + (b.x - a.x) * f + nx / len * j, a.y + (b.y - a.y) * f + ny / len * j);
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

    static void circle(float x, float y, float r, int steps) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < steps; i++) {
            double a = Math.PI * 2.0 * i / steps;
            GL11.glVertex2f(x + (float) Math.cos(a) * r, y + (float) Math.sin(a) * r);
        }
        GL11.glEnd();
    }

    /** A sector scan's reach: the cone out to `far`, a dashed circle at `near` elsewhere. */
    static void sectorRing(Vector2f c, float aim, float far, float near) {
        float half = TcoSettings.sectorArc / 2f;
        dashed(c.x, c.y, near);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        double a0 = Math.toRadians(aim - half);
        GL11.glVertex2f(c.x, c.y);
        for (int i = 0; i <= 24; i++) {
            double a = Math.toRadians(aim - half + TcoSettings.sectorArc * i / 24f);
            GL11.glVertex2f(c.x + (float) Math.cos(a) * far, c.y + (float) Math.sin(a) * far);
        }
        GL11.glVertex2f(c.x, c.y);
        GL11.glEnd();
    }

    static void dashed(float x, float y, float r) {
        GL11.glBegin(GL11.GL_LINES);
        int steps = 96;
        for (int i = 0; i < steps; i += 2) {
            double a0 = Math.PI * 2.0 * i / steps, a1 = Math.PI * 2.0 * (i + 1) / steps;
            GL11.glVertex2f(x + (float) Math.cos(a0) * r, y + (float) Math.sin(a0) * r);
            GL11.glVertex2f(x + (float) Math.cos(a1) * r, y + (float) Math.sin(a1) * r);
        }
        GL11.glEnd();
    }

    static void brackets(float x, float y, float r) {
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
