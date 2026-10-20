package tco.hullmods;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;

import tco.TcoSettings;
import tco.armor.ArmorListener;
import tco.armor.ArmorMath;

/**
 * Composite Armor: less armor, faster and more agile, and kinetic/HE projectiles do compositeDamageMult.
 * The cut keeps the shell's punch against armor (see keepPunch). With ERA, only the stronger cut applies.
 */
public class CompositeArmor extends BaseHullMod {

    public static final String ID = "tco_composite";

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        TcoSettings.load();
        stats.getArmorBonus().modifyMult(id, TcoSettings.compositeArmorMult);
        float bonus = TcoSettings.compositeMobilityBonus * 100f;
        stats.getMaxSpeed().modifyPercent(id, bonus);
        stats.getAcceleration().modifyPercent(id, bonus);
        stats.getDeceleration().modifyPercent(id, bonus);
        stats.getTurnAcceleration().modifyPercent(id, bonus);
        stats.getMaxTurnRate().modifyPercent(id, bonus);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (!ship.hasListenerOfClass(Listener.class)) ship.addListener(new Listener(ship));
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round((1f - TcoSettings.compositeDamageMult) * 100f) + "%";
        if (index == 1) return Math.round((1f - TcoSettings.compositeArmorMult) * 100f) + "%";
        if (index == 2) return Math.round(TcoSettings.compositeMobilityBonus * 100f) + "%";
        return null;
    }

    /** Extra multiplier on kinetic/HE projectile hits, so the total with ERA is min(ERA, composite). */
    public static float damageMult(ShipAPI ship, Vector2f point, Object param, DamageType type) {
        if (ship == null || (type != DamageType.KINETIC && type != DamageType.HIGH_EXPLOSIVE)) return 1f;
        if (!(param instanceof DamagingProjectileAPI) || param instanceof BeamAPI) return 1f;
        if (ship.getVariant() == null || !ship.getVariant().hasHullMod(ID)) return 1f;
        float composite = Math.max(0f, TcoSettings.compositeDamageMult);
        if (type == DamageType.HIGH_EXPLOSIVE) {
            float era = ExplosiveReactiveArmor.heMult(ship, point, param, type);
            if (era < 1f) {
                if (era <= composite) return 1f;
                return era > 0f ? composite / era : 1f;
            }
        }
        return composite;
    }

    public static class Listener implements DamageTakenModifier {
        private final ShipAPI ship;

        public Listener(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point,
                boolean shieldHit) {
            if (shieldHit || target != ship) return null;
            // TCO's armor rules include this cut for shells
            if (ArmorListener.solves(param, target, damage, point, shieldHit)) return null;
            float mult = damageMult(ship, point, param, damage.getType());
            if (mult >= 1f) return null;
            damage.getModifier().modifyMult(ID, keepPunch(ship, point, damage, mult));
            return ID;
        }
    }

    static float keepPunch(ShipAPI ship, Vector2f point, DamageAPI damage, float cut) {
        return ArmorMath.keepPunch(ship, point, damage, cut);
    }
}
