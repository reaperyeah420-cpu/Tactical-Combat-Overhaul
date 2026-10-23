package tco.armor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;

import tco.Msg;
import tco.TcoSettings;
import tco.hullmods.BlowoutPanels;
import tco.hullmods.ExpandedPropellant;
import tco.hullmods.WetMagazines;
import tco.power.PowerCombatPlugin;
import tco.repair.RepairCombatPlugin;

/**
 * Effects of bounces and penetrations, queued by ArmorListener during hit resolution and applied next frame.
 */
public final class ArmorEffects {

    private static final int SOUNDS_PER_FRAME = 2;
    private static final Color SPARK = new Color(255, 225, 150);
    private static final Color DUST = new Color(210, 215, 225);
    private static final Color BREACH = new Color(255, 120, 60);

    private static class Ricochet {
        ShipAPI source;
        WeaponAPI weapon;
        Vector2f from;
        float heading;
        float damage;
        ShipAPI struck;
    }

    private static class Breach {
        ShipAPI ship;
        Vector2f point;
        float hullDamage;
        ShipAPI source;
        float caliber = 1f;
    }

    private static class Shatter {
        Vector2f point;
        ShipAPI struck;
    }

    private static final List<Ricochet> ricochets = new ArrayList<Ricochet>();
    private static final List<Breach> breaches = new ArrayList<Breach>();
    private static final List<Shatter> shatters = new ArrayList<Shatter>();
    private static final List<Breach> exposures = new ArrayList<Breach>();
    // entry point, heading, depth, hull damage
    private static final List<Object[]> insides = new ArrayList<Object[]>();
    private static final int LABELS_PER_FRAME = 6;
    private static final Map<ShipAPI, Long> lastNote = new WeakHashMap<ShipAPI, Long>();
    private static final List<Object[]> notes = new ArrayList<Object[]>();
    private static final Set<DamagingProjectileAPI> bounced =
            Collections.newSetFromMap(new WeakHashMap<DamagingProjectileAPI, Boolean>());
    private static final Map<WeaponAPI, Float> lastCookoffRoll = new WeakHashMap<WeaponAPI, Float>();

    private ArmorEffects() {
    }

    public static void reset() {
        ricochets.clear();
        breaches.clear();
        shatters.clear();
        exposures.clear();
        insides.clear();
        bounced.clear();
        notes.clear();
        lastNote.clear();
        lastCookoffRoll.clear();
    }

    static void note(ShipAPI ship, Vector2f point, String text) {
        if (!TcoSettings.armorDebugText) return;
        long now = System.nanoTime();
        Long last = lastNote.get(ship);
        if (last != null && now - last < 500000000L) return;
        lastNote.put(ship, now);
        notes.add(new Object[] {new Vector2f(point), text, ship});
    }

    public static boolean isRicochet(DamagingProjectileAPI shell) {
        return bounced.contains(shell);
    }

    static void ricochet(ShipAPI source, WeaponAPI weapon, Vector2f from, float heading, float damage, ShipAPI struck) {
        Ricochet r = new Ricochet();
        r.source = source;
        r.weapon = weapon;
        r.from = from;
        r.heading = heading;
        r.damage = damage;
        r.struck = struck;
        ricochets.add(r);
    }

    static void penetrate(ShipAPI ship, Vector2f point, float hullDamage, ShipAPI source, float caliber) {
        Breach b = new Breach();
        b.ship = ship;
        b.point = new Vector2f(point);
        b.hullDamage = hullDamage;
        b.source = source;
        b.caliber = caliber;
        breaches.add(b);
    }

    static void exposed(ShipAPI ship, Vector2f point, ShipAPI source, float caliber) {
        Breach b = new Breach();
        b.ship = ship;
        b.point = new Vector2f(point);
        b.source = source;
        b.caliber = caliber;
        exposures.add(b);
    }

    static void inside(ShipAPI ship, Vector2f entry, float dirX, float dirY, float depth, float hullDamage) {
        insides.add(new Object[] {ship, new Vector2f(entry), new float[] {dirX, dirY, depth, hullDamage}});
    }

    static void shatter(Vector2f point, ShipAPI struck) {
        Shatter s = new Shatter();
        s.point = new Vector2f(point);
        s.struck = struck;
        shatters.add(s);
    }

    public static void flush(CombatEngineAPI engine) {
        for (Object[] note : notes) {
            label(engine, (Vector2f) note[0], (String) note[1], new Color(170, 180, 190), (ShipAPI) note[2]);
        }
        notes.clear();
        if (!insides.isEmpty()) {
            PowerCombatPlugin power = PowerCombatPlugin.get(engine);
            for (Object[] in : insides) {
                ShipAPI ship = (ShipAPI) in[0];
                float[] v = (float[]) in[2];
                if (power == null || !ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) continue;
                power.shellInside(ship, (Vector2f) in[1], v[0], v[1], v[2], v[3]);
            }
            insides.clear();
        }
        if (ricochets.isEmpty() && breaches.isEmpty() && shatters.isEmpty() && exposures.isEmpty()) return;
        int sounds = 0;
        int labels = 0;

        for (Ricochet r : ricochets) {
            try {
                CombatEntityAPI spawned = engine.spawnProjectile(r.source, r.weapon, r.weapon.getId(),
                        r.from, r.heading, new Vector2f());
                if (spawned instanceof DamagingProjectileAPI) {
                    DamagingProjectileAPI shell = (DamagingProjectileAPI) spawned;
                    shell.setDamageAmount(r.damage);
                    bounced.add(shell);
                }
            } catch (RuntimeException e) {
                Global.getLogger(ArmorEffects.class).warn("TCO: could not spawn a deflected shell", e);
            }
            double rad = Math.toRadians(r.heading);
            for (int i = 0; i < 3; i++) {
                float speed = 120f + i * 90f;
                Vector2f drift = new Vector2f((float) Math.cos(rad) * speed, (float) Math.sin(rad) * speed);
                engine.addHitParticle(r.from, drift, 18f - i * 4f, 1f, 0.25f + i * 0.08f, SPARK);
            }
            if (sounds++ < SOUNDS_PER_FRAME) play("hit_glancing", r.from, r.struck);
            if (labels++ < LABELS_PER_FRAME) label(engine, r.from, "Ricochet", new Color(255, 220, 90), r.struck);
        }
        ricochets.clear();

        for (Shatter s : shatters) {
            for (int i = 0; i < 4; i++) {
                double a = Math.random() * Math.PI * 2.0;
                float speed = 60f + (float) Math.random() * 120f;
                Vector2f drift = new Vector2f((float) Math.cos(a) * speed, (float) Math.sin(a) * speed);
                engine.addSmoothParticle(s.point, drift, 10f, 0.8f, 0.35f, DUST);
            }
            if (sounds++ < SOUNDS_PER_FRAME) play("hit_light", s.point, s.struck);
            if (labels++ < LABELS_PER_FRAME) label(engine, s.point, "Shattered", new Color(200, 205, 215), s.struck);
        }
        shatters.clear();

        for (Breach b : breaches) {
            ShipAPI ship = b.ship;
            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) continue;
            float hull = ship.getHitpoints();
            if (b.hullDamage >= hull - 1f) {
                // Lethal: let the engine handle the kill
                engine.applyDamage(ship, b.point, hull * 20f + 5000f, DamageType.ENERGY, 0f, true, false, b.source);
            } else {
                ship.setHitpoints(hull - b.hullDamage);
            }
            float size = 30f + (float) Math.sqrt(b.hullDamage) * 3f;
            engine.addHitParticle(b.point, ship.getVelocity(), size, 1f, 0.3f, BREACH);
            engine.spawnExplosion(b.point, ship.getVelocity(), BREACH, size * 0.6f, 0.35f);
            if (sounds++ < SOUNDS_PER_FRAME) play(b.hullDamage > 150f ? "hit_hull_heavy" : "hit_hull_solid", b.point, ship);
            if (labels++ < LABELS_PER_FRAME) {
                label(engine, b.point, b.hullDamage >= 1f ? "Penetrated " + Math.round(b.hullDamage) : "Penetrated",
                        new Color(255, 140, 70), ship);
            }
            if (ship.isAlive() && !ship.isHulk()) checkMagazines(engine, ship, b);
            Fires.tryIgnite(engine, ship, b.point, false, b.caliber);
        }
        breaches.clear();

        for (Breach e : exposures) {
            ShipAPI ship = e.ship;
            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) continue;
            checkMagazines(engine, ship, e);
        }
        exposures.clear();
    }

    /**
     * A shell inside the hull may cook off the nearest loaded weapon's ammo (fuller magazine, bigger
     * blast; missiles worst) or wreck an energy weapon. One roll per cookoffRollInterval per weapon.
     */
    private static void checkMagazines(CombatEngineAPI engine, ShipAPI ship, Breach breach) {
        WeaponAPI weapon = null;
        float best = Float.MAX_VALUE;
        for (WeaponAPI candidate : ship.getAllWeapons()) {
            if (candidate.isDecorative() || RepairCombatPlugin.isDestroyed(candidate)) continue;
            WeaponType type = candidate.getType();
            if (type == WeaponType.ENERGY) {
                if (TcoSettings.energyDamageChance <= 0f) continue;
            } else {
                // Station modules' magazines never go up
                if (!TcoSettings.cookoffEnabled || !candidate.usesAmmo() || ship.isStationModule()) continue;
                if (type != WeaponType.BALLISTIC && type != WeaponType.MISSILE) continue;
                if (candidate.getMaxAmmo() <= 0 || candidate.getAmmo() <= 0) continue;
            }
            float reach = (candidate.getSize() == WeaponSize.LARGE ? 100f
                    : candidate.getSize() == WeaponSize.MEDIUM ? 70f : 50f) * TcoSettings.cookoffReachMult;
            float dx = candidate.getLocation().x - breach.point.x;
            float dy = candidate.getLocation().y - breach.point.y;
            float dist = dx * dx + dy * dy;
            if (dist > reach * reach || dist >= best) continue;
            best = dist;
            weapon = candidate;
        }
        if (weapon == null) return;

        float now = engine.getTotalElapsedTime(false);
        Float last = lastCookoffRoll.get(weapon);
        if (last != null && now - last < TcoSettings.cookoffRollInterval) return;
        lastCookoffRoll.put(weapon, now);

        WeaponType type = weapon.getType();
        if (type == WeaponType.ENERGY) {
            if (Math.random() < TcoSettings.energyDamageChance * breach.caliber) wreckEnergyWeapon(engine, ship, weapon);
            return;
        }
        float blast = weapon.getSize() == WeaponSize.LARGE ? TcoSettings.cookoffDamageLarge
                : weapon.getSize() == WeaponSize.MEDIUM ? TcoSettings.cookoffDamageMedium : TcoSettings.cookoffDamageSmall;
        Vector2f at = weapon.getLocation();
        float fill = Math.min(1f, (float) weapon.getAmmo() / weapon.getMaxAmmo());
        float volatility = type == WeaponType.MISSILE ? 1.5f : 1f;
        // Wet Magazine Stowage: flooded magazines rarely go up
        if (Math.random() >= TcoSettings.cookoffChance * fill * volatility * breach.caliber * WetMagazines.chanceMult(ship)) return;

        // Expanded Propellant: bigger charges, a bigger blast; Blowout Panels vent most of it outward
        float damage = blast * fill * volatility * ExpandedPropellant.cookoffMult(ship) * BlowoutPanels.damageMult(ship);
        weapon.setAmmo(0);
        weapon.disable(true);
        RepairCombatPlugin.markDestroyed(engine, weapon);

        // Straight to hull, plus a lesser blast on the surrounding armor
        float hull = ship.getHitpoints();
        if (damage >= hull - 1f) {
            engine.applyDamage(ship, at, hull * 20f + 5000f, DamageType.ENERGY, 0f, true, false, breach.source);
        } else {
            ship.setHitpoints(hull - damage);
            engine.applyDamage(ship, at, damage * 0.25f, DamageType.HIGH_EXPLOSIVE, 0f, true, false, breach.source);
        }
        float size = 60f + (float) Math.sqrt(damage) * 4f;
        engine.spawnExplosion(at, ship.getVelocity(), BREACH, size, 0.9f);
        engine.addHitParticle(at, ship.getVelocity(), size * 1.6f, 1f, 0.5f, new Color(255, 230, 170));
        play("explosion_from_damage", at, ship);
        label(engine, at, "MAGAZINE DETONATION", new Color(255, 80, 60), ship);
        // Fire not scaled by the shell again
        Fires.tryIgnite(engine, ship, at, true, BlowoutPanels.fireMult(ship) * WetMagazines.fireMult(ship));
        Msg.about(engine, ship, "Magazine detonation aboard " + ship.getName()
                + ": " + weapon.getDisplayName() + " destroyed.");
    }

    private static void wreckEnergyWeapon(CombatEngineAPI engine, ShipAPI ship, WeaponAPI weapon) {
        weapon.disable(true);
        RepairCombatPlugin.markDestroyed(engine, weapon);
        Vector2f at = weapon.getLocation();
        engine.addHitParticle(at, ship.getVelocity(), 50f, 1f, 0.3f, new Color(150, 200, 255));
        engine.spawnExplosion(at, ship.getVelocity(), new Color(120, 170, 255), 30f, 0.4f);
        play("hit_hull_heavy", at, ship);
        label(engine, at, "WEAPON DESTROYED", new Color(150, 200, 255), ship);
        Msg.about(engine, ship, weapon.getDisplayName() + " aboard " + ship.getName() + " destroyed.");
    }

    public static void floatText(CombatEngineAPI engine, Vector2f at, String text, Color color, ShipAPI near) {
        label(engine, at, text, color, near);
    }

    static void label(CombatEngineAPI engine, Vector2f at, String text, Color color, ShipAPI near) {
        if (!TcoSettings.armorDebugText) return;
        // One at a time per ship, except magazine detonations
        if (near != null && !text.startsWith("MAGAZINE")) {
            float now = engine.getTotalElapsedTime(false);
            Float last = lastLabel.get(near);
            if (last != null && now >= last && now - last < TcoSettings.hitTextInterval) return;
            lastLabel.put(near, now);
        }
        engine.addFloatingText(at, text, 18f, color, near, 0f, 0f);
    }

    private static final Map<ShipAPI, Float> lastLabel = new WeakHashMap<ShipAPI, Float>();

    private static void play(String sound, Vector2f at, ShipAPI near) {
        try {
            Global.getSoundPlayer().playSound(sound, 1f, 0.7f, at, near == null ? new Vector2f() : near.getVelocity());
        } catch (RuntimeException e) {
        }
    }
}
