package tco.armor;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;

/**
 * Vanilla's armor arithmetic (from the armor grid's computeDamageMultiplier), so TCO can build on it:
 * share = hitStrength / (hitStrength + armor), never below 1 - maxArmorDamageReduction.
 */
public final class ArmorMath {

    private ArmorMath() {
    }

    public static float share(float hitStrength, float armor, float floor) {
        if (hitStrength <= 0f) return floor;
        return Math.max(floor, hitStrength / (hitStrength + armor));
    }

    public static float effectiveArmor(ShipAPI ship, Vector2f point) {
        ArmorGridAPI grid = ship.getArmorGrid();
        if (grid == null || point == null) return 0f;
        MutableShipStatsAPI stats = ship.getMutableStats();
        float armor = stats.getEffectiveArmorBonus().computeEffective(cellArmor(grid, point));
        return Math.max(armor, grid.getArmorRating() * stats.getMinArmorFraction().getModifiedValue());
    }

    /** Minimum share any hit gets through. */
    public static float floor(ShipAPI ship) {
        return 1f - ship.getMutableStats().getMaxArmorDamageReduction().getModifiedValue();
    }

    public static float hitStrength(ShipAPI ship, DamageAPI damage, float amount) {
        return amount * damage.getType().getArmorMult()
                * ship.getMutableStats().getArmorDamageTakenMult().getModifiedValue();
    }

    public static float vanillaShare(ShipAPI ship, Vector2f point, DamageAPI damage, float amount) {
        return share(hitStrength(ship, damage, amount), effectiveArmor(ship, point), floor(ship));
    }

    /**
     * Damage multiplier m so the hit does exactly cut x its full-damage effect: m x share(m x H) = cut x
     * share(H).
     */
    public static float keepPunch(ShipAPI ship, Vector2f point, DamageAPI damage, float cut) {
        if (point == null || ship.getArmorGrid() == null || cut <= 0f || cut >= 1f) return cut;
        float armor = effectiveArmor(ship, point);
        float floor = floor(ship);
        float hit = hitStrength(ship, damage, damage.getDamage());
        if (hit <= 0f || armor <= 0f) return cut;
        float target = cut * share(hit, armor, floor);
        float low = cut;
        float high = 1f;
        for (int i = 0; i < 18; i++) {
            float m = (low + high) * 0.5f;
            if (m * share(m * hit, armor, floor) < target) low = m;
            else high = m;
        }
        return (low + high) * 0.5f;
    }

    /** Damage multiplier M so vanilla lets exactly targetShare through: M x share(M x H, A) = targetShare. */
    public static float solve(float targetShare, float hit, float armor, float floor) {
        if (targetShare <= 0f) return 0f;
        if (hit <= 0f || armor <= 0f) return Math.min(1f, targetShare);
        if (targetShare >= share(hit, armor, floor)) return 1f;
        float low = 0f;
        float high = 1f;
        for (int i = 0; i < 22; i++) {
            float m = (low + high) * 0.5f;
            if (m * share(m * hit, armor, floor) < targetShare) low = m;
            else high = m;
        }
        return (low + high) * 0.5f;
    }

    /** Armor under a hit: the cell, the eight around it, and half of the next twelve. */
    public static float cellArmor(ArmorGridAPI grid, Vector2f point) {
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
}
