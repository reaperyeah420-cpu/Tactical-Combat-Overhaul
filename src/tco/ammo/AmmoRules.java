package tco.ammo;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.DerivedWeaponStatsAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;

import tco.TcoSettings;

/** Ammo and resupply numbers, derived from weapon and hull data so other mods need no setup. */
public final class AmmoRules {

    public static final String HULLMOD_ID = "tco_resupply_bay";
    public static final String SUPPLY_KEY = "tco_supply";
    public static final String SUPPLY_MAX_KEY = "tco_supply_max";
    public static final String SUPPLY_GAIN_KEY = "tco_supply_gain";
    public static final String SHUTTLE_KEY = "tco_shuttle";
    // not shuttlepod_xfer, that's the command pod
    public static final String SHUTTLE_VARIANT = "shuttlepod_Hull";
    public static final String SUPPLY_SHUTTLE_VARIANT = "tco_supply_shuttle_Hull";

    public static boolean isShuttle(ShipAPI ship) {
        return ship != null && ship.getCustomData().containsKey(SHUTTLE_KEY);
    }

    private AmmoRules() {
    }

    /** Projectile ballistics; beams and energy weapons excluded. */
    public static boolean isBallisticGun(WeaponAPI weapon) {
        return !weapon.isDecorative()
                && weapon.getType() == WeaponType.BALLISTIC
                && !weapon.isBeam();
    }

    /** Ballistics without ammo get a magazine. */
    public static boolean shouldLimit(WeaponAPI weapon) {
        return isBallisticGun(weapon) && !weapon.usesAmmo();
    }

    /** Ballistics with their own self-reloading magazine; reloads draw from a finite reserve. */
    public static boolean isClipFed(WeaponAPI weapon) {
        return isBallisticGun(weapon) && weapon.usesAmmo() && weapon.getAmmoPerSecond() > 0f;
    }

    /** Ammo multiplier: pdAmmoMult for PD, otherwise by mount size. */
    public static float stockMult(boolean pd, WeaponSize size) {
        if (pd) return TcoSettings.pdAmmoMult;
        if (size == WeaponSize.LARGE) return TcoSettings.ammoLargeMult;
        if (size == WeaponSize.MEDIUM) return TcoSettings.ammoMediumMult;
        return TcoSettings.ammoSmallMult;
    }

    /** Reserve: rounds reloaded over the set time. */
    public static int reserveFor(WeaponAPI weapon, ShipAPI ship) {
        float shotsPerSecond = 0f;
        DerivedWeaponStatsAPI stats = weapon.getDerivedStats();
        if (stats != null && stats.getDamagePerShot() > 0f && stats.getSustainedDps() > 0f) {
            shotsPerSecond = stats.getSustainedDps() / stats.getDamagePerShot();
        }
        if (shotsPerSecond <= 0f || Float.isNaN(shotsPerSecond) || Float.isInfinite(shotsPerSecond)) {
            shotsPerSecond = weapon.getAmmoPerSecond();
        }

        float shots = shotsPerSecond * TcoSettings.ammoSeconds * stockMult(weapon.hasAIHint(AIHints.PD), weapon.getSize());
        shots = ship.getMutableStats().getBallisticAmmoBonus().computeEffective(shots);
        int floor = Math.max(TcoSettings.minAmmo, weapon.getMaxAmmo() * 2);
        return Math.max(floor, Math.min(99999, Math.round(shots)));
    }

    /** Magazine: a set number of seconds of continuous fire. */
    public static int magazineFor(WeaponAPI weapon, ShipAPI ship) {
        float shotsPerSecond = 0f;
        DerivedWeaponStatsAPI stats = weapon.getDerivedStats();
        if (stats != null && stats.getDamagePerShot() > 0f && stats.getDps() > 0f) {
            shotsPerSecond = stats.getDps() / stats.getDamagePerShot();
        }
        if (shotsPerSecond <= 0f || Float.isNaN(shotsPerSecond) || Float.isInfinite(shotsPerSecond)) {
            shotsPerSecond = 1f / Math.max(0.1f, weapon.getCooldown());
        }

        float shots = shotsPerSecond * TcoSettings.ammoSeconds * stockMult(weapon.hasAIHint(AIHints.PD), weapon.getSize());
        shots = ship.getMutableStats().getBallisticAmmoBonus().computeEffective(shots);
        return Math.max(TcoSettings.minAmmo, Math.min(99999, Math.round(shots)));
    }

    /** Non-regenerating ballistic and missile ammo. */
    public static boolean canResupply(WeaponAPI weapon) {
        if (weapon.isDecorative() || !weapon.usesAmmo()) return false;
        if (weapon.getAmmoPerSecond() > 0f) return false;
        WeaponType type = weapon.getType();
        return type == WeaponType.BALLISTIC || type == WeaponType.MISSILE;
    }

    public static float fullReloadCost(WeaponAPI weapon) {
        boolean missile = weapon.getType() == WeaponType.MISSILE;
        WeaponSize size = weapon.getSize();
        float cost;
        if (size == WeaponSize.LARGE) cost = missile ? 40f : 16f;
        else if (size == WeaponSize.MEDIUM) cost = missile ? 20f : 8f;
        else cost = missile ? 10f : 4f;
        return cost;
    }

    public static float fullReloadSeconds(WeaponAPI weapon) {
        return weapon.getType() == WeaponType.MISSILE
                ? TcoSettings.missileReloadSeconds : TcoSettings.gunReloadSeconds;
    }

    public static float fobCapacity() {
        return supplyCapacity(HullSize.CAPITAL_SHIP) * TcoSettings.fobCapitalStocks;
    }

    public static float supplyCapacity(HullSize size) {
        float base;
        if (size == HullSize.CAPITAL_SHIP) base = 1200f;
        else if (size == HullSize.CRUISER) base = 600f;
        else if (size == HullSize.DESTROYER) base = 300f;
        else base = 150f;
        return base * TcoSettings.supplyMult;
    }

    public static float resupplyRange(HullSize size) {
        float base;
        if (size == HullSize.CAPITAL_SHIP) base = 1200f;
        else if (size == HullSize.CRUISER) base = 1000f;
        else if (size == HullSize.DESTROYER) base = 800f;
        else base = 600f;
        return base * TcoSettings.resupplyRangeMult;
    }
}
