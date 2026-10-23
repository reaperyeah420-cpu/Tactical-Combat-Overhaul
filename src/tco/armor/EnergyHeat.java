package tco.armor;

import java.awt.Color;
import java.util.Map;
import java.util.WeakHashMap;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;

import tco.TcoSettings;
import tco.hullmods.ThermalDispersal;

/**
 * Heat soak for energy bolts. They keep the game's own armour formula (no slope, no bounce, shatter or
 * penetration), but each hit heats the armour cells around it, and hits on hot armour do more damage. Heat
 * spreads to the neighbouring cells and cools steadily. Beams and missiles don't take part.
 */
final class EnergyHeat {

    private static final String MOD_ID = "tco_heat";
    // Below this armour rating a ship heats as if it had this much (frigates would heat in one hit)
    private static final float MIN_ARMOR = 300f;
    private static final Color GLOW = new Color(255, 130, 50, 255);

    private static final class Heat {
        float[][] cells;
        float time;
    }

    private static final Map<ShipAPI, Heat> heat = new WeakHashMap<ShipAPI, Heat>();
    private static CombatEngineAPI engine;

    private EnergyHeat() {
    }

    /** An energy bolt on armour: bonus from the heat already there, then the spot heats up. */
    static String onHit(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point, boolean shieldHit) {
        if (!TcoSettings.armorEnabled || !TcoSettings.energyHeatEnabled || shieldHit || point == null) return null;
        if (!(param instanceof DamagingProjectileAPI) || param instanceof MissileAPI || !(target instanceof ShipAPI)) return null;
        DamagingProjectileAPI bolt = (DamagingProjectileAPI) param;
        ShipAPI ship = (ShipAPI) target;
        if (ship.isFighter() || ship.isHulk() || bolt.isFromMissile()) return null;
        WeaponAPI weapon = bolt.getWeapon();
        if (weapon == null || weapon.getType() != WeaponType.ENERGY) return null;
        ArmorGridAPI grid = ship.getArmorGrid();
        if (grid == null || grid.getArmorRating() <= 0f) return null;
        int[] cell = grid.getCellAtLocation(point);
        float[][] armor = grid.getGrid();
        if (cell == null || armor.length == 0) return null;

        CombatEngineAPI now = Global.getCombatEngine();
        if (now != engine) {
            engine = now;
            heat.clear();
        }
        float time = engine.getTotalElapsedTime(false);
        Heat h = heat.get(ship);
        if (h == null || h.cells.length != armor.length || h.cells[0].length != armor[0].length) {
            h = new Heat();
            h.cells = new float[armor.length][armor[0].length];
            h.time = time;
            heat.put(ship, h);
        }
        cool(h, time, ship);
        int cx = cell[0], cy = cell[1];
        if (cx < 0 || cy < 0 || cx >= h.cells.length || cy >= h.cells[cx].length) return null;

        // Bare hull doesn't hold heat
        if (ArmorMath.cellArmor(grid, point) < grid.getArmorRating() * TcoSettings.armorMinFraction) return null;

        float level = h.cells[cx][cy];
        String result = null;
        if (level > 0f && TcoSettings.energyHeatMaxBonus > 0f) {
            damage.getModifier().modifyMult(MOD_ID, 1f + TcoSettings.energyHeatMaxBonus * level);
            result = MOD_ID;
        }

        float rating = ship.getMutableStats().getEffectiveArmorBonus().computeEffective(grid.getArmorRating());
        float add = damage.getDamage() / (Math.max(MIN_ARMOR, rating) * TcoSettings.energyHeatArmorScale)
                * ThermalDispersal.heatMult(ship);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int x = cx + dx, y = cy + dy;
                if (x < 0 || y < 0 || x >= h.cells.length || y >= h.cells[x].length) continue;
                float share = dx == 0 && dy == 0 ? 1f : 0.5f;
                h.cells[x][y] = Math.min(1f, h.cells[x][y] + add * share);
            }
        }
        // Hot armour glows
        if (level > 0.3f) {
            engine.addHitParticle(point, ship.getVelocity(), 20f + 40f * level, 0.3f + 0.5f * level, 0.3f, GLOW);
        }
        return result;
    }

    private static void cool(Heat h, float time, ShipAPI ship) {
        float dt = time - h.time;
        if (dt <= 0f) return;
        h.time = time;
        float drop = dt / Math.max(0.1f, TcoSettings.energyHeatCoolSeconds * ThermalDispersal.coolMult(ship));
        for (float[] column : h.cells) {
            for (int y = 0; y < column.length; y++) {
                if (column[y] > 0f) column[y] = Math.max(0f, column[y] - drop);
            }
        }
    }
}
