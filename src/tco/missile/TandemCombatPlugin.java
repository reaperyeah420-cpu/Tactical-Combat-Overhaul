package tco.missile;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.TcoSettings;
import tco.hullmods.TandemWarheads;

/**
 * Tandem warheads through shields: a tandem missile that hits a raised shield spends tandemPenetrationLoss of its
 * warhead on it (the shield takes that share), and the rest strikes the hull where the missile's path enters it,
 * next frame (outside collision handling), as a hit from the missile itself (bypassing shields), so armor rules and
 * ERA (which takes another tandemPenetrationLoss, ExplosiveReactiveArmor) still apply.
 */
public class TandemCombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final String ID = "tco_tandem";
    private static final String LISTENER_KEY = "tco_tandem_listener";
    private static final Color FLASH = new Color(255, 200, 140);

    private static final class Strike {
        ShipAPI ship;
        MissileAPI missile;
        // Entry point in the ship's own frame (forward, left), so it follows the ship
        float forward, left;
        float damage, emp;
        DamageType type;
    }

    private CombatEngineAPI engine;
    private final List<Strike> pending = new ArrayList<Strike>();
    // Missiles whose warhead went through a shield (ERA then takes the rest)
    private static final Set<MissileAPI> throughShield = Collections.newSetFromMap(new WeakHashMap<MissileAPI, Boolean>());

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        throughShield.clear();
    }

    /** This tandem missile already spent part of its warhead on a shield. */
    public static boolean wentThroughShield(Object param) {
        return param instanceof MissileAPI && throughShield.contains(param);
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null) return;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getCustomData().containsKey(LISTENER_KEY) || ship.isFighter()) continue;
            ship.setCustomData(LISTENER_KEY, Boolean.TRUE);
            ship.addListener(new ShieldListener(ship));
        }
        if (pending.isEmpty()) return;
        List<Strike> now = new ArrayList<Strike>(pending);
        pending.clear();
        for (Strike s : now) {
            if (!s.ship.isAlive() || s.ship.isHulk() || !engine.isEntityInPlay(s.ship) || s.damage <= 0f) continue;
            double f = Math.toRadians(s.ship.getFacing());
            float x = s.ship.getLocation().x + (float) Math.cos(f) * s.forward - (float) Math.sin(f) * s.left;
            float y = s.ship.getLocation().y + (float) Math.sin(f) * s.forward + (float) Math.cos(f) * s.left;
            Vector2f at = new Vector2f(x, y);
            engine.applyDamage(s.missile, s.ship, at, s.damage, s.type, s.emp, true, false, s.missile.getSource(), true);
            engine.spawnExplosion(at, s.ship.getVelocity(), FLASH, 40f + (float) Math.sqrt(s.damage) * 2f, 0.4f);
        }
    }

    private class ShieldListener implements DamageTakenModifier {
        private final ShipAPI ship;

        ShieldListener(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point, boolean shieldHit) {
            if (!shieldHit || target != ship || point == null || !(param instanceof MissileAPI)) return null;
            MissileAPI missile = (MissileAPI) param;
            if (!TandemWarheads.fired(missile) || throughShield.contains(missile)) return null;
            float loss = Math.max(0f, Math.min(1f, TcoSettings.tandemPenetrationLoss));
            float through = 1f - loss;
            // The shield takes the precursor's share
            damage.getModifier().modifyMult(ID, loss);
            throughShield.add(missile);
            if (through <= 0f) return ID;
            Vector2f v = missile.getVelocity();
            float speed = v.length();
            float dx, dy;
            if (speed > 1f) {
                dx = v.x / speed;
                dy = v.y / speed;
            } else {
                float cx = ship.getLocation().x - point.x, cy = ship.getLocation().y - point.y;
                float l = Math.max(1f, (float) Math.hypot(cx, cy));
                dx = cx / l;
                dy = cy / l;
            }
            Vector2f entry = hullEntry(ship, point, dx, dy);
            if (entry == null) {
                // Glancing path: straight at the ship instead
                float cx = ship.getLocation().x - point.x, cy = ship.getLocation().y - point.y;
                float l = Math.max(1f, (float) Math.hypot(cx, cy));
                entry = hullEntry(ship, point, cx / l, cy / l);
            }
            if (entry == null) return ID;
            Strike s = new Strike();
            s.ship = ship;
            s.missile = missile;
            double f = Math.toRadians(ship.getFacing());
            float rx = entry.x - ship.getLocation().x, ry = entry.y - ship.getLocation().y;
            s.forward = (float) (rx * Math.cos(f) + ry * Math.sin(f));
            s.left = (float) (-rx * Math.sin(f) + ry * Math.cos(f));
            // The missile's own damage, as the shield pierce of PowerCombatPlugin uses
            s.damage = missile.getDamageAmount() * through;
            s.emp = missile.getEmpAmount() * through;
            s.type = damage.getType();
            pending.add(s);
            engine.addHitParticle(point, ship.getVelocity(), 45f, 1f, 0.15f, FLASH);
            return ID;
        }
    }

    /** Where a path from a point on the shield first enters the hull, or null if it misses the ship. */
    private static Vector2f hullEntry(ShipAPI ship, Vector2f from, float dirX, float dirY) {
        BoundsAPI bounds = ship.getExactBounds();
        if (bounds == null) return null;
        bounds.update(ship.getLocation(), ship.getFacing());
        float reach = ship.getCollisionRadius() * 2.5f + ship.getShieldRadiusEvenIfNoShield();
        float pathX = dirX * reach, pathY = dirY * reach;
        float first = Float.MAX_VALUE;
        for (SegmentAPI segment : bounds.getSegments()) {
            Vector2f a = segment.getP1();
            Vector2f b = segment.getP2();
            float ex = b.x - a.x, ey = b.y - a.y;
            float cross = pathX * ey - pathY * ex;
            if (Math.abs(cross) < 0.0001f) continue;
            float t = ((a.x - from.x) * ey - (a.y - from.y) * ex) / cross;
            float u = ((a.x - from.x) * pathY - (a.y - from.y) * pathX) / cross;
            if (t >= 0f && t <= 1f && u >= 0f && u <= 1f && t < first) first = t;
        }
        if (first == Float.MAX_VALUE) return null;
        return new Vector2f(from.x + pathX * first, from.y + pathY * first);
    }
}
