package tco.battle;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.listeners.HullDamageAboutToBeTakenListener;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.TcoSettings;
import tco.ammo.AmmoRules;
import tco.AiUtil;
import tco.dock.RepairDock;
import tco.fob.InertAI;
import tco.fob.SupplyFob;

/**
 * Death throes: a killing blow is held back and the ship drifts, disabled, through spreading explosions
 * before dying the normal way, credited to the killer. Uses HullDamageAboutToBeTakenListener.
 * Phase Anchor ships, stations, modules, fighters and TCO's craft die as in vanilla.
 */
public class DeathThroes extends BaseEveryFrameCombatPlugin {

    public static final String DYING_KEY = "tco_dying";
    private static final String ENGINE_KEY = "tco_death_throes";
    private static final String LISTENED_KEY = "tco_death_listener";
    // set before the final blow so it isn't caught as a new killing blow
    private static final String DONE_KEY = "tco_death_done";
    private static final Color FIRE = new Color(255, 130, 40);
    private static final Color HOT = new Color(255, 225, 170);

    private static final Color FIREBALL = new Color(255, 115, 35, 190);
    private static final Color FLAME = new Color(255, 140, 50, 150);
    private static final Color SPARK = new Color(255, 200, 120);
    private static final Color SOOT = new Color(40, 36, 33, 140);
    private static final Color ARC_FRINGE = new Color(110, 160, 255, 200);
    private static final Color ARC_CORE = new Color(235, 240, 255, 255);

    private static final class Dying {
        ShipAPI ship;
        float start;
        float end;
        ShipAPI killer;
        float nextBoom;
        Vector2f drift;
        float spin;
        int chain;
        Vector2f lastLocal;
        final List<Vector2f> fires = new ArrayList<Vector2f>();
        final List<float[]> fireTimers = new ArrayList<float[]>();
        boolean finishing;
    }

    private CombatEngineAPI engine;
    private final Map<ShipAPI, Dying> dying = new WeakHashMap<ShipAPI, Dying>();

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
    }

    private static DeathThroes get() {
        CombatEngineAPI engine = Global.getCombatEngine();
        Object plugin = engine == null ? null : engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof DeathThroes ? (DeathThroes) plugin : null;
    }

    public static boolean isDying(ShipAPI ship) {
        return ship != null && ship.getCustomData().containsKey(DYING_KEY);
    }

    public static float timeLeft(ShipAPI ship) {
        DeathThroes plugin = get();
        CombatEngineAPI engine = Global.getCombatEngine();
        if (plugin == null || engine == null) return 0f;
        Dying d = plugin.dying.get(ship);
        return d == null ? 0f : Math.max(0f, d.end - engine.getTotalElapsedTime(false));
    }

    private static boolean eligible(ShipAPI ship) {
        if (!TcoSettings.deathThroesEnabled || ship == null || !ship.isAlive() || ship.isHulk()) return false;
        if (ship.getCustomData().containsKey(DONE_KEY)) return false;
        if (ship.isFighter() || ship.isDrone() || ship.isShuttlePod() || ship.isPiece() || AmmoRules.isShuttle(ship)) return false;
        if (ship.isStation() || ship.isStationModule() || ship.getParentStation() != null) return false;
        if (SupplyFob.isFobPart(ship) || RepairDock.isDock(ship)) return false;
        return ship.getVariant() == null || !ship.getVariant().hasHullMod("phase_anchor");
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null) return;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getCustomData().containsKey(LISTENED_KEY)) continue;
            ship.setCustomData(LISTENED_KEY, Boolean.TRUE);
            if (!ship.isFighter() && !ship.isDrone()) ship.addListener(new Listener());
        }
        if (dying.isEmpty()) return;
        float now = engine.getTotalElapsedTime(false);
        Iterator<Dying> it = new ArrayList<Dying>(dying.values()).iterator();
        while (it.hasNext()) {
            Dying d = it.next();
            ShipAPI ship = d.ship;
            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) {
                dying.remove(ship);
                ship.removeCustomData(DYING_KEY);
                continue;
            }
            if (now >= d.end) {
                finish(d);
                continue;
            }
            if (engine.isPaused() || amount <= 0f) continue;
            hold(d, amount);
            burn(d, amount);
            d.nextBoom -= amount;
            if (d.nextBoom <= 0f) {
                float progress = (now - d.start) / Math.max(0.1f, d.end - d.start);
                boolean chained = d.chain > 0;
                explode(d, progress, chained);
                if (chained) {
                    d.chain--;
                } else if (Math.random() < 0.25f + 0.35f * progress) {
                    // Chain of blasts through one section
                    d.chain = 1 + (int) (Math.random() * (2 + 2 * progress));
                }
                // Faster as the ship comes apart
                d.nextBoom = d.chain > 0 ? 0.06f + (float) Math.random() * 0.1f
                        : (0.9f - 0.75f * progress) * (0.6f + (float) Math.random() * 0.8f);
            }
        }
    }

    private void start(ShipAPI ship, Object param, Vector2f point) {
        HullSize size = ship.getHullSize();
        float min = size == HullSize.CAPITAL_SHIP ? TcoSettings.deathCapitalMin : size == HullSize.CRUISER ? TcoSettings.deathCruiserMin
                : size == HullSize.DESTROYER ? TcoSettings.deathDestroyerMin : TcoSettings.deathFrigateMin;
        float max = size == HullSize.CAPITAL_SHIP ? TcoSettings.deathCapitalMax : size == HullSize.CRUISER ? TcoSettings.deathCruiserMax
                : size == HullSize.DESTROYER ? TcoSettings.deathDestroyerMax : TcoSettings.deathFrigateMax;
        float duration = min + (float) Math.random() * Math.max(0f, max - min);
        float now = engine.getTotalElapsedTime(false);
        Dying d = new Dying();
        d.ship = ship;
        d.start = now;
        d.end = now + duration;
        d.killer = killerOf(param);
        // Keep momentum; the blow adds a small shove
        d.drift = new Vector2f(ship.getVelocity());
        d.spin = ship.getAngularVelocity();
        if (point != null) shove(d, point, 1.5f);
        dying.put(ship, d);
        ship.setHitpoints(1f);
        ship.setCustomData(DYING_KEY, d.end);
        try {
            ship.getFluxTracker().forceOverload(duration + 1f);
            if (ship.getEngineController() != null) ship.getEngineController().forceFlameout(true);
            ship.setHoldFire(true);
            if (!AiUtil.playerFlying(engine, ship)) ship.setShipAI(new InertAI());
        } catch (RuntimeException e) {
            Global.getLogger(DeathThroes.class).warn("TCO: could not disable dying ship " + ship.getName(), e);
        }
        // First blast
        Vector2f at = ship.getLocation();
        float scale = scale(ship);
        engine.spawnExplosion(at, ship.getVelocity(), FIRE, 70f * scale, 1.0f);
        engine.addHitParticle(at, ship.getVelocity(), 160f * scale, 1f, 0.3f, HOT);
        Global.getSoundPlayer().playSound("explosion_from_damage", 0.9f, 1f, at, ship.getVelocity());
        d.nextBoom = 0.3f;
    }

    private void hold(Dying d, float amount) {
        ShipAPI ship = d.ship;
        if (ship.getHitpoints() < 1f) ship.setHitpoints(1f);
        ship.setBeingIgnored(true);
        for (ShipCommand command : ShipCommand.values()) ship.blockCommandForOneFrame(command);
        if (ship.getEngineController() != null && !ship.getEngineController().isFlamedOut()) {
            ship.getEngineController().forceFlameout(true);
        }
        // Re-apply drift each frame (the engine would bleed it off), keeping outside changes
        Vector2f v = ship.getVelocity();
        if (Vector2f.sub(v, d.drift, null).length() > 6f) d.drift.set(v);
        float drag = 1f - Math.min(1f, 0.01f * amount);
        d.drift.scale(drag);
        v.set(d.drift);
        float w = ship.getAngularVelocity();
        if (Math.abs(w - d.spin) > 8f) d.spin = w;
        d.spin *= drag;
        ship.setAngularVelocity(d.spin);
    }

    private static float kick(ShipAPI ship) {
        HullSize size = ship.getHullSize();
        return size == HullSize.CAPITAL_SHIP ? 1f : size == HullSize.CRUISER ? 1.8f : size == HullSize.DESTROYER ? 2.8f : 4f;
    }

    private static void shove(Dying d, Vector2f at, float strength) {
        ShipAPI ship = d.ship;
        Vector2f c = ship.getLocation();
        Vector2f dir = new Vector2f(c.x - at.x, c.y - at.y);
        float len = dir.length();
        float k = kick(ship) * strength * (0.4f + (float) Math.random() * 0.6f);
        if (len > 1f) {
            dir.scale(k / len);
            Vector2f.add(d.drift, dir, d.drift);
        }
        // Off-centre blasts add a little spin
        float lever = Math.min(1f, len / Math.max(1f, ship.getCollisionRadius()));
        d.spin += (Math.random() < 0.5 ? -1f : 1f) * k * lever * 0.6f;
    }

    private void explode(Dying d, float progress, boolean chained) {
        ShipAPI ship = d.ship;
        Vector2f at;
        if (chained && d.lastLocal != null) {
            // Chained blasts go off near the last one
            float r = ship.getCollisionRadius() * 0.25f;
            Vector2f near = toWorld(ship, new Vector2f(d.lastLocal.x + ((float) Math.random() - 0.5f) * r,
                    d.lastLocal.y + ((float) Math.random() - 0.5f) * r));
            at = insideHull(ship, near) ? near : pointOnHull(ship);
        } else {
            at = pointOnHull(ship);
        }
        d.lastLocal = toLocal(ship, at);
        Vector2f vel = ship.getVelocity();
        float scale = scale(ship) * (0.6f + (float) Math.random() * 0.7f) * (0.8f + 0.6f * progress) * (chained ? 0.75f : 1f);
        shove(d, at, scale / scale(ship));
        if (engine.getViewport().isNearViewport(at, 400f)) {
            // Flash, fireball, flame, soot
            engine.addHitParticle(at, vel, 110f * scale, 1f, 0.12f, HOT);
            engine.spawnExplosion(at, vel, FIRE, 36f * scale, 0.6f + (float) Math.random() * 0.5f);
            engine.addNebulaParticle(at, vel, 34f * scale, 1.9f, 0.08f, 0.25f, 0.9f + (float) Math.random() * 0.5f, FIREBALL);
            engine.addNebulaParticle(at, vel, 40f * scale, 2.6f, 0.15f, 0.2f, 2.8f + (float) Math.random() * 1.5f, SOOT);
            // Sparks and fragments
            int sparks = 4 + (int) (Math.random() * 5 * scale);
            for (int i = 0; i < sparks; i++) {
                double a = Math.random() * Math.PI * 2.0;
                float s = 70f + (float) Math.random() * 170f;
                Vector2f sv = new Vector2f(vel.x + (float) Math.cos(a) * s, vel.y + (float) Math.sin(a) * s);
                engine.addHitParticle(at, sv, 4f + (float) Math.random() * 6f, 1f, 0.35f + (float) Math.random() * 0.5f, SPARK);
            }
            engine.spawnDebrisSmall(at, vel, 2 + (int) (Math.random() * 4), (float) Math.random() * 360f, 360f, 30f, 110f, 360f);
            if (progress > 0.5f && Math.random() < 0.3f) {
                engine.spawnDebrisMedium(at, vel, 1, (float) Math.random() * 360f, 360f, 20f, 60f, 180f);
            }
            // Occasional arcs
            if (Math.random() < 0.2f) {
                Vector2f to = pointOnHull(ship);
                engine.spawnEmpArcVisual(at, ship, to, ship, 6f + (float) Math.random() * 6f, ARC_FRINGE, ARC_CORE);
            }
            if (!chained || Math.random() < 0.4) {
                Global.getSoundPlayer().playSound("explosion_from_damage", 0.9f + (float) Math.random() * 0.35f,
                        chained ? 0.35f : 0.55f, at, vel);
            }
        }
        // Some blasts leave a fire
        int maxFires = ship.getHullSize() == HullSize.CAPITAL_SHIP ? 8 : ship.getHullSize() == HullSize.CRUISER ? 6
                : ship.getHullSize() == HullSize.DESTROYER ? 4 : 3;
        if (d.fires.size() < maxFires && Math.random() < 0.35f) {
            d.fires.add(new Vector2f(d.lastLocal));
            d.fireTimers.add(new float[] { 0f });
        }
        // Scorch the armor; hull is held
        engine.applyDamage(ship, at, 60f * scale, DamageType.HIGH_EXPLOSIVE, 0f, true, false, ship);
    }

    private void burn(Dying d, float amount) {
        if (d.fires.isEmpty()) return;
        ShipAPI ship = d.ship;
        if (!engine.getViewport().isNearViewport(ship.getLocation(), ship.getCollisionRadius() + 300f)) return;
        float scale = scale(ship);
        Vector2f vel = ship.getVelocity();
        for (int i = 0; i < d.fires.size(); i++) {
            float[] timer = d.fireTimers.get(i);
            timer[0] -= amount;
            if (timer[0] > 0f) continue;
            timer[0] = 0.06f + (float) Math.random() * 0.06f;
            Vector2f at = toWorld(ship, d.fires.get(i));
            double a = Math.random() * Math.PI * 2.0;
            float s = 10f + (float) Math.random() * 20f;
            Vector2f fv = new Vector2f(vel.x + (float) Math.cos(a) * s, vel.y + (float) Math.sin(a) * s);
            engine.addNebulaParticle(at, fv, (12f + (float) Math.random() * 10f) * scale, 1.6f, 0.1f, 0.3f,
                    0.4f + (float) Math.random() * 0.3f, FLAME);
            if (Math.random() < 0.5) {
                engine.addNebulaParticle(at, fv, (18f + (float) Math.random() * 12f) * scale, 2.2f, 0.2f, 0.2f,
                        1.6f + (float) Math.random() * 1.2f, SOOT);
            }
        }
    }

    private static Vector2f toLocal(ShipAPI ship, Vector2f world) {
        Vector2f c = ship.getLocation();
        double f = Math.toRadians(-ship.getFacing());
        float x = world.x - c.x, y = world.y - c.y;
        return new Vector2f((float) (x * Math.cos(f) - y * Math.sin(f)), (float) (x * Math.sin(f) + y * Math.cos(f)));
    }

    private static Vector2f toWorld(ShipAPI ship, Vector2f local) {
        Vector2f c = ship.getLocation();
        double f = Math.toRadians(ship.getFacing());
        return new Vector2f(c.x + (float) (local.x * Math.cos(f) - local.y * Math.sin(f)),
                c.y + (float) (local.x * Math.sin(f) + local.y * Math.cos(f)));
    }

    private static boolean insideHull(ShipAPI ship, Vector2f point) {
        BoundsAPI bounds = ship.getExactBounds();
        if (bounds == null) return false;
        bounds.update(ship.getLocation(), ship.getFacing());
        return inside(bounds.getSegments(), point);
    }

    private void finish(Dying d) {
        ShipAPI ship = d.ship;
        d.finishing = true;
        ship.setCustomData(DONE_KEY, Boolean.TRUE);
        dying.remove(ship);
        ship.removeCustomData(DYING_KEY);
        ShipAPI killer = d.killer != null && d.killer != ship ? d.killer : null;
        engine.applyDamage(ship, ship.getLocation(), ship.getMaxHitpoints() * 10f + 10000f, DamageType.ENERGY, 0f, true, false,
                killer);
    }

    private static float scale(ShipAPI ship) {
        HullSize size = ship.getHullSize();
        return size == HullSize.CAPITAL_SHIP ? 1.8f : size == HullSize.CRUISER ? 1.4f : size == HullSize.DESTROYER ? 1.1f : 0.8f;
    }

    private static ShipAPI killerOf(Object param) {
        if (param instanceof DamagingProjectileAPI) return ((DamagingProjectileAPI) param).getSource();
        if (param instanceof BeamAPI) return ((BeamAPI) param).getSource();
        if (param instanceof ShipAPI) return (ShipAPI) param;
        return null;
    }

    /** A random point inside the ship's outline. */
    public static Vector2f pointOnHull(ShipAPI ship) {
        Vector2f c = ship.getLocation();
        BoundsAPI bounds = ship.getExactBounds();
        float r = ship.getCollisionRadius() * 0.85f;
        if (bounds != null) {
            bounds.update(c, ship.getFacing());
            List<SegmentAPI> segments = bounds.getSegments();
            for (int i = 0; i < 12; i++) {
                double a = Math.random() * Math.PI * 2.0;
                float d = r * (float) Math.sqrt(Math.random());
                Vector2f p = new Vector2f(c.x + (float) Math.cos(a) * d, c.y + (float) Math.sin(a) * d);
                if (inside(segments, p)) return p;
            }
        }
        double a = Math.random() * Math.PI * 2.0;
        float d = r * 0.4f * (float) Math.random();
        return new Vector2f(c.x + (float) Math.cos(a) * d, c.y + (float) Math.sin(a) * d);
    }

    private static boolean inside(List<SegmentAPI> segments, Vector2f point) {
        boolean in = false;
        for (SegmentAPI segment : segments) {
            Vector2f a = segment.getP1();
            Vector2f b = segment.getP2();
            if ((a.y > point.y) != (b.y > point.y)) {
                float x = a.x + (point.y - a.y) * (b.x - a.x) / (b.y - a.y);
                if (point.x < x) in = !in;
            }
        }
        return in;
    }

    /** Holds back the killing blow and further hull damage to dying ships. */
    public static class Listener implements HullDamageAboutToBeTakenListener {
        @Override
        public boolean notifyAboutToTakeHullDamage(Object param, ShipAPI ship, Vector2f point, float damageAmount) {
            DeathThroes plugin = get();
            if (plugin == null) return false;
            Dying d = plugin.dying.get(ship);
            if (d != null) return !d.finishing;
            if (damageAmount < ship.getHitpoints() || !eligible(ship)) return false;
            try {
                plugin.start(ship, param, point);
            } catch (RuntimeException e) {
                Global.getLogger(DeathThroes.class).warn("TCO: could not start the death throes of " + ship.getName(), e);
                return false;
            }
            return true;
        }
    }
}
