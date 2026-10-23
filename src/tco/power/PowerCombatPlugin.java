package tco.power;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;

import tco.AiUtil;
import tco.Msg;
import tco.TcoSettings;
import tco.ammo.AmmoRules;
import tco.ammo.EnergyAmmo;
import tco.armor.ArmorEffects;
import tco.hullmods.AuxiliaryGenerator;
import tco.hullmods.BackupGenerator;
import tco.hullmods.SecondaryEnergyBanks;
import tco.rts.FogOfWar;
import tco.rts.RtsCombatPlugin;

/**
 * Reactor power: a second reserve beside flux, drawn by shield hits and weapon fire and scaled from flux
 * stats. Out of power, energy weapons and shields shut off until it recovers.
 * <p>
 * Reactor meltdowns: a deep enough penetration at the reactor knocks out power, engines and shields for
 * good, until a tender repairs it.
 */
public class PowerCombatPlugin extends BaseEveryFrameCombatPlugin {

    public static final String ENGINE_KEY = "tco_power_plugin";
    private static final String DONE_KEY = "tco_power_done";
    private static final float SCAN_INTERVAL = 0.5f;

    private static class Reactor {
        float power;
        float capacity;
        boolean dry;
        boolean meltdown;
        boolean recharging;
        float forward;
        float left;
        // smoothed net change per second, for the HUD
        float rate;
        float lastPower = -1f;
    }

    private static final String PIERCE_ID = "tco_shield_pierce";
    // ship -> shield colours before fading, and the faded ones last set
    private final Map<ShipAPI, Color[]> shieldBase = new HashMap<ShipAPI, Color[]>();
    private final Map<ShipAPI, Color[]> shieldSet = new HashMap<ShipAPI, Color[]>();
    // Kinetic shots through a weak shield, waiting for next frame (outside collision handling):
    // {ship, shot, shield hit point, hull entry point or null, ship location then}
    private final List<Object[]> piercing = new ArrayList<Object[]>();
    // Visible copies flying through the bubble to the hull
    private final List<Pierce> flying = new ArrayList<Pierce>();

    private static final class Pierce {
        ShipAPI ship;
        DamagingProjectileAPI shot;
        DamagingProjectileAPI visual;
        // hull entry point in the ship's own frame (forward, left), so it follows the ship as it moves and turns
        float forward, left;
        float age;
        float damage, emp;
    }

    private CombatEngineAPI engine;
    private final Map<ShipAPI, Reactor> reactors = new HashMap<ShipAPI, Reactor>();
    private final Set<DamagingProjectileAPI> counted =
            Collections.newSetFromMap(new WeakHashMap<DamagingProjectileAPI, Boolean>());
    private final ShieldListener shieldListener = new ShieldListener();
    private float scanTimer = 0f;
    private boolean loggedShieldHit = false;
    private float aiTimer = 0f;
    private static final float AI_INTERVAL = 0.25f;
    private static final float AI_FLAG_SECONDS = 0.6f;
    private LabelAPI hudLabel;
    private String hudText;
    private LabelAPI rateLabel;
    private String rateText;

    public static PowerCombatPlugin get(CombatEngineAPI engine) {
        if (engine == null) return null;
        Object plugin = engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof PowerCombatPlugin ? (PowerCombatPlugin) plugin : null;
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
    }

    public float powerOf(ShipAPI ship) {
        Reactor r = reactors.get(ship);
        return r == null || !TcoSettings.powerEnabled ? -1f : r.power;
    }

    public float capacityOf(ShipAPI ship) {
        Reactor r = reactors.get(ship);
        return r == null || !TcoSettings.powerEnabled ? -1f : r.capacity;
    }

    public boolean isOutOfPower(ShipAPI ship) {
        Reactor r = reactors.get(ship);
        return r != null && (r.meltdown || (TcoSettings.powerEnabled && r.dry));
    }

    public boolean isMeltedDown(ShipAPI ship) {
        Reactor r = reactors.get(ship);
        return r != null && r.meltdown;
    }

    /** Reactor world position, or the ship's centre. */
    public Vector2f reactorLocation(ShipAPI ship) {
        Reactor r = reactors.get(ship);
        if (r == null) return new Vector2f(ship.getLocation());
        double f = Math.toRadians(ship.getFacing());
        float cos = (float) Math.cos(f);
        float sin = (float) Math.sin(f);
        return new Vector2f(ship.getLocation().x + r.forward * cos - r.left * sin,
                ship.getLocation().y + r.forward * sin + r.left * cos);
    }

    /** Draws power for TCO systems (ECM). False when dry or melted down. */
    public boolean draw(ShipAPI ship, float amount) {
        Reactor r = reactors.get(ship);
        if (r == null) return true;
        if (!TcoSettings.powerEnabled) return !r.meltdown;
        if (r.dry || r.meltdown) return false;
        r.power = Math.max(0f, r.power - amount);
        return true;
    }

    /** Permanently and quietly shuts down the reactor. */
    public void shutDown(ShipAPI ship) {
        Reactor r = reactors.get(ship);
        if (r == null) return;
        r.meltdown = true;
        r.dry = true;
        r.power = 0f;
    }

    /** Reactor repaired: power restarts from empty. */
    public void restoreReactor(ShipAPI ship) {
        Reactor r = reactors.get(ship);
        if (r == null) return;
        // Shields and energy weapons wait for the restart level
        r.meltdown = false;
        r.dry = true;
        if (ship.getOwner() == 0) Msg.about(engine, ship, ship.getName() + "'s reactor is back online.");
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine != null && engine.isPaused()) amount = 0f;
        // With power off, still track reactors for meltdowns
        if (engine == null || (!TcoSettings.powerEnabled && !TcoSettings.reactorMeltdownEnabled)) return;

        scanTimer -= amount;
        if (scanTimer <= 0f) {
            scanTimer = SCAN_INTERVAL;
            for (ShipAPI ship : engine.getShips()) {
                if (ship.getCustomData().containsKey(DONE_KEY)) continue;
                if (ship.isFighter() || ship.isDrone() || ship.isShuttlePod() || AmmoRules.isShuttle(ship)) {
                    ship.setCustomData(DONE_KEY, Boolean.TRUE);
                    continue;
                }
                if (ship.getFluxTracker() == null || ship.getMaxFlux() <= 0f) continue;
                ship.setCustomData(DONE_KEY, Boolean.TRUE);
                Reactor r = new Reactor();
                r.capacity = capacityFor(ship);
                r.power = r.capacity;
                placeReactor(ship, r);
                reactors.put(ship, r);
                if (TcoSettings.powerEnabled) ship.addListener(shieldListener);
            }
        }
        if (amount <= 0f) return;

        if (TcoSettings.powerEnabled) {
            applyPiercing();
            flyPiercing(amount);
            countShots();
        }
        aiTimer -= amount;
        boolean aiPass = aiTimer <= 0f;
        if (aiPass) aiTimer = AI_INTERVAL;

        Iterator<Map.Entry<ShipAPI, Reactor>> it = reactors.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ShipAPI, Reactor> entry = it.next();
            ShipAPI ship = entry.getKey();
            Reactor r = entry.getValue();
            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) {
                shieldBase.remove(ship);
                shieldSet.remove(ship);
                it.remove();
                continue;
            }

            if (!TcoSettings.powerEnabled) {
                // Meltdown kills engines and shields
                if (r.meltdown) {
                    if (!BackupGenerator.has(ship.getVariant())) keepEnginesDead(ship);
                    ShieldAPI shield = ship.getShield();
                    if (shield != null && shield.isOn()) shield.toggleOff();
                    ship.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK);
                    if (aiPass && isAiFlown(ship) && isDefaultAi(ship) && ship.getAIFlags() != null) {
                        ship.getAIFlags().setFlag(AIFlags.NEEDS_HELP, AI_FLAG_SECONDS);
                        ship.getAIFlags().setFlag(AIFlags.DO_NOT_PURSUE, AI_FLAG_SECONDS);
                    }
                }
                continue;
            }

            // Capacity follows flux capacity; the share is kept
            float capacity = capacityFor(ship);
            if (Math.abs(capacity - r.capacity) > 0.5f && r.capacity > 0f) {
                r.power *= capacity / r.capacity;
                r.capacity = capacity;
            }

            float regen = regenFor(ship.getMutableStats(), ship.getVariant());
            if (ship.getFluxTracker().isVenting()) regen *= 1f + TcoSettings.powerVentRegenBonus;
            float ceiling = r.capacity;
            if (r.meltdown) {
                // Emergency trickle
                regen *= TcoSettings.reactorEmergencyRegenMult;
                ceiling = r.capacity * TcoSettings.reactorEmergencyCapMult;
                if (!BackupGenerator.has(ship.getVariant())) keepEnginesDead(ship);
            }
            if (r.power < ceiling) r.power = Math.min(ceiling, r.power + regen * amount);
            // Beam upkeep
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                if (!weapon.isBeam() || !weapon.isFiring() || weapon.getDerivedStats() == null) continue;
                float perSecond = weapon.getDerivedStats().getFluxPerSecond();
                float mult = weapon.getType() == WeaponType.ENERGY ? TcoSettings.powerEnergyMult : TcoSettings.powerKineticBeamMult;
                r.power -= perSecond * mult * amount;
            }

            if (r.power <= 0f) {
                r.power = 0f;
                if (!r.dry && !r.meltdown) {
                    r.dry = true;
                    engine.addFloatingText(ship.getLocation(), "Power depleted", 22f, new Color(255, 200, 80), ship, 0f, 0f);
                }
            } else if (r.dry && r.power >= r.capacity * TcoSettings.powerRestartFraction) {
                r.dry = false;
            }

            if (r.dry || r.meltdown) cutPower(ship);
            fadeShield(ship, r);
            // Net change, smoothed over about half a second
            if (r.lastPower >= 0f) {
                float now = (r.power - r.lastPower) / amount;
                float k = 1f - (float) Math.exp(-amount / 0.5f);
                r.rate += (now - r.rate) * k;
            }
            r.lastPower = r.power;
            boolean aiFlown = isAiFlown(ship);
            holdUnaffordable(ship, r, aiFlown);
            if (aiFlown && aiPass && TcoSettings.powerAiEnabled) advise(ship, r);
        }
    }

    /**
     * Low power as the game's AI should feel it, as a flux level: 0 at or above powerAiLow, rising to 0.95 as
     * the reactor empties (and in a meltdown). The AI patch hands it the higher of this and its real flux.
     */
    public static float aiPressure(ShipAPI ship) {
        if (!TcoSettings.powerEnabled || !TcoSettings.powerAiEnabled) return 0f;
        PowerCombatPlugin plugin = get(Global.getCombatEngine());
        if (plugin == null) return 0f;
        Reactor r = plugin.reactors.get(ship);
        if (r == null || r.capacity <= 0f) return 0f;
        if (r.meltdown) return 0.95f;
        float low = TcoSettings.powerAiLow;
        float level = r.power / r.capacity;
        if (low <= 0f || level >= low) return 0f;
        return 0.95f * (1f - level / low);
    }

    private boolean isAiFlown(ShipAPI ship) {
        return !AiUtil.playerFlying(engine, ship);
    }

    private static boolean isDefaultAi(ShipAPI ship) {
        return AiUtil.isGameAi(ship);
    }

    private static boolean hasRealShield(ShipAPI ship) {
        ShieldAPI shield = ship.getShield();
        return shield != null && shield.getType() != ShieldType.NONE && shield.getType() != ShieldType.PHASE;
    }

    /**
     * Steers the game's AI through its flags: fire main groups when low, pull back when out, call for help
     * after a meltdown.
     */
    private void advise(ShipAPI ship, Reactor r) {
        if (!isDefaultAi(ship)) return;
        ShipwideAIFlags flags = ship.getAIFlags();
        if (flags == null) return;
        float level = r.capacity > 0f ? r.power / r.capacity : 0f;

        if (r.meltdown) {
            flags.setFlag(AIFlags.NEEDS_HELP, AI_FLAG_SECONDS);
            flags.setFlag(AIFlags.DO_NOT_PURSUE, AI_FLAG_SECONDS);
            return;
        }
        if (level < TcoSettings.powerAiLow) {
            flags.setFlag(AIFlags.DO_NOT_AUTOFIRE_NON_ESSENTIAL_GROUPS, AI_FLAG_SECONDS);
        }
        if (!hasRealShield(ship)) return;
        if (r.dry || level < TcoSettings.powerAiBackOff) r.recharging = true;
        else if (level >= TcoSettings.powerAiRecover) r.recharging = false;
        if (r.recharging) {
            flags.unsetFlag(AIFlags.DO_NOT_BACK_OFF);
            flags.setFlag(AIFlags.BACK_OFF, AI_FLAG_SECONDS);
            flags.setFlag(AIFlags.DO_NOT_PURSUE, AI_FLAG_SECONDS);
        }
    }

    /**
     * Power per shot: energy weapons their flux cost, ballistics a share, missiles and fluxless guns a flat
     * amount.
     */
    private static float shotCost(WeaponAPI weapon) {
        boolean onAmmo = EnergyAmmo.isMarked(weapon);
        if (weapon.getType() == WeaponType.ENERGY && !onAmmo) return weapon.getFluxCostToFire() * TcoSettings.powerEnergyMult;
        // Energy weapons on ammo pay like ballistics
        if ((weapon.getType() == WeaponType.BALLISTIC || onAmmo) && weapon.getFluxCostToFire() > 0f) {
            return weapon.getFluxCostToFire() * TcoSettings.powerBallisticMult;
        }
        WeaponSize size = weapon.getSize();
        return size == WeaponSize.LARGE ? TcoSettings.powerShotLarge
                : size == WeaponSize.MEDIUM ? TcoSettings.powerShotMedium : TcoSettings.powerShotSmall;
    }

    private void holdUnaffordable(ShipAPI ship, Reactor r, boolean aiFlown) {
        // AI shield reserve
        float reserve = aiFlown && TcoSettings.powerAiEnabled && hasRealShield(ship) && !r.meltdown
                ? r.capacity * TcoSettings.powerAiShieldReserve : 0f;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            WeaponType type = weapon.getType();
            if (type != WeaponType.BALLISTIC && type != WeaponType.MISSILE && type != WeaponType.ENERGY) continue;
            boolean pd = weapon.hasAIHint(AIHints.PD) || weapon.hasAIHint(AIHints.PD_ONLY);
            float keep = type == WeaponType.MISSILE || pd ? 0f : reserve;
            // Energy weapons are cutPower's job, except small PD after a meltdown
            if (type == WeaponType.ENERGY && !EnergyAmmo.isMarked(weapon) && keep <= 0f && !(r.meltdown && isSmallPd(weapon))) continue;
            float cost = weapon.isBeam() ? 0.01f : shotCost(weapon);
            if (r.power - cost < keep) weapon.setForceNoFireOneFrame(true);
        }
    }

    private float capacityFor(ShipAPI ship) {
        return capacityFor(ship.getMutableStats(), ship.getVariant());
    }

    /** Power capacity from flux capacity (also for the refit screen). */
    public static float capacityFor(MutableShipStatsAPI stats, ShipVariantAPI variant) {
        float capacity = stats.getFluxCapacity().getModifiedValue() * TcoSettings.powerCapacityMult;
        if (SecondaryEnergyBanks.has(variant)) capacity *= 1f + TcoSettings.energyBanksCapacityBonus;
        return Math.max(1f, capacity);
    }

    /** Power regenerated per second from flux dissipation, not counting venting (also for the refit screen). */
    public static float regenFor(MutableShipStatsAPI stats, ShipVariantAPI variant) {
        float regen = stats.getFluxDissipation().getModifiedValue() * TcoSettings.powerRegenMult;
        if (AuxiliaryGenerator.has(variant)) regen *= 1f + TcoSettings.auxGeneratorRegenBonus;
        return regen;
    }

    private void countShots() {
        for (DamagingProjectileAPI shot : engine.getProjectiles()) {
            if (counted.contains(shot)) continue;
            counted.add(shot);
            if (shot.isFromMissile() || ArmorEffects.isRicochet(shot)) continue;
            WeaponAPI weapon = shot.getWeapon();
            if (weapon == null || weapon.isBeam()) continue;
            Reactor r = reactors.get(weapon.getShip());
            if (r == null) continue;
            r.power = Math.max(0f, r.power - projectileCost(weapon, shot));
        }
    }

    /**
     * Each projectile pays for the flux of its own damage, the way the weapon card counts it. getFluxCostToFire
     * covers a whole burst, so charging it per projectile made burst weapons (needlers, Heavy Mauler) pay
     * burst-size times over.
     */
    private static float projectileCost(WeaponAPI weapon, DamagingProjectileAPI shot) {
        WeaponAPI.DerivedWeaponStatsAPI stats = weapon.getDerivedStats();
        float damage = shot.getBaseDamageAmount();
        float perDam = stats == null ? 0f : stats.getFluxPerDam();
        WeaponType type = weapon.getType();
        if (perDam > 0f && damage > 0f && (type == WeaponType.BALLISTIC || type == WeaponType.ENERGY)) {
            MutableShipStatsAPI s = weapon.getShip().getMutableStats();
            float flux = perDam * damage;
            if (type == WeaponType.BALLISTIC) {
                return s.getBallisticWeaponFluxCostMod().computeEffective(flux) * TcoSettings.powerBallisticMult;
            }
            flux = s.getEnergyWeaponFluxCostMod().computeEffective(flux);
            // The game's cached flux-per-damage is from before EnergyAmmo's cut
            if (EnergyAmmo.isMarked(weapon)) return flux * TcoSettings.energyAmmoFluxMult * TcoSettings.powerBallisticMult;
            return flux * TcoSettings.powerEnergyMult;
        }
        // Missiles and fluxless guns: the flat cost, shared by linked barrels and multi-projectile shots
        float full = shotCost(weapon);
        float perShot = stats == null ? 0f : stats.getDamagePerShot();
        if (perShot <= 0f || damage <= 0f) return full;
        return full * Math.min(1f, damage / perShot);
    }

    private void cutPower(ShipAPI ship) {
        ShieldAPI shield = ship.getShield();
        if (shield != null) {
            if (shield.isOn()) shield.toggleOff();
            ship.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK);
        }
        Reactor r = reactors.get(ship);
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            // Energy weapons on ammo are held by holdUnaffordable, like ballistics
            if (weapon.getType() != WeaponType.ENERGY || EnergyAmmo.isMarked(weapon)) continue;
            // Small PD runs on emergency power
            if (r != null && r.meltdown && isSmallPd(weapon)) continue;
            weapon.setForceNoFireOneFrame(true);
        }
    }

    private static boolean isSmallPd(WeaponAPI weapon) {
        return weapon.getSize() == WeaponSize.SMALL
                && (weapon.hasAIHint(AIHints.PD) || weapon.hasAIHint(AIHints.PD_ONLY));
    }

    // hold nozzles at zero hp so the game never relights them
    private void keepEnginesDead(ShipAPI ship) {
        if (ship.getEngineController() == null) return;
        for (ShipEngineAPI nozzle : ship.getEngineController().getShipEngines()) {
            if (!nozzle.isDisabled()) nozzle.disable();
            nozzle.setHitpoints(0f);
        }
    }

    private void placeReactor(ShipAPI ship, Reactor r) {
        Vector2f centre = new Vector2f(ship.getLocation());
        ArmorGridAPI grid = ship.getArmorGrid();
        if (grid != null) {
            float[][] cells = grid.getGrid();
            if (cells.length > 0 && cells[0].length > 0) {
                Vector2f a = grid.getLocation(0, 0);
                Vector2f b = grid.getLocation(cells.length - 1, cells[0].length - 1);
                if (a != null && b != null) centre.set((a.x + b.x) / 2f, (a.y + b.y) / 2f);
            }
        }
        double f = Math.toRadians(ship.getFacing());
        float fx = (float) Math.cos(f);
        float fy = (float) Math.sin(f);
        Vector2f spot = new Vector2f(centre);
        BoundsAPI bounds = ship.getExactBounds();
        if (bounds != null) {
            bounds.update(ship.getLocation(), ship.getFacing());
            List<SegmentAPI> segments = bounds.getSegments();
            float limit = ship.getCollisionRadius();
            for (float step = 0f; step <= limit; step += 4f) {
                Vector2f ahead = new Vector2f(centre.x + fx * step, centre.y + fy * step);
                if (inside(segments, ahead)) {
                    spot = ahead;
                    break;
                }
                Vector2f behind = new Vector2f(centre.x - fx * step, centre.y - fy * step);
                if (inside(segments, behind)) {
                    spot = behind;
                    break;
                }
            }
        }
        float dx = spot.x - ship.getLocation().x;
        float dy = spot.y - ship.getLocation().y;
        r.forward = dx * fx + dy * fy;
        r.left = -dx * fy + dy * fx;
    }

    private static boolean inside(List<SegmentAPI> segments, Vector2f point) {
        boolean in = false;
        for (SegmentAPI segment : segments) {
            Vector2f a = segment.getP1();
            Vector2f b = segment.getP2();
            if ((a.y > point.y) != (b.y > point.y)) {
                float crossX = a.x + (point.y - a.y) * (b.x - a.x) / (b.y - a.y);
                if (point.x < crossX) in = !in;
            }
        }
        return in;
    }

    private static float reactorRadius(HullSize size) {
        float base = size == HullSize.CAPITAL_SHIP ? 24f : size == HullSize.CRUISER ? 18f
                : size == HullSize.DESTROYER ? 14f : 10f;
        return base * TcoSettings.reactorRadiusMult;
    }

    /** A shell's path inside the hull may hit the reactor and cause a meltdown. */
    public void shellInside(ShipAPI ship, Vector2f entry, float dirX, float dirY, float depth, float hullDamage) {
        // Station modules have no reactor of their own to melt down
        if (!TcoSettings.reactorMeltdownEnabled || ship.isStationModule()) return;
        Reactor r = reactors.get(ship);
        if (r == null || r.meltdown || depth <= 0f) return;
        if (hullDamage < TcoSettings.reactorMinDamage) return;

        Vector2f core = reactorLocation(ship);
        float t = (core.x - entry.x) * dirX + (core.y - entry.y) * dirY;
        if (t < 0f || t > depth) return;
        float px = entry.x + dirX * t - core.x;
        float py = entry.y + dirY * t - core.y;
        float radius = reactorRadius(ship.getHullSize());
        if (px * px + py * py > radius * radius) return;
        if (Math.random() >= TcoSettings.reactorMeltdownChance) return;
        meltdown(ship, r, core);
    }

    private void meltdown(ShipAPI ship, Reactor r, Vector2f core) {
        r.meltdown = true;
        r.dry = true;
        r.power = 0f;
        ship.getFluxTracker().forceOverload(TcoSettings.reactorOverloadSeconds);
        // A Backup Generator keeps the engines running
        boolean backup = BackupGenerator.has(ship.getVariant());
        if (!backup && ship.getEngineController() != null) ship.getEngineController().forceFlameout(true);
        engine.spawnExplosion(core, ship.getVelocity(), new Color(120, 200, 255), 90f, 0.8f);
        engine.addHitParticle(core, ship.getVelocity(), 220f, 1f, 0.6f, new Color(160, 220, 255));
        engine.addFloatingText(core, "REACTOR MELTDOWN", 30f, new Color(120, 200, 255), ship, 0f, 0f);
        Global.getSoundPlayer().playSound("explosion_from_damage", 0.6f, 1.2f, core, ship.getVelocity());
        Msg.about(engine, ship, ship.getOwner() == 0 ? ship.getName() + "'s reactor is down. It needs a tender to restart."
                + (backup ? " Its backup generator keeps the engines running." : "")
                : ship.getName() + "''s reactor is down.");
    }


    /** 0 at or above shieldWeakStart of capacity, rising to 1 at empty. */
    private static float weakness(Reactor r) {
        if (r.meltdown || r.dry || r.capacity <= 0f) return 1f;
        float start = TcoSettings.shieldWeakStart;
        if (start <= 0f) return 0f;
        float level = r.power / r.capacity;
        return Math.max(0f, Math.min(1f, (start - level) / start));
    }

    /** 0 to 1: how weak low power makes the ship's shield (0 without a reactor). */
    public float shieldWeakness(ShipAPI ship) {
        Reactor r = reactors.get(ship);
        return r == null || !TcoSettings.powerEnabled ? 0f : weakness(r);
    }

    /**
     * Dims and thins the shield as it weakens, from the colours it had before (re-read whenever another mod
     * changes them), and puts them back once power recovers.
     */
    private void fadeShield(ShipAPI ship, Reactor r) {
        ShieldAPI shield = ship.getShield();
        if (shield == null || !hasRealShield(ship)) return;
        Color inner = shield.getInnerColor();
        Color ring = shield.getRingColor();
        if (inner == null || ring == null) return;
        Color[] set = shieldSet.get(ship);
        Color[] base = shieldBase.get(ship);
        if (set == null || base == null || !inner.equals(set[0]) || !ring.equals(set[1])) {
            base = new Color[] {inner, ring};
            shieldBase.put(ship, base);
        }
        float w = weakness(r);
        if (w <= 0f) {
            if (set != null) {
                shield.setInnerColor(base[0]);
                shield.setRingColor(base[1]);
                shieldSet.remove(ship);
                shieldBase.remove(ship);
            }
            return;
        }
        float keep = 1f - TcoSettings.shieldWeakFade * w;
        Color fadedInner = scaled(base[0], keep);
        Color fadedRing = scaled(base[1], keep);
        shield.setInnerColor(fadedInner);
        shield.setRingColor(fadedRing);
        shieldSet.put(ship, new Color[] {fadedInner, fadedRing});
    }

    private static Color scaled(Color c, float keep) {
        return new Color(Math.round(c.getRed() * keep), Math.round(c.getGreen() * keep), Math.round(c.getBlue() * keep),
                Math.round(c.getAlpha() * keep));
    }

    /**
     * The shield's hit area is the whole bubble, so the real shot can't carry on inside it. A pierced shot is
     * cancelled on the shield and a copy that collides with nothing flies on from the hit point, so it visibly goes
     * through. When the copy reaches the point where the shot's path enters the hull, the hit is applied there with
     * the original shot as the damage source, so TCO's armour rules (bounces, penetration, heat) still see a
     * projectile hit. A path that misses the hull just flies out the far side.
     */
    private void applyPiercing() {
        if (piercing.isEmpty()) return;
        for (Object[] p : piercing) {
            ShipAPI ship = (ShipAPI) p[0];
            DamagingProjectileAPI shot = (DamagingProjectileAPI) p[1];
            Vector2f from = (Vector2f) p[2];
            Vector2f entry = (Vector2f) p[3];
            Vector2f then = (Vector2f) p[4];
            float dealt = (Float) p[5];
            float emp = (Float) p[6];
            try {
                if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) continue;
                float dx = ship.getLocation().x - then.x, dy = ship.getLocation().y - then.y;
                Vector2f start = new Vector2f(from.x + dx, from.y + dy);
                DamagingProjectileAPI visual = spawnVisual(shot, start);
                puncture(ship, start);
                if (entry == null) continue;
                Vector2f hit = new Vector2f(entry.x + dx, entry.y + dy);
                if (visual == null) {
                    // No copy (no weapon to spawn it from): strike at once
                    strike(ship, shot, hit, dealt, emp);
                    continue;
                }
                Pierce f = new Pierce();
                f.ship = ship;
                f.shot = shot;
                f.visual = visual;
                f.damage = dealt;
                f.emp = emp;
                double a = Math.toRadians(ship.getFacing());
                float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
                float rx = hit.x - ship.getLocation().x, ry = hit.y - ship.getLocation().y;
                f.forward = rx * cos + ry * sin;
                f.left = -rx * sin + ry * cos;
                flying.add(f);
            } catch (RuntimeException e) {
                Global.getLogger(PowerCombatPlugin.class).warn("TCO: could not pass a shot through a weak shield", e);
            }
        }
        piercing.clear();
    }

    // The copy starts this far back along the shot's path (seconds of its travel, capped in units)
    private static final float VISUAL_LEAD = 1f / 60f;
    private static final float VISUAL_LEAD_MAX = 60f;

    /** A spark in the shield's own colour where the shot punches through. */
    private void puncture(ShipAPI ship, Vector2f at) {
        ShieldAPI shield = ship.getShield();
        Color ring = shield != null && shield.getRingColor() != null ? shield.getRingColor() : new Color(150, 200, 255);
        Color spark = new Color(Math.min(255, ring.getRed() + 60), Math.min(255, ring.getGreen() + 60),
                Math.min(255, ring.getBlue() + 60), 255);
        engine.addHitParticle(at, ship.getVelocity(), 30f, 0.9f, 0.1f, spark);
    }

    /** A copy of the shot that collides with nothing, from the shield hit point on the shot's heading and speed. */
    private DamagingProjectileAPI spawnVisual(DamagingProjectileAPI shot, Vector2f at) {
        WeaponAPI weapon = shot.getWeapon();
        if (weapon == null) return null;
        Vector2f v = shot.getVelocity();
        float heading = (float) Math.toDegrees(Math.atan2(v.y, v.x));
        // One frame's travel back, where the real shot was last drawn: the copy carries on from there and is seen
        // crossing the shield edge, however small the gap to the hull
        float speed = v.length();
        Vector2f from = at;
        if (speed >= 1f) {
            float back = Math.min(speed * VISUAL_LEAD, VISUAL_LEAD_MAX) / speed;
            from = new Vector2f(at.x - v.x * back, at.y - v.y * back);
        }
        CombatEntityAPI spawned = engine.spawnProjectile(shot.getSource(), weapon, weapon.getId(), from, heading, new Vector2f());
        if (!(spawned instanceof DamagingProjectileAPI)) {
            if (spawned != null) engine.removeEntity(spawned);
            return null;
        }
        DamagingProjectileAPI visual = (DamagingProjectileAPI) spawned;
        visual.setCollisionClass(CollisionClass.NONE);
        visual.getVelocity().set(v);
        visual.setDamageAmount(0f);
        // Already paid for when fired
        counted.add(visual);
        return visual;
    }

    /** Moves each copy on; once it reaches (or passes) its hull entry point the hit lands there. */
    private void flyPiercing(float amount) {
        if (flying.isEmpty()) return;
        for (Iterator<Pierce> it = flying.iterator(); it.hasNext();) {
            Pierce f = it.next();
            f.age += amount;
            ShipAPI ship = f.ship;
            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) {
                it.remove();
                continue;
            }
            double a = Math.toRadians(ship.getFacing());
            float cos = (float) Math.cos(a), sin = (float) Math.sin(a);
            Vector2f hit = new Vector2f(ship.getLocation().x + f.forward * cos - f.left * sin,
                    ship.getLocation().y + f.forward * sin + f.left * cos);
            Vector2f at = f.visual.getLocation();
            Vector2f v = f.visual.getVelocity();
            float speed = v.length();
            float ahead = speed < 1f ? 0f : ((hit.x - at.x) * v.x + (hit.y - at.y) * v.y) / speed;
            // Struck once the copy has actually reached the hull (it's drawn the whole way), or it's lost
            boolean arrived = ahead <= 0f || f.age > 2f || !engine.isEntityInPlay(f.visual);
            if (!arrived) continue;
            strike(ship, f.shot, hit, f.damage, f.emp);
            if (engine.isEntityInPlay(f.visual)) engine.removeEntity(f.visual);
            it.remove();
        }
    }

    /** The shot's own damage, type and EMP, as a normal hull hit would deal. */
    private void strike(ShipAPI ship, DamagingProjectileAPI shot, Vector2f hit, float dealt, float emp) {
        engine.applyDamage(shot, ship, hit, dealt, shot.getDamageType(), emp,
                true, false, shot.getSource(), true);
        engine.addHitParticle(hit, ship.getVelocity(), 35f, 1f, 0.12f, new Color(255, 220, 170));
        if (!loggedPierce) {
            loggedPierce = true;
            Global.getLogger(PowerCombatPlugin.class).info("TCO power: a shot pierced " + ship.getName()
                    + "'s weak shield (" + Math.round(dealt) + " damage, " + Math.round(emp) + " EMP to the hull)");
        }
    }

    private boolean loggedPierce;

    /**
     * How much of the pierce chance gets through this ship's shield: shield systems (Fortress Shield and any listed in
     * pierceBlockingSystems) cut it to pierceSystemMult while active, scaled by how far the system is on; Hardened
     * Shields to pierceHardenedMult.
     */
    private static float pierceResistance(ShipAPI ship) {
        float mult = 1f;
        if (ship.getVariant() != null && ship.getVariant().hasHullMod(HARDENED_SHIELDS)) mult *= TcoSettings.pierceHardenedMult;
        ShipSystemAPI system = ship.getSystem();
        if (system != null && system.getId() != null && TcoSettings.pierceBlockingSystems.contains(system.getId())) {
            float level = system.isActive() ? Math.max(0f, Math.min(1f, system.getEffectLevel())) : 0f;
            mult *= 1f - (1f - TcoSettings.pierceSystemMult) * level;
        }
        return mult;
    }

    private static final String HARDENED_SHIELDS = "hardenedshieldemitter";
    private static final String STABILIZED_SHIELDS = "stabilizedshieldemitter";

    /** Power a shield hit draws per point of flux it causes: less with Stabilized Shields. */
    public static float shieldPowerMult(ShipVariantAPI variant) {
        float mult = TcoSettings.powerShieldMult;
        if (variant != null && variant.hasHullMod(STABILIZED_SHIELDS)) mult *= TcoSettings.stabilizedShieldPowerMult;
        return mult;
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

    /** Shield hits draw power in proportion to their flux. */
    private class ShieldListener implements DamageTakenModifier {
        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage,
                Vector2f point, boolean shieldHit) {
            if (!shieldHit || !(target instanceof ShipAPI)) return null;
            ShipAPI ship = (ShipAPI) target;
            Reactor r = reactors.get(ship);
            ShieldAPI shield = ship.getShield();
            if (r == null || shield == null) return null;
            // A weak shield lets kinetic shots through. The shield still takes a share of the hit (flux and power,
            // shieldPierceShare); the shot carries on and strikes the hull behind it with its full damage
            boolean pierced = false;
            if (param instanceof DamagingProjectileAPI && !(param instanceof MissileAPI)
                    && damage.getType() == DamageType.KINETIC && !damage.isDps() && point != null) {
                DamagingProjectileAPI shot = (DamagingProjectileAPI) param;
                float chance = TcoSettings.shieldPierceMax * weakness(r) * pierceResistance(ship);
                if (chance > 0f && Math.random() < chance) {
                    Vector2f v = shot.getVelocity();
                    float speed = v.length();
                    if (speed >= 1f) {
                        Vector2f entry = hullEntry(ship, point, v.x / speed, v.y / speed);
                        // Through the shield either way; it only does damage if its path meets the hull
                        // Damage and EMP read before the shield's share is set on this hit
                        piercing.add(new Object[] {ship, shot, new Vector2f(point), entry, new Vector2f(ship.getLocation()),
                                shot.getDamageAmount(), shot.getEmpAmount()});
                        damage.getModifier().modifyMult(PIERCE_ID, TcoSettings.shieldPierceShare);
                        pierced = true;
                    }
                }
            }
            float amount = damage.getDamage();
            if (damage.isDps()) amount *= engine.getElapsedInLastFrame();
            float flux = amount * damage.getType().getShieldMult() * shield.getFluxPerPointOfDamage();
            if (pierced) flux *= TcoSettings.shieldPierceShare;
            float drawn = flux * shieldPowerMult(ship.getVariant());
            r.power = Math.max(0f, r.power - drawn);
            if (!loggedShieldHit) {
                loggedShieldHit = true;
                Global.getLogger(PowerCombatPlugin.class).info("TCO power: shield hit on " + ship.getName()
                        + " drew " + Math.round(drawn) + " power");
            }
            return pierced ? PIERCE_ID : null;
        }
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        if (engine == null || !TcoSettings.powerEnabled || !TcoSettings.powerHud) return;
        ShipAPI ship = engine.getPlayerShip();
        Reactor r = ship == null ? null : reactors.get(ship);
        if (r == null || engine.getCombatUI() == null || engine.getCombatUI().isShowingCommandUI()) return;
        // The RTS view shows power on the ship cards; hidden with the game's own HUD and over its dialogs
        if (RtsCombatPlugin.isRtsView(engine)) return;
        if (!engine.isUIShowingHUD() || engine.isUIShowingDialog() || engine.getCombatUI().isShowingDeploymentDialog()) return;

        float cap = Math.max(1f, r.capacity);
        float level = Math.max(0f, Math.min(1f, r.power / cap));
        float now = engine.getTotalElapsedTime(true);
        float flash = r.dry || r.meltdown ? 0.55f + 0.45f * (float) Math.sin(now * 8f) : 1f;
        float next = groupCost(ship);
        float cost = next > 0f ? Math.min(1f, next / cap) : 0f;
        boolean shielded = hasRealShield(ship) && TcoSettings.shieldWeakStart > 0f;

        // Right above the flagship HUD's status list (moves up as statuses are added), or the fixed upright spot
        float[] anchor = null;
        try {
            anchor = StatusAnchor.top(engine);
        } catch (Throwable e) {
            anchor = null;
        }
        boolean across = anchor != null;
        float x, y, width, height;
        if (across) {
            x = anchor[0];
            y = anchor[1] + HUD_GAP;
            width = TcoSettings.powerHudWidth;
            height = 8f;
        } else {
            x = TcoSettings.powerHudX;
            y = TcoSettings.powerHudY;
            width = 12f;
            height = TcoSettings.powerHudHeight;
        }

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        // Frame
        GL11.glColor4f(0f, 0f, 0f, 0.65f);
        quad(x - 3f, y - 3f, width + 6f, height + 6f);
        GL11.glColor4f(0.45f, 0.55f, 0.6f, 0.6f);
        outline(x - 2f, y - 2f, width + 4f, height + 4f);
        GL11.glColor4f(0.12f, 0.14f, 0.16f, 0.9f);
        quad(x, y, width, height);
        // Fill
        float[] c = barColor(r);
        GL11.glColor4f(c[0], c[1], c[2], 0.95f * flash);
        if (across) quad(x, y, width * level, height);
        else quad(x, y, width, height * level);
        // The selected group's next shot: the part of the fill it would use, or the shortfall past it
        if (cost > 0f) {
            if (cost <= level) {
                GL11.glColor4f(1f, 1f, 1f, 0.45f);
                if (across) quad(x + width * (level - cost), y, width * cost, height);
                else quad(x, y + height * (level - cost), width, height * cost);
            } else {
                GL11.glColor4f(1f, 0.3f, 0.25f, 0.5f);
                if (across) quad(x + width * level, y, width * (cost - level), height);
                else quad(x, y + height * level, width, height * (cost - level));
            }
        }
        // Marks: restart level, and where the shield starts to weaken
        if (across) {
            mark(x + width * TcoSettings.powerRestartFraction, y, height, 0.9f, 0.9f, 0.9f, 0.8f);
            if (shielded) mark(x + width * TcoSettings.shieldWeakStart, y, height, 0.55f, 0.8f, 1f, 0.8f);
        } else {
            tick(x, y + height * TcoSettings.powerRestartFraction, width, 0.9f, 0.9f, 0.9f, 0.8f);
            if (shielded) tick(x, y + height * TcoSettings.shieldWeakStart, width, 0.55f, 0.8f, 1f, 0.8f);
        }
        GL11.glPopAttrib();
        // Labels: one line above the bar when anchored, two lines beside it when upright
        float topX = across ? x : x + width + 8f;
        float topY = across ? y + height + 4f : y + 16f;
        float rateX = across ? x + 150f : x + width + 8f;
        float rateY = across ? y + height + 4f : y;

        String state = r.meltdown ? "REACTOR DOWN" : r.dry ? "DEPLETED"
                : shieldWeakness(ship) > 0f && hasRealShield(ship) ? "SHIELD WEAK" : "";
        String top = "POWER  " + Math.round(r.power) + " / " + Math.round(r.capacity);
        int rate = Math.round(r.rate);
        String bottom = (rate > 0 ? "+" : "") + rate + "/s" + (next > 0f ? "   next shot " + Math.round(next) : "")
                + (state.isEmpty() ? "" : "   " + state);
        try {
            if (hudLabel == null) hudLabel = Global.getSettings().createLabel(top, Fonts.DEFAULT_SMALL);
            if (!top.equals(hudText)) {
                hudLabel.setText(top);
                hudText = top;
            }
            hudLabel.setColor(new Color(210, 215, 220));
            hudLabel.getPosition().setSize(260f, 16f);
            hudLabel.getPosition().setLocation(topX, topY);
            hudLabel.render(1f);
            if (rateLabel == null) rateLabel = Global.getSettings().createLabel(bottom, Fonts.DEFAULT_SMALL);
            if (!bottom.equals(rateText)) {
                rateLabel.setText(bottom);
                rateText = bottom;
            }
            boolean bad = r.dry || r.meltdown || !state.isEmpty() || (next > 0f && next > r.power);
            rateLabel.setColor(bad ? new Color(255, 140, 90) : rate < 0 ? new Color(230, 200, 120) : new Color(140, 220, 150));
            rateLabel.getPosition().setSize(320f, 16f);
            rateLabel.getPosition().setLocation(rateX, rateY);
            rateLabel.render(1f);
        } catch (RuntimeException e) {
            hudLabel = null;
            rateLabel = null;
        }
    }

    /** Power the selected weapon group's next shot takes (a burst counted whole, beams one second). */
    private static float groupCost(ShipAPI ship) {
        WeaponGroupAPI group = ship.getSelectedGroupAPI();
        if (group == null) return 0f;
        float total = 0f;
        for (WeaponAPI weapon : group.getWeaponsCopy()) {
            if (weapon.isDecorative() || weapon.isDisabled()) continue;
            if (weapon.isBeam()) {
                if (weapon.getDerivedStats() == null) continue;
                total += weapon.getDerivedStats().getFluxPerSecond()
                        * (weapon.getType() == WeaponType.ENERGY ? TcoSettings.powerEnergyMult : TcoSettings.powerKineticBeamMult);
            } else {
                total += shotCost(weapon);
            }
        }
        return total;
    }

    private static final float FLASH_SPEED = 8f;

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (engine == null || !TcoSettings.powerEnabled || !TcoSettings.powerReticle || reactors.isEmpty()) return;
        if (engine.getCombatUI() != null && engine.getCombatUI().isShowingCommandUI()) return;
        float px = viewport.getViewMult();
        float now = engine.getTotalElapsedTime(true);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (Map.Entry<ShipAPI, Reactor> entry : reactors.entrySet()) {
            ShipAPI ship = entry.getKey();
            Reactor r = entry.getValue();
            if (!ship.isAlive() || ship.isHulk() || ship.isForceHideFFOverlay() || ship.isStationModule()
                    || FogOfWar.isHidden(ship)) continue;
            Vector2f centre = ship.getShieldCenterEvenIfNoShield();
            if (centre == null) centre = ship.getLocation();
            float size = ship.getShieldRadiusEvenIfNoShield() * 0.9f;
            if (ship.getSpriteAPI() != null) {
                size = Math.min(size, (ship.getSpriteAPI().getWidth() + ship.getSpriteAPI().getHeight()) * 0.5f);
            }
            size = Math.max(25f, size);
            if (!viewport.isNearViewport(centre, size + 50f)) continue;

            float width = Math.max(size * 1.2f, 40f * px);
            float height = TcoSettings.powerReticleThickness * px;
            float x = centre.x - width / 2f;
            float y = centre.y - size - TcoSettings.powerReticleGap * px - height;
            float level = r.capacity > 0f ? Math.max(0f, Math.min(1f, r.power / r.capacity)) : 0f;
            float alpha = ship.getOwner() == 0 ? 0.85f : 0.75f;
            // Empty or melted down: flashes
            float flash = r.dry || r.meltdown ? 0.45f + 0.55f * Math.abs((float) Math.sin(now * FLASH_SPEED * 0.5f)) : 1f;

            GL11.glColor4f(0f, 0f, 0f, 0.6f * alpha);
            quad(x - px, y - px, width + 2f * px, height + 2f * px);
            GL11.glColor4f(0.25f, 0.25f, 0.25f, alpha);
            quad(x, y, width, height);
            float[] c = barColor(r);
            GL11.glColor4f(c[0], c[1], c[2], alpha * flash);
            quad(x, y, width * (r.dry || r.meltdown ? 1f : level), height);
            // The share the AI keeps for its shield
            if (TcoSettings.powerAiEnabled && TcoSettings.powerAiShieldReserve > 0f && hasRealShield(ship) && isAiFlown(ship)) {
                float mx = x + width * TcoSettings.powerAiShieldReserve;
                GL11.glColor4f(0.6f, 0.85f, 1f, 0.7f * alpha);
                quad(mx - px * 0.5f, y - px, px, height + 2f * px);
            }
        }
        GL11.glPopAttrib();
    }

    public float[] barColor(ShipAPI ship) {
        Reactor r = reactors.get(ship);
        return r == null ? new float[] {0.5f, 0.5f, 0.5f} : barColor(r);
    }

    /** Green when well charged, yellow lower, red once the shield weakens; orange empty, deep red melted down. */
    private static float[] barColor(Reactor r) {
        if (r.meltdown) return new float[] {0.85f, 0.2f, 0.2f};
        if (r.dry) return new float[] {1f, 0.55f, 0.2f};
        float level = r.capacity > 0f ? r.power / r.capacity : 0f;
        if (level > 0.5f) return new float[] {0.45f, 0.9f, 0.5f};
        if (level > Math.max(0.01f, TcoSettings.shieldWeakStart)) return new float[] {1f, 0.85f, 0.3f};
        return new float[] {1f, 0.35f, 0.25f};
    }

    /** A vertical mark across a horizontal bar. */
    private static void mark(float x, float y, float height, float red, float green, float blue, float alpha) {
        GL11.glColor4f(red, green, blue, alpha);
        quad(x - 0.75f, y - 3f, 1.5f, height + 6f);
    }

    private static final float HUD_GAP = 6f;

    private static void tick(float x, float y, float width, float red, float green, float blue, float alpha) {
        GL11.glColor4f(red, green, blue, alpha);
        quad(x - 3f, y - 0.5f, width + 6f, 1.5f);
    }

    private static void outline(float x, float y, float width, float height) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + width, y);
        GL11.glVertex2f(x + width, y + height);
        GL11.glVertex2f(x, y + height);
        GL11.glEnd();
    }

    private static void quad(float x, float y, float width, float height) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + width, y);
        GL11.glVertex2f(x + width, y + height);
        GL11.glVertex2f(x, y + height);
        GL11.glEnd();
    }
}
