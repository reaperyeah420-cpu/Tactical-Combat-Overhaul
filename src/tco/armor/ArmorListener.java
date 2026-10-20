package tco.armor;

import java.util.List;
import java.util.Random;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;

import tco.TcoSettings;
import tco.hullmods.CompositeArmor;
import tco.hullmods.ExplosiveReactiveArmor;
import tco.hullmods.SpallLining;

/**
 * Bounce and penetration rules for kinetic and HE ballistic shells on armor; everything else is left
 * to vanilla. Derived from the shot and the armor it hits, so other mods need no setup. Effects are
 * queued in ArmorEffects and applied next frame, outside collision handling.
 */
public class ArmorListener implements DamageTakenModifier {

    private static final String MOD_ID = "tco_armor";
    private static final float REFERENCE_SPEED = 700f;

    private final Random random = new Random();

    private static final class Hit {
        ShipAPI ship;
        DamagingProjectileAPI shell;
        WeaponAPI weapon;
        DamageType type;
        boolean kinetic;
        ArmorGridAPI grid;
        float raw;
        float armor;
        boolean bare;
    }

    private static Hit classify(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point, boolean shieldHit) {
        if (shieldHit || point == null || !TcoSettings.armorEnabled) return null;
        if (!(param instanceof DamagingProjectileAPI) || param instanceof MissileAPI) return null;
        if (!(target instanceof ShipAPI)) return null;
        DamagingProjectileAPI shell = (DamagingProjectileAPI) param;
        ShipAPI ship = (ShipAPI) target;
        if (ship.isFighter() || ship.isHulk() || shell.isFromMissile()) return null;
        WeaponAPI weapon = shell.getWeapon();
        if (weapon == null || weapon.getType() != WeaponType.BALLISTIC) return null;
        DamageType type = damage.getType();
        if (type != DamageType.KINETIC && type != DamageType.HIGH_EXPLOSIVE) return null;
        // Already bounced: ordinary hit
        if (ArmorEffects.isRicochet(shell)) return null;
        ArmorGridAPI grid = ship.getArmorGrid();
        if (grid == null || grid.getArmorRating() <= 0f) return null;
        Hit hit = new Hit();
        hit.ship = ship;
        hit.shell = shell;
        hit.weapon = weapon;
        hit.type = type;
        hit.kinetic = type == DamageType.KINETIC;
        hit.grid = grid;
        hit.raw = ArmorMath.cellArmor(grid, point);
        // Effective armor, never below the floor
        hit.armor = ArmorMath.effectiveArmor(ship, point);
        hit.bare = hit.raw < grid.getArmorRating() * TcoSettings.armorMinFraction;
        return hit;
    }

    /** Whether TCO's armor rules resolve this hit (Composite Armor defers to them). */
    public static boolean solves(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point, boolean shieldHit) {
        Hit hit = classify(param, target, damage, point, shieldHit);
        return hit != null && !hit.bare && hit.shell.getVelocity().length() >= 1f;
    }

    /** Armor-piercing power: damage x speed factor x type factor. */
    public static float penetrationPower(float damage, float speed, boolean kinetic) {
        float speedFactor = (float) Math.pow(Math.max(1f, speed) / REFERENCE_SPEED, TcoSettings.penSpeedExponent);
        speedFactor = Math.max(0.5f, Math.min(2f, speedFactor));
        return damage * speedFactor * (kinetic ? TcoSettings.kineticPenFactor : TcoSettings.hePenFactor);
    }

    private static float caliber(ShipAPI ship, ArmorGridAPI grid, float penetration) {
        float rating = ship.getMutableStats().getEffectiveArmorBonus().computeEffective(grid.getArmorRating());
        float ref = Math.max(1f, rating * TcoSettings.cookoffCaliberRef);
        return Math.max(0f, Math.min(1f, penetration / ref));
    }

    @Override
    public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage,
            Vector2f point, boolean shieldHit) {
        Hit hit = classify(param, target, damage, point, shieldHit);
        if (hit == null) return null;
        ShipAPI ship = hit.ship;
        DamagingProjectileAPI shell = hit.shell;
        ArmorGridAPI grid = hit.grid;
        boolean kinetic = hit.kinetic;
        float armor = hit.armor;
        Vector2f velocity = shell.getVelocity();
        float speed = velocity.length();
        float spall = SpallLining.penetrationMult(ship);

        if (hit.bare) {
            // Little armor left: vanilla damage, but stored ammo is exposed
            float pen = penetrationPower(damage.getDamage(), speed, kinetic) * spall;
            if (speed >= 1f) {
                Vector2f entry = new Vector2f(point);
                entryNormal(ship, point, velocity, speed, entry);
                float depth = Math.min(ship.getCollisionRadius() * 2f,
                        pen / Math.max(armor, grid.getArmorRating() * 0.05f) * TcoSettings.reactorDepthPerRatio);
                ArmorEffects.inside(ship, entry, velocity.x / speed, velocity.y / speed, depth, damage.getDamage());
            }
            ArmorEffects.exposed(ship, point, shell.getSource(), caliber(ship, grid, pen));
            ArmorEffects.note(ship, point, "Bare hull (armor " + Math.round(armor) + ")");
            return null;
        }
        if (speed < 1f) return null;

        // Hull crossing point and surface normal (the reported point can be inside the hull)
        Vector2f surface = new Vector2f(point);
        Vector2f normal = entryNormal(ship, point, velocity, speed, surface);
        float cos = -(velocity.x * normal.x + velocity.y * normal.y) / speed;
        if (cos <= 0f) cos = 1f;
        cos = Math.min(1f, cos);
        float angle = (float) Math.toDegrees(Math.acos(cos));
        // Sloped armor is thicker along the shell's path
        float thickness = armor / Math.max(cos, 0.25f);

        // ERA and Composite Armor cuts
        float era = ExplosiveReactiveArmor.heMult(ship, point, shell, hit.type);
        float composite = CompositeArmor.damageMult(ship, point, shell, hit.type);
        float full = damage.getDamage();
        float base = full * era;
        float shot = base * composite;
        float penetration = penetrationPower(base, speed, kinetic) * spall;
        float ratio = penetration / thickness;
        float caliber = caliber(ship, grid, penetration);

        // Armor pass-through by TCO's rules
        float hitStrength = ArmorMath.hitStrength(ship, damage, full);
        float floor = ArmorMath.floor(ship);
        float low = Math.min(floor, TcoSettings.smallCaliberFloor);
        float t = (ratio - TcoSettings.floorRatioLow) / Math.max(0.01f, TcoSettings.floorRatioHigh - TcoSettings.floorRatioLow);
        float tcoFloor = low + (floor - low) * Math.max(0f, Math.min(1f, t));
        float against = TcoSettings.armorAngleEnabled ? thickness : armor;
        float through = ArmorMath.share(hitStrength * era, against, tcoFloor);

        // Deflection
        float slant = (angle - TcoSettings.bounceStartAngle)
                / Math.max(1f, TcoSettings.bounceFullAngle - TcoSettings.bounceStartAngle);
        slant = Math.max(0f, Math.min(1f, slant));
        float overmatch = Math.max(0f, Math.min(1f, 1.3f - ratio));
        float deflect = slant * TcoSettings.bounceMaxChance * overmatch
                * (kinetic ? 1f : TcoSettings.heBounceMult);
        if (deflect > 0f && random.nextFloat() < deflect) {
            if (random.nextFloat() < TcoSettings.shatterShare) {
                damage.getModifier().modifyMult(MOD_ID, 0f);
                ArmorEffects.shatter(surface, ship);
            } else {
                apply(ship, damage, hitStrength, armor, floor, era, era * composite * TcoSettings.ricochetArmorFraction * through);
                // Reflect about the surface
                float dot = velocity.x * normal.x + velocity.y * normal.y;
                float outX = velocity.x - 2f * dot * normal.x;
                float outY = velocity.y - 2f * dot * normal.y;
                float heading = (float) Math.toDegrees(Math.atan2(outY, outX));
                Vector2f from = new Vector2f(surface.x + normal.x * 12f, surface.y + normal.y * 12f);
                ArmorEffects.ricochet(shell.getSource(), hit.weapon, from, heading,
                        base * TcoSettings.ricochetShellFraction, ship);
            }
            return MOD_ID;
        }

        float penetrate = Math.max(0f, Math.min(1f, (ratio - 0.8f) / 0.4f));
        if (penetrate > 0f && random.nextFloat() < penetrate) {
            float depth = Math.min(ship.getCollisionRadius() * 2f, (ratio - 0.8f) * TcoSettings.reactorDepthPerRatio);
            float hullMult = ship.getMutableStats().getHullDamageTakenMult().getModifiedValue();
            // Hull share grows with overmatch
            float penHull = TcoSettings.penHullMin + TcoSettings.penHullPerRatio * (ratio - 0.8f);
            penHull = Math.max(TcoSettings.penHullMin, Math.min(TcoSettings.penHullMax, penHull));
            if (!kinetic) penHull = Math.min(1f, penHull * TcoSettings.heBehindArmorMult);
            // Penetrating never does less than being stopped would
            if (through >= penHull) {
                apply(ship, damage, hitStrength, armor, floor, era, era * composite * through);
                ArmorEffects.penetrate(ship, point, 0f, shell.getSource(), caliber);
                ArmorEffects.inside(ship, surface, velocity.x / speed, velocity.y / speed, depth, shot * through * hullMult);
                return MOD_ID;
            }
            // Penetrated
            apply(ship, damage, hitStrength, armor, floor, era, era * composite * TcoSettings.penArmorFraction * through);
            float hull = shot * penHull * hullMult;
            ArmorEffects.penetrate(ship, point, hull, shell.getSource(), caliber);
            ArmorEffects.inside(ship, surface, velocity.x / speed, velocity.y / speed, depth, hull);
            return MOD_ID;
        }

        // Held
        apply(ship, damage, hitStrength, armor, floor, era, era * composite * through);
        ArmorEffects.note(ship, point, "Held: pen " + Math.round(penetration) + " vs armor "
                + Math.round(thickness) + " (" + Math.round(angle) + " deg)");
        return MOD_ID;
    }

    // sets the multiplier so vanilla's own armor math lets exactly targetShare through
    private void apply(ShipAPI ship, DamageAPI damage, float hitStrength, float armor, float floor, float era,
            float targetShare) {
        float total = ArmorMath.solve(targetShare, hitStrength, armor, floor);
        float mult = era > 0f ? Math.min(1f, total / era) : 0f;
        damage.getModifier().modifyMult(MOD_ID, mult);
    }

    // same cell area vanilla uses
    private float armorAt(ArmorGridAPI grid, Vector2f point) {
        int[] cell = grid.getCellAtLocation(point);
        if (cell == null) return 0f;
        float[][] cells = grid.getGrid();
        float total = 0f;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (Math.abs(dx) == 2 && Math.abs(dy) == 2) continue;
                int x = cell[0] + dx;
                int y = cell[1] + dy;
                if (x < 0 || y < 0 || x >= cells.length || y >= cells[x].length) continue;
                float weight = Math.abs(dx) <= 1 && Math.abs(dy) <= 1 ? 1f : 0.5f;
                total += grid.getArmorValue(x, y) * weight;
            }
        }
        return total;
    }

    // outward normal of the first hull edge the shell crossed; crossing point goes in surfaceOut
    private Vector2f entryNormal(ShipAPI ship, Vector2f point, Vector2f velocity, float speed, Vector2f surfaceOut) {
        float dirX = velocity.x / speed;
        float dirY = velocity.y / speed;
        BoundsAPI bounds = ship.getExactBounds();
        if (bounds == null) return new Vector2f(-dirX, -dirY);
        bounds.update(ship.getLocation(), ship.getFacing());
        List<SegmentAPI> segments = bounds.getSegments();

        // Trace from outside the ship along the shell's heading
        float back = ship.getCollisionRadius() * 2f + 100f;
        float startX = point.x - dirX * back;
        float startY = point.y - dirY * back;
        float pathX = dirX * (back + 30f);
        float pathY = dirY * (back + 30f);

        float first = Float.MAX_VALUE;
        Vector2f normal = null;
        for (SegmentAPI segment : segments) {
            Vector2f a = segment.getP1();
            Vector2f b = segment.getP2();
            float ex = b.x - a.x;
            float ey = b.y - a.y;
            float cross = pathX * ey - pathY * ex;
            if (Math.abs(cross) < 0.0001f) continue;
            float t = ((a.x - startX) * ey - (a.y - startY) * ex) / cross;
            float u = ((a.x - startX) * pathY - (a.y - startY) * pathX) / cross;
            if (t < 0f || t > 1f || u < 0f || u > 1f || t >= first) continue;
            first = t;
            float length = (float) Math.sqrt(ex * ex + ey * ey);
            normal = new Vector2f(ey / length, -ex / length);
            surfaceOut.set(startX + pathX * t, startY + pathY * t);
        }

        if (normal == null) {
            // No crossing: nearest edge
            float bestDist = Float.MAX_VALUE;
            for (SegmentAPI segment : segments) {
                Vector2f a = segment.getP1();
                Vector2f b = segment.getP2();
                float ex = b.x - a.x;
                float ey = b.y - a.y;
                float lengthSq = ex * ex + ey * ey;
                if (lengthSq < 0.01f) continue;
                float t = ((point.x - a.x) * ex + (point.y - a.y) * ey) / lengthSq;
                t = Math.max(0f, Math.min(1f, t));
                float dx = point.x - (a.x + ex * t);
                float dy = point.y - (a.y + ey * t);
                float dist = dx * dx + dy * dy;
                if (dist < bestDist) {
                    bestDist = dist;
                    float length = (float) Math.sqrt(lengthSq);
                    normal = new Vector2f(ey / length, -ex / length);
                    surfaceOut.set(a.x + ex * t, a.y + ey * t);
                }
            }
        }
        if (normal == null) return new Vector2f(-dirX, -dirY);
        // Face the incoming shell
        if (normal.x * dirX + normal.y * dirY > 0f) normal.set(-normal.x, -normal.y);
        return normal;
    }
}
