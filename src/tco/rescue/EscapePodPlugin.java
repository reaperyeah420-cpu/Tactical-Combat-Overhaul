package tco.rescue;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.Msg;
import tco.TcoSettings;
import tco.ammo.AmmoRules;
import tco.ammo.ShuttleAI;
import tco.battle.DeathThroes;
import tco.boarding.BoardingCombatPlugin;
import tco.fob.InertAI;
import tco.hangar.HangarCombatPlugin;
import tco.repair.RepairCombatPlugin;
import tco.rts.FogOfWar;

/**
 * Escape pods from lost crewed ships, carrying a random share of crew and marines. Pods are neutral and
 * ignored by gunners but not stray fire; each side rescues its own by passing close or sending shuttles.
 * Only the player's survivors are accounted for ({@link PodLedger}, {@link PodResults}).
 */
public class EscapePodPlugin extends BaseEveryFrameCombatPlugin {

    public static final String POD_KEY = "tco_escape_pod";
    private static final String ENGINE_KEY = "tco_pod_plugin";

    public static EscapePodPlugin get(CombatEngineAPI engine) {
        Object plugin = engine == null ? null : engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof EscapePodPlugin ? (EscapePodPlugin) plugin : null;
    }

    /** Drifting pods of this ship's side. */
    public List<ShipAPI> podsFor(ShipAPI rescuer) {
        List<ShipAPI> out = new ArrayList<ShipAPI>();
        String side = side(rescuer);
        for (Pod pod : pods) {
            if (pod.side.equals(side) && pod.ship.isAlive() && !pod.ship.isHulk() && engine.isEntityInPlay(pod.ship)) out.add(pod.ship);
        }
        return out;
    }
    private static final String POD_VARIANT = "tco_escape_pod_Hull";
    private static final Color OWN = new Color(120, 255, 140);
    private static final Color ALLY = new Color(255, 220, 90);
    private static final Color ENEMY = new Color(255, 110, 90);

    private enum Leg { OUTBOUND, RETURN, LANDING }

    private static final class Pod {
        ShipAPI ship;
        String side;
        // real side; the pod ship itself is neutral (owner 100)
        int owner;
        boolean player;
        float crew;
        float marines;
        Vector2f drift;
        float blink;
        Shuttle shuttle;
    }

    private static final class Shuttle {
        ShipAPI ship;
        ShuttleAI ai;
        ShipAPI home;
        String side;
        boolean player;
        Pod target;
        float crew;
        float marines;
        Leg leg = Leg.OUTBOUND;
        float legTime;
    }

    /** Pods still to launch from a dying ship, at set times. */
    private static final class Plan {
        String side;
        boolean player;
        final List<float[]> pods = new ArrayList<float[]>();
        final List<Float> times = new ArrayList<Float>();
    }

    private CombatEngineAPI engine;
    private final Map<ShipAPI, Plan> plans = new WeakHashMap<ShipAPI, Plan>();
    private final Set<ShipAPI> ejected = Collections.newSetFromMap(new WeakHashMap<ShipAPI, Boolean>());
    // noted while alive, boarding clears a lost ship's count
    private final Map<ShipAPI, Integer> marinesSeen = new WeakHashMap<ShipAPI, Integer>();
    private final List<Pod> pods = new ArrayList<Pod>();
    private final List<Shuttle> shuttles = new ArrayList<Shuttle>();
    private float scanTimer;
    private float dispatchTimer;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        engine.getCustomData().put(ENGINE_KEY, this);
        TcoSettings.load();
        PodLedger.begin();
    }

    /**
     * Pod markers: the player's pods show a distress ping anywhere on the map, others a ring while in sight.
     */
    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (engine == null || pods.isEmpty() || !TcoSettings.podsEnabled || !TcoSettings.podDistressSignal) return;
        float mult = viewport.getViewMult();
        float now = engine.getTotalElapsedTime(true);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (Pod pod : pods) {
            ShipAPI ship = pod.ship;
            if (!ship.isAlive() || ship.isHulk()) continue;
            Vector2f at = ship.getLocation();
            if (!viewport.isNearViewport(at, 200f * mult)) continue;
            if (pod.player) {
                float pulse = 0.5f + 0.5f * (float) Math.sin(now * 4f + pod.blink * 6f);
                float r = (12f + 4f * pulse) * mult;
                GL11.glLineWidth(2f);
                GL11.glColor4f(OWN.getRed() / 255f, OWN.getGreen() / 255f, OWN.getBlue() / 255f, 0.6f + 0.35f * pulse);
                ring(at.x, at.y, r);
                GL11.glBegin(GL11.GL_LINES);
                for (int i = 0; i < 4; i++) {
                    double a = Math.PI / 2.0 * i + Math.PI / 4.0;
                    GL11.glVertex2f(at.x + (float) Math.cos(a) * r * 1.2f, at.y + (float) Math.sin(a) * r * 1.2f);
                    GL11.glVertex2f(at.x + (float) Math.cos(a) * r * 1.8f, at.y + (float) Math.sin(a) * r * 1.8f);
                }
                GL11.glEnd();
                // Distress ping
                float phase = ((now + pod.blink * 2f) % 2f) / 2f;
                GL11.glLineWidth(1f);
                GL11.glColor4f(OWN.getRed() / 255f, OWN.getGreen() / 255f, OWN.getBlue() / 255f, 0.5f * (1f - phase));
                ring(at.x, at.y, r + phase * 50f * mult);
            } else if (FogOfWar.isVisible(at)) {
                Color c = colorOf(pod);
                GL11.glLineWidth(1.5f);
                GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 0.55f);
                ring(at.x, at.y, 9f * mult);
            }
        }
        GL11.glPopAttrib();
    }

    private static void ring(float x, float y, float r) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2.0 * i / 24.0;
            GL11.glVertex2f(x + (float) Math.cos(a) * r, y + (float) Math.sin(a) * r);
        }
        GL11.glEnd();
    }

    private static String side(ShipAPI ship) {
        int owner = ship.isHulk() ? ship.getOriginalOwner() : ship.getOwner();
        return owner + (ship.isAlly() ? "a" : "");
    }

    private static boolean isPlayerSide(ShipAPI ship) {
        return ship.getOriginalOwner() == 0 && !ship.isAlly();
    }

    private static boolean isCraft(ShipAPI ship) {
        return ship.isFighter() || ship.isDrone() || ship.isShuttlePod() || AmmoRules.isShuttle(ship)
                || ship.getCustomData().containsKey(POD_KEY);
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || amount <= 0f || engine.isPaused() || !TcoSettings.podsEnabled) return;

        scanTimer -= amount;
        if (scanTimer <= 0f) {
            scanTimer = 0.25f;
            BoardingCombatPlugin boarding = BoardingCombatPlugin.get(engine);
            float now = engine.getTotalElapsedTime(false);
            for (ShipAPI ship : new ArrayList<ShipAPI>(engine.getShips())) {
                try {
                    if (!ship.isHulk()) {
                        if (DeathThroes.isDying(ship)) {
                            // Death throes: pods leave one by one
                            if (ejected.add(ship)) {
                                Plan plan = plan(ship);
                                if (plan != null) {
                                    float window = Math.max(0.5f, DeathThroes.timeLeft(ship) * 0.9f);
                                    for (int i = 0; i < plan.pods.size(); i++) plan.times.add(now + (float) Math.random() * window);
                                    Collections.sort(plan.times);
                                    plans.put(ship, plan);
                                }
                            }
                        } else if (boarding != null && !isCraft(ship)) {
                            marinesSeen.put(ship, boarding.marinesAboard(ship));
                        }
                        continue;
                    }
                    Plan left = plans.remove(ship);
                    if (left != null) {
                        // Wreck gone: the rest leave at once
                        for (float[] pod : left.pods) spawnPod(ship, left, pod);
                        continue;
                    }
                    if (!ejected.add(ship)) continue;
                    // No death throes: all at once
                    Plan plan = plan(ship);
                    if (plan != null) for (float[] pod : plan.pods) spawnPod(ship, plan, pod);
                } catch (RuntimeException e) {
                    Global.getLogger(EscapePodPlugin.class).warn("TCO: could not eject escape pods from " + ship.getName(), e);
                }
            }
        }
        releaseDue();
        updatePods(amount);
        dispatchTimer -= amount;
        if (dispatchTimer <= 0f) {
            dispatchTimer = 1f;
            dispatch();
        }
        updateShuttles(amount);

        float transitCrew = 0f;
        float transitMarines = 0f;
        for (Shuttle s : shuttles) {
            if (!s.player) continue;
            transitCrew += s.crew;
            transitMarines += s.marines;
        }
        PodLedger.update(transitCrew, transitMarines, driftingList());
    }

    private List<float[]> driftingList() {
        List<float[]> list = new ArrayList<float[]>();
        for (Pod pod : pods) if (pod.player) list.add(new float[] {pod.crew, pod.marines});
        return list;
    }

    private void releaseDue() {
        if (plans.isEmpty()) return;
        float now = engine.getTotalElapsedTime(false);
        Iterator<Map.Entry<ShipAPI, Plan>> it = plans.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ShipAPI, Plan> entry = it.next();
            Plan plan = entry.getValue();
            while (!plan.times.isEmpty() && plan.times.get(0) <= now && !plan.pods.isEmpty()) {
                plan.times.remove(0);
                try {
                    spawnPod(entry.getKey(), plan, plan.pods.remove(0));
                } catch (RuntimeException e) {
                    Global.getLogger(EscapePodPlugin.class).warn("TCO: could not launch an escape pod", e);
                }
            }
            if (plan.pods.isEmpty()) it.remove();
        }
    }

    private Plan plan(ShipAPI wreck) {
        // Only the original hull ejects, not its pieces
        if (isCraft(wreck) || wreck.isPiece() || wreck.getParentStation() != null || wreck.isStation()) return null;
        if (wreck.getCustomData().containsKey("tco_captured") || BoardingCombatPlugin.isAutomated(wreck)) return null;
        FleetMemberAPI member = wreck.getFleetMember();
        if (member == null) return null;
        float crew = member.getCrewComposition() == null ? 0f : member.getCrewComposition().getCrew();
        if (crew <= 0f) crew = member.getMinCrew();
        Integer seen = marinesSeen.get(wreck);
        float marines = seen == null ? 0f : seen;
        // Survivor share capped at 75% (vanilla wreck recovery adds up to 25%)
        float low = Math.max(0f, Math.min(0.75f, TcoSettings.podSurvivorsMin));
        float high = Math.max(low, Math.min(0.75f, TcoSettings.podSurvivorsMax));
        float share = low + (float) Math.random() * (high - low);
        float crewSaved = crew * share;
        float marinesSaved = marines * share;
        if (crewSaved + marinesSaved < 1f) return null;
        HullSize size = wreck.getHullSize();
        int count = size == HullSize.CAPITAL_SHIP ? TcoSettings.podsCapital : size == HullSize.CRUISER ? TcoSettings.podsCruiser
                : size == HullSize.DESTROYER ? TcoSettings.podsDestroyer : TcoSettings.podsFrigate;
        count = Math.max(1, count);
        // Uneven loads
        float[] weights = new float[count];
        float weightSum = 0f;
        for (int i = 0; i < count; i++) {
            weights[i] = 0.2f + (float) Math.random();
            weightSum += weights[i];
        }
        Plan plan = new Plan();
        plan.side = side(wreck);
        plan.player = isPlayerSide(wreck);
        for (int i = 0; i < count; i++) {
            plan.pods.add(new float[] {crewSaved * weights[i] / weightSum, marinesSaved * weights[i] / weightSum});
        }
        if (plan.player) {
            Msg.send(engine, Msg.PLAYER, wreck.getName() + ": " + Math.round(crewSaved + marinesSaved)
                    + " survivors are making for the escape pods.", "pods-" + wreck.getId(), 4f);
        }
        return plan;
    }

    private void spawnPod(ShipAPI wreck, Plan plan, float[] load) {
        CombatFleetManagerAPI fleet = engine.getFleetManager(wreck.getOriginalOwner());
        {
            Vector2f at = DeathThroes.pointOnHull(wreck);
            float dx = at.x - wreck.getLocation().x;
            float dy = at.y - wreck.getLocation().y;
            double a = (dx * dx + dy * dy) > 1f ? Math.atan2(dy, dx) : Math.random() * Math.PI * 2.0;
            ShipAPI ship;
            boolean quiet = fleet.isSuppressDeploymentMessages();
            fleet.setSuppressDeploymentMessages(true);
            try {
                try {
                    ship = fleet.spawnShipOrWing(POD_VARIANT, at, (float) Math.toDegrees(a));
                } catch (RuntimeException e) {
                    ship = fleet.spawnShipOrWing(AmmoRules.SHUTTLE_VARIANT, at, (float) Math.toDegrees(a));
                }
            } finally {
                fleet.setSuppressDeploymentMessages(quiet);
            }
            fleet.removeDeployed(ship, false);
            ship.setCustomData(POD_KEY, Boolean.TRUE);
            ship.setCustomData(AmmoRules.SHUTTLE_KEY, Boolean.TRUE);
            ship.setCustomData("tco_ammo_done", Boolean.TRUE);
            ship.setCollisionClass(CollisionClass.FIGHTER);
            ship.setInvalidTransferCommandTarget(true);
            ship.setShipAI(new InertAI());
            ship.setHoldFire(true);
            float speed = TcoSettings.podDriftSpeed * (0.6f + (float) Math.random() * 0.8f);
            Pod pod = new Pod();
            pod.ship = ship;
            pod.owner = wreck.getOriginalOwner();
            // Neutral, like a wreck
            ship.setOwner(100);
            pod.side = plan.side;
            pod.player = plan.player;
            pod.crew = load[0];
            pod.marines = load[1];
            pod.drift = new Vector2f((float) Math.cos(a) * speed, (float) Math.sin(a) * speed);
            pod.blink = (float) Math.random();
            // Thrown clear, then drifting
            ship.getVelocity().set(wreck.getVelocity().x + pod.drift.x * 4f, wreck.getVelocity().y + pod.drift.y * 4f);
            pods.add(pod);
            engine.addHitParticle(at, ship.getVelocity(), 30f, 1f, 0.2f, colorOf(pod));
        }
    }

    private static Color colorOf(Pod pod) {
        return pod.player ? OWN : pod.side.endsWith("a") ? ALLY : ENEMY;
    }

    private void updatePods(float amount) {
        Iterator<Pod> it = pods.iterator();
        while (it.hasNext()) {
            Pod pod = it.next();
            ShipAPI ship = pod.ship;
            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship) || strayHit(pod)) {
                // Lost
                if (pod.player) {
                    PodLedger.lost(pod.crew, pod.marines);
                    if (ship.isHulk() || !ship.isAlive() || engine.isEntityInPlay(ship)) {
                        Msg.send(engine, Msg.PLAYER, "An escape pod was destroyed.", "pod-lost", 5f);
                    }
                }
                if (pod.shuttle != null) pod.shuttle.target = null;
                if (engine.isEntityInPlay(ship)) engine.removeEntity(ship);
                it.remove();
                continue;
            }
            // Neutral and phased; enemy stray fire still kills it (strayHit)
            ship.setBeingIgnored(true);
            ship.setPhased(true);
            Vector2f v = ship.getVelocity();
            float blend = Math.min(1f, amount * 0.8f);
            v.set(v.x + (pod.drift.x - v.x) * blend, v.y + (pod.drift.y - v.y) * blend);
            ship.setAngularVelocity(ship.getAngularVelocity() * (1f - Math.min(1f, amount * 0.5f)) + 8f * amount);
            pod.blink -= amount;
            if (pod.blink <= 0f) {
                pod.blink = 1.2f;
                if (engine.getViewport().isNearViewport(ship.getLocation(), 200f)) {
                    engine.addHitParticle(ship.getLocation(), v, 22f, 1f, 0.25f, colorOf(pod));
                }
            }
            // Scooped up by a passing friendly ship
            if (pod.shuttle == null) {
                ShipAPI picker = nearestPicker(pod);
                if (picker != null) {
                    rescued(pod.crew, pod.marines, pod.player, picker.getName());
                    remove(pod);
                    it.remove();
                }
            }
        }
    }

    private ShipAPI nearestPicker(Pod pod) {
        Vector2f at = pod.ship.getLocation();
        for (ShipAPI ship : engine.getShips()) {
            if (!ship.isAlive() || ship.isHulk() || isCraft(ship) || ship.getParentStation() != null) continue;
            if (DeathThroes.isDying(ship) || !pod.side.equals(side(ship))) continue;
            float reach = ship.getCollisionRadius() + pod.ship.getCollisionRadius() + TcoSettings.podPickupRadius;
            float dx = ship.getLocation().x - at.x;
            float dy = ship.getLocation().y - at.y;
            if (dx * dx + dy * dy <= reach * reach) return ship;
        }
        return null;
    }

    private void remove(Pod pod) {
        engine.addHitParticle(pod.ship.getLocation(), pod.ship.getVelocity(), 40f, 0.8f, 0.2f, colorOf(pod));
        engine.removeEntity(pod.ship);
    }

    private void rescued(float crew, float marines, boolean player, String by) {
        if (!player) return;
        PodLedger.rescued(crew, marines);
        Msg.send(engine, Msg.PLAYER, Math.round(crew + marines) + " survivors picked up by " + by + ".", "pods-rescued", 3f);
    }

    private void dispatch() {
        if (pods.isEmpty()) return;
        for (ShipAPI carrier : new ArrayList<ShipAPI>(engine.getShips())) {
            if (!carrier.isAlive() || carrier.isHulk() || isCraft(carrier) || DeathThroes.isDying(carrier)) continue;
            if (!BoardingCombatPlugin.hasBay(carrier) && !RepairCombatPlugin.hasBay(carrier)) continue;
            // No shuttles while the hangar is held
            if (HangarCombatPlugin.isHeld(carrier) || carrier.getFluxTracker().isOverloadedOrVenting()) continue;
            int out = 0;
            for (Shuttle s : shuttles) if (s.home == carrier) out++;
            if (out >= TcoSettings.podShuttlesPerShip) continue;
            String side = side(carrier);
            Pod best = null;
            float bestDist = Float.MAX_VALUE;
            float range = TcoSettings.podRescueRange + carrier.getCollisionRadius();
            for (Pod pod : pods) {
                if (pod.shuttle != null || !pod.side.equals(side)) continue;
                float dx = pod.ship.getLocation().x - carrier.getLocation().x;
                float dy = pod.ship.getLocation().y - carrier.getLocation().y;
                float d = dx * dx + dy * dy;
                if (d > range * range || d >= bestDist) continue;
                bestDist = d;
                best = pod;
            }
            if (best != null) launch(carrier, best);
        }
    }

    private void launch(ShipAPI carrier, Pod pod) {
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(carrier.getOwner());
            boolean quiet = fleet.isSuppressDeploymentMessages();
            fleet.setSuppressDeploymentMessages(true);
            ShipAPI ship;
            try {
                ship = fleet.spawnShipOrWing(AmmoRules.SHUTTLE_VARIANT, new Vector2f(carrier.getLocation()), carrier.getFacing());
            } finally {
                fleet.setSuppressDeploymentMessages(quiet);
            }
            fleet.removeDeployed(ship, false);
            ship.setCustomData(AmmoRules.SHUTTLE_KEY, Boolean.TRUE);
            ship.setCustomData("tco_ammo_done", Boolean.TRUE);
            ship.setCollisionClass(CollisionClass.FIGHTER);
            ship.setInvalidTransferCommandTarget(true);
            Shuttle s = new Shuttle();
            s.ship = ship;
            s.ai = new ShuttleAI(ship);
            s.home = carrier;
            s.side = side(carrier);
            s.player = isPlayerSide(carrier);
            s.target = pod;
            ship.setShipAI(s.ai);
            ship.setAnimatedLaunch();
            ship.setLaunchingShip(carrier);
            s.ai.flyTo(pod.ship.getLocation());
            pod.shuttle = s;
            shuttles.add(s);
        } catch (RuntimeException e) {
            Global.getLogger(EscapePodPlugin.class).warn("TCO: could not launch a rescue shuttle", e);
        }
    }

    // pods are phased, so enemy shots have to be checked by hand
    private boolean strayHit(Pod pod) {
        ShipAPI ship = pod.ship;
        float r = Math.max(15f, ship.getCollisionRadius());
        int owner = pod.owner;
        Iterator<Object> near = engine.getAllObjectGrid().getCheckIterator(ship.getLocation(), r * 2f + 60f, r * 2f + 60f);
        while (near.hasNext()) {
            Object o = near.next();
            if (!(o instanceof DamagingProjectileAPI)) continue;
            DamagingProjectileAPI shot = (DamagingProjectileAPI) o;
            if (shot.getOwner() == owner || shot.didDamage() || shot.isFading() || shot.getDamageAmount() <= 0f) continue;
            float dx = shot.getLocation().x - ship.getLocation().x, dy = shot.getLocation().y - ship.getLocation().y;
            float hit = r + shot.getCollisionRadius();
            if (dx * dx + dy * dy > hit * hit) continue;
            engine.spawnExplosion(ship.getLocation(), ship.getVelocity(), new Color(255, 170, 90), 40f, 0.5f);
            engine.removeEntity(shot);
            return true;
        }
        return false;
    }

    private void updateShuttles(float amount) {
        Iterator<Shuttle> it = shuttles.iterator();
        while (it.hasNext()) {
            Shuttle s = it.next();
            ShipAPI ship = s.ship;
            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) {
                if (s.player) PodLedger.lost(s.crew, s.marines);
                if (s.player && s.crew + s.marines >= 1f) {
                    Msg.send(engine, Msg.PLAYER, "A rescue shuttle was lost with " + Math.round(s.crew + s.marines) + " survivors aboard.",
                            "rescue-lost", 5f);
                }
                if (s.target != null) s.target.shuttle = null;
                it.remove();
                continue;
            }
            s.legTime += amount;
            boolean homeOk = s.home.isAlive() && !s.home.isHulk() && engine.isEntityInPlay(s.home);
            if (!homeOk) {
                ShipAPI other = newHome(s);
                if (other == null) {
                    // Nowhere to land: stay aboard until the battle ends
                    s.ai.hold();
                    continue;
                }
                s.home = other;
            }
            switch (s.leg) {
                case OUTBOUND: {
                    Pod pod = s.target;
                    if (pod == null || !pods.contains(pod) || HangarCombatPlugin.isHeld(s.home)) {
                        if (pod != null) pod.shuttle = null;
                        s.target = null;
                        s.leg = Leg.RETURN;
                        break;
                    }
                    Vector2f at = pod.ship.getLocation();
                    s.ai.flyTo(at);
                    float reach = ship.getCollisionRadius() + pod.ship.getCollisionRadius() + 30f;
                    float dx = ship.getLocation().x - at.x;
                    float dy = ship.getLocation().y - at.y;
                    if (dx * dx + dy * dy <= reach * reach) {
                        s.crew += pod.crew;
                        s.marines += pod.marines;
                        remove(pod);
                        pods.remove(pod);
                        s.target = null;
                        s.leg = Leg.RETURN;
                        s.legTime = 0f;
                    }
                    break;
                }
                case RETURN: {
                    Vector2f at = s.home.getLocation();
                    s.ai.flyTo(at);
                    float reach = s.home.getCollisionRadius() * 0.6f + 30f;
                    float dx = ship.getLocation().x - at.x;
                    float dy = ship.getLocation().y - at.y;
                    if (dx * dx + dy * dy <= reach * reach) {
                        s.leg = Leg.LANDING;
                        s.legTime = 0f;
                        s.ai.hold();
                        ship.beginLandingAnimation(s.home);
                    }
                    break;
                }
                default: {
                    ship.getLocation().set(s.home.getLocation());
                    ship.getVelocity().set(s.home.getVelocity());
                    if (ship.isFinishedLanding() || s.legTime > 6f) {
                        if (s.crew + s.marines > 0f) rescued(s.crew, s.marines, s.player, s.home.getName());
                        s.crew = 0f;
                        s.marines = 0f;
                        engine.removeEntity(ship);
                        it.remove();
                    }
                    break;
                }
            }
        }
    }

    private ShipAPI newHome(Shuttle s) {
        ShipAPI best = null;
        float bestDist = Float.MAX_VALUE;
        for (ShipAPI ship : engine.getShips()) {
            if (!ship.isAlive() || ship.isHulk() || isCraft(ship) || ship.getParentStation() != null) continue;
            if (!s.side.equals(side(ship))) continue;
            float dx = ship.getLocation().x - s.ship.getLocation().x;
            float dy = ship.getLocation().y - s.ship.getLocation().y;
            float d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = ship;
            }
        }
        return best;
    }
}
