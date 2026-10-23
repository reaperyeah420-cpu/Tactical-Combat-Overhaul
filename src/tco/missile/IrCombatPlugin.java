package tco.missile;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.GuidedMissileAI;
import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipSystemSpecAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import tco.AiUtil;
import tco.TcoSettings;
import tco.ammo.AmmoRules;
import tco.battle.DeathThroes;
import tco.radar.RadarCombatPlugin;
import tco.rts.FogOfWar;

/**
 * IR missiles and flares.
 * <p>
 * Ships have a heat signature from engines and flux. IR missiles can lose cold targets and then
 * search a cone ahead for heat; flares decoy them by a chance weighed against the target's heat.
 * TCO's flares are drawn particles, so vanilla's missile AI never sees them. While a missile is off
 * target, {@link Seeker} replaces its AI, and its own AI is restored on reacquiring a ship.
 */
public class IrCombatPlugin extends BaseEveryFrameCombatPlugin {

    public static final String ENGINE_KEY = "tco_ir_plugin";
    public static final String ECD_ID = "tco_ecd";
    public static final String IRCCM_ID = "tco_irccm";
    private static final float CHECK_INTERVAL = 0.5f;
    private static final float SCAN_INTERVAL = 0.25f;
    private static final float SEEKER_CONE = 40f;
    private static final float SEEKER_RANGE = 1400f;
    private static final float FLARE_CONE = 60f;
    private static final Color FLARE_CORE = new Color(255, 240, 200);
    private static final Color FLARE_GLOW = new Color(255, 170, 90);
    private static final Color FLARE_SMOKE = new Color(150, 145, 140, 110);

    /** A burning flare: TCO's own or a vanilla flare missile. */
    public static final class Flare {
        final Vector2f loc = new Vector2f();
        final Vector2f vel = new Vector2f();
        float age;
        float life;
        int owner;
        float nextSmoke;
        MissileAPI entity;

        float heat() {
            if (entity != null) return VANILLA_FLARE_HEAT;
            float left = 1f - age / Math.max(0.1f, life);
            return 0.35f + 0.65f * Math.max(0f, left);
        }

        boolean burning() {
            if (entity != null) {
                CombatEngineAPI engine = Global.getCombatEngine();
                return engine != null && engine.isEntityInPlay(entity) && !entity.isFading() && !entity.isFizzling();
            }
            return age < life;
        }
    }

    private static final float VANILLA_FLARE_HEAT = 0.9f;
    private static final String ECCM_TAG = "tco_flare_eccm";

    private static final class Tracked {
        float nextCheck;
        float nextScan;
        MissileAIPlugin original;
        Seeker seeker;
        final Set<Flare> rolled = new HashSet<Flare>();
        boolean ignoresFlares;
        // original ECCM chance, before TCO overrides it to block vanilla flares
        float eccm;
        boolean irccm;
    }

    private CombatEngineAPI engine;
    private final List<Flare> flares = new ArrayList<Flare>();
    private final List<Flare> allFlares = new ArrayList<Flare>();
    private final Map<MissileAPI, Flare> vanillaFlares = new WeakHashMap<MissileAPI, Flare>();
    private final Map<MissileAPI, Tracked> tracked = new WeakHashMap<MissileAPI, Tracked>();
    private final Map<ShipAPI, Integer> stock = new WeakHashMap<ShipAPI, Integer>();
    private final Map<ShipAPI, Float> ready = new WeakHashMap<ShipAPI, Float>();
    private final Map<ShipAPI, Float> autoReady = new WeakHashMap<ShipAPI, Float>();
    private final Map<ShipAPI, Integer> reloads = new WeakHashMap<ShipAPI, Integer>();
    private final Set<ShipAPI> systemWasActive = new HashSet<ShipAPI>();
    private static final Map<String, Boolean> flareSystems = new HashMap<String, Boolean>();

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
    }

    public static IrCombatPlugin get(CombatEngineAPI engine) {
        Object plugin = engine == null ? null : engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof IrCombatPlugin ? (IrCombatPlugin) plugin : null;
    }

    public static boolean carriesFlares(ShipAPI ship) {
        if (ship == null || ship.isFighter() || ship.isDrone() || ship.isShuttlePod() || ship.isStationModule()) return false;
        return !AmmoRules.isShuttle(ship);
    }

    /** Flares at the start of battle. */
    public static int capacity(ShipAPI ship) {
        if (!carriesFlares(ship)) return 0;
        HullSize size = ship.getHullSize();
        float base = size == HullSize.CAPITAL_SHIP ? TcoSettings.flaresCapital : size == HullSize.CRUISER ? TcoSettings.flaresCruiser
                : size == HullSize.DESTROYER ? TcoSettings.flaresDestroyer : TcoSettings.flaresFrigate;
        if (ship.getVariant() != null && ship.getVariant().hasHullMod(ECD_ID)) {
            base *= size == HullSize.FRIGATE ? TcoSettings.ecdFrigateMult : TcoSettings.ecdOtherMult;
        }
        return Math.round(base);
    }

    public static int salvo(ShipAPI ship) {
        HullSize size = ship.getHullSize();
        return size == HullSize.CAPITAL_SHIP ? TcoSettings.flareSalvoCapital : size == HullSize.CRUISER ? TcoSettings.flareSalvoCruiser
                : size == HullSize.DESTROYER ? TcoSettings.flareSalvoDestroyer : TcoSettings.flareSalvoFrigate;
    }

    /** Flares left, or -1 if it carries none. */
    public static int flaresLeft(CombatEngineAPI engine, ShipAPI ship) {
        IrCombatPlugin plugin = get(engine);
        if (plugin == null || !TcoSettings.flaresEnabled || !carriesFlares(ship)) return -1;
        return plugin.left(ship);
    }

    private int left(ShipAPI ship) {
        Integer n = stock.get(ship);
        if (n == null) {
            n = capacity(ship);
            stock.put(ship, n);
        }
        return n;
    }

    /** Whether the ship system's weapon fires flare missiles (from any mod). */
    public static boolean hasFlareSystem(ShipAPI ship) {
        if (ship == null || ship.getSystem() == null) return false;
        String id = ship.getSystem().getId();
        Boolean known = flareSystems.get(id);
        if (known == null) {
            known = Boolean.FALSE;
            try {
                ShipSystemSpecAPI spec = Global.getSettings().getShipSystemSpec(id);
                String weaponId = spec == null ? null : spec.getWeaponId();
                WeaponSpecAPI weapon = weaponId == null || weaponId.isEmpty() ? null : Global.getSettings().getWeaponSpec(weaponId);
                Object proj = weapon == null ? null : weapon.getProjectileSpec();
                if (proj instanceof MissileSpecAPI) {
                    String type = ((MissileSpecAPI) proj).getTypeString();
                    known = type != null && type.startsWith("FLARE");
                }
            } catch (RuntimeException e) {
            }
            flareSystems.put(id, known);
        }
        return known;
    }

    private static boolean reloadsInstead(ShipAPI ship) {
        return TcoSettings.flaresEnabled && TcoSettings.flareSystemReloads && carriesFlares(ship) && hasFlareSystem(ship);
    }

    /** Reloads left, or -1 without a flare system. */
    public static int reloadsLeft(CombatEngineAPI engine, ShipAPI ship) {
        IrCombatPlugin plugin = get(engine);
        if (plugin == null || !reloadsInstead(ship)) return -1;
        return plugin.reloadsOf(ship);
    }

    private int reloadsOf(ShipAPI ship) {
        Integer n = reloads.get(ship);
        if (n == null) {
            n = Math.max(0, TcoSettings.flareReloads);
            reloads.put(ship, n);
        }
        return n;
    }

    private boolean reload(ShipAPI ship) {
        int r = reloadsOf(ship);
        if (r <= 0) return false;
        reloads.put(ship, r - 1);
        stock.put(ship, capacity(ship));
        if (visible(ship.getLocation(), ship.getOwner())) {
            engine.addFloatingText(ship.getLocation(), "Flares reloaded (" + (r - 1) + " left)", 20f,
                    new Color(255, 210, 140), ship, 0.5f, 1f);
        }
        return true;
    }

    /**
     * Flare systems become reloads: held off except for the player's manual early reload; AI ships reload
     * when dry.
     */
    private void handleFlareSystems() {
        ShipAPI player = engine.getPlayerShip();
        for (ShipAPI ship : engine.getShips()) {
            if (!ship.isAlive() || ship.isHulk() || !reloadsInstead(ship)) continue;
            ShipSystemAPI system = ship.getSystem();
            int r = reloadsOf(ship);
            boolean flown = AiUtil.playerFlying(engine, ship);
            if (!flown || r <= 0 || left(ship) >= capacity(ship)) ship.blockCommandForOneFrame(ShipCommand.USE_SYSTEM);
            if (system.getMaxAmmo() > 0 && system.getMaxAmmo() < 1000) {
                system.setAmmo(Math.min(r, system.getMaxAmmo()));
                system.setAmmoReloadProgress(0f);
            }
            boolean active = system.isActive();
            if (active && !systemWasActive.contains(ship)) {
                systemWasActive.add(ship);
                reload(ship);
            } else if (!active) {
                systemWasActive.remove(ship);
            }
        }
    }

    /** Drops a salvo from each ship; returns how many did. */
    public static int dispense(CombatEngineAPI engine, List<ShipAPI> ships) {
        IrCombatPlugin plugin = get(engine);
        if (plugin == null || !TcoSettings.flaresEnabled) return 0;
        int done = 0;
        for (ShipAPI ship : ships) {
            if (plugin.drop(ship, false)) done++;
        }
        return done;
    }

    private boolean drop(ShipAPI ship, boolean auto) {
        if (ship == null || !ship.isAlive() || ship.isHulk() || !carriesFlares(ship)) return false;
        if (DeathThroes.isDying(ship)) return false;
        float now = engine.getTotalElapsedTime(false);
        Float r = ready.get(ship);
        if (r != null && now < r) return false;
        int left = left(ship);
        // Out of flares: reload if possible
        if (left <= 0 && reloadsInstead(ship) && reload(ship)) left = left(ship);
        if (left <= 0) return false;
        int n = Math.min(left, salvo(ship));
        stock.put(ship, left - n);
        ready.put(ship, now + TcoSettings.flareCooldown);
        if (auto) autoReady.put(ship, now + TcoSettings.flareAutoCooldown);

        Vector2f c = ship.getLocation();
        double back = Math.toRadians(ship.getFacing() + 180f);
        float r0 = ship.getCollisionRadius() * 0.35f;
        for (int i = 0; i < n; i++) {
            Flare f = new Flare();
            // Spread to the sides and behind
            float spread = n == 1 ? 0f : -75f + 150f * i / (n - 1);
            double a = Math.toRadians(ship.getFacing() + 180f + spread + (Math.random() - 0.5) * 20.0);
            float speed = 110f + (float) Math.random() * 110f;
            f.loc.set(c.x + (float) Math.cos(back) * r0, c.y + (float) Math.sin(back) * r0);
            f.vel.set(ship.getVelocity().x + (float) Math.cos(a) * speed, ship.getVelocity().y + (float) Math.sin(a) * speed);
            f.life = TcoSettings.flareLife * (0.85f + (float) Math.random() * 0.3f);
            f.owner = ship.getOwner();
            flares.add(f);
        }
        if (visible(c, ship.getOwner())) {
            Global.getSoundPlayer().playSound("launch_flare_1", 1f, 1f, c, ship.getVelocity());
        }
        return true;
    }

    /** Heat signature 0-1, seen from a point (null: no particular side). */
    public static float heat(ShipAPI ship, Vector2f from) {
        float engines = 0f;
        ShipEngineControllerAPI ec = ship.getEngineController();
        if (ec != null && !ec.isFlamedOut()) {
            float total = 0f, working = 0f;
            for (ShipEngineAPI e : ec.getShipEngines()) {
                if (e.isSystemActivated()) continue;
                float c = Math.max(0.01f, e.getContribution());
                total += c;
                if (!e.isDisabled()) working += c;
            }
            engines = total > 0f ? working / total : 1f;
            boolean thrusting = ec.isAccelerating() || ec.isAcceleratingBackwards() || ec.isDecelerating()
                    || ec.isStrafingLeft() || ec.isStrafingRight() || ec.isTurningLeft() || ec.isTurningRight();
            if (!thrusting) engines *= 0.6f;
            if (from != null) {
                // Exhaust aspect is hotter
                float toFrom = (float) Math.toDegrees(Math.atan2(from.y - ship.getLocation().y, from.x - ship.getLocation().x));
                float off = Math.abs(angleDiff(toFrom, ship.getFacing() + 180f));
                if (off < 60f) engines *= 1.3f;
                else if (off > 120f) engines *= 0.75f;
            }
        }
        float flux = ship.getFluxTracker() == null ? 0f
                : ship.getFluxTracker().isVenting() ? 1f : ship.getFluxTracker().getFluxLevel();
        return Math.max(0f, Math.min(1f, 0.1f + 0.5f * engines + 0.5f * flux));
    }

    private static float angleDiff(float a, float b) {
        float d = (a - b) % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    private static float angleTo(Vector2f from, Vector2f to) {
        return (float) Math.toDegrees(Math.atan2(to.y - from.y, to.x - from.x));
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || !TcoSettings.flaresEnabled && !TcoSettings.irLockEnabled) return;
        if (engine.isPaused() || amount <= 0f) return;
        float now = engine.getTotalElapsedTime(false);
        advanceFlares(amount);
        if (TcoSettings.flaresEnabled && TcoSettings.flareSystemReloads) handleFlareSystems();
        collectFlares();

        Set<ShipAPI> threatened = new HashSet<ShipAPI>();
        for (MissileAPI missile : engine.getMissiles()) {
            if (missile.isFizzling() || missile.isFading() || missile.isFlare() || missile.didDamage()) continue;
            WeaponSpecAPI spec = missile.getWeaponSpec();
            if (spec == null) continue;
            Guidance.Kind kind = Guidance.of(spec);
            if (kind == Guidance.Kind.RADAR) {
                // Shut out vanilla flare decoying via the ECCM chance
                if (TcoSettings.flaresEnabled && !missile.hasTag(ECCM_TAG)) {
                    missile.addTag(ECCM_TAG);
                    missile.setEccmChanceOverride(1f);
                }
                continue;
            }
            if (kind != Guidance.Kind.IR && kind != Guidance.Kind.IR_ENGINE) continue;
            Tracked t = tracked.get(missile);
            if (t == null) {
                t = new Tracked();
                t.nextCheck = now + 0.2f;
                t.nextScan = now;
                t.ignoresFlares = spec.getAIHints() != null && spec.getAIHints().contains(AIHints.IGNORES_FLARES);
                t.eccm = missile.getECCMChance();
                t.irccm = hasIrccm(missile.getSource());
                // Vanilla flares follow TCO's rules
                if (TcoSettings.flaresEnabled) missile.setEccmChanceOverride(1f);
                tracked.put(missile, t);
            }
            ShipAPI target = targetOf(missile, t);
            if (target != null && target.getOwner() != missile.getOwner()
                    && distance(missile.getLocation(), target.getLocation()) < TcoSettings.flareThreatRange) {
                threatened.add(target);
            }
            if (t.seeker != null) {
                fly(missile, t, now);
                continue;
            }
            if (TcoSettings.irLockEnabled && !t.irccm && target != null && now >= t.nextCheck) {
                t.nextCheck = now + CHECK_INTERVAL;
                float h = heat(target, missile.getLocation());
                float lose = Math.max(0f, TcoSettings.irLoseThreshold - h) * TcoSettings.irLoseRate;
                if (Math.random() < lose) {
                    takeOver(missile, t, null);
                    continue;
                }
            }
            if (TcoSettings.flaresEnabled && !t.ignoresFlares && now >= t.nextScan) {
                t.nextScan = now + SCAN_INTERVAL;
                Flare decoy = tryDecoy(missile, t, target);
                if (decoy != null) takeOver(missile, t, decoy);
            }
        }
        if (TcoSettings.flaresEnabled) autoDispense(threatened, now);
    }

    private static ShipAPI targetOf(MissileAPI missile, Tracked t) {
        if (t.seeker != null) return t.seeker.ship;
        MissileAIPlugin ai = missile.getUnwrappedMissileAI();
        if (!(ai instanceof GuidedMissileAI)) ai = missile.getMissileAI();
        if (ai instanceof GuidedMissileAI) {
            CombatEntityAPI target = ((GuidedMissileAI) ai).getTarget();
            if (target instanceof ShipAPI) return (ShipAPI) target;
        }
        return null;
    }

    private Flare tryDecoy(MissileAPI missile, Tracked t, ShipAPI target) {
        float targetHeat = target == null ? 0.5f : heat(target, missile.getLocation());
        int blanket = -1;
        for (Flare f : allFlares) {
            if (!f.burning() || f.owner == missile.getOwner() || t.rolled.contains(f)) continue;
            if (distance(missile.getLocation(), f.loc) > TcoSettings.flareRange) continue;
            if (Math.abs(angleDiff(angleTo(missile.getLocation(), f.loc), missile.getFacing())) > FLARE_CONE) continue;
            t.rolled.add(f);
            if (t.irccm && blanket < 0) blanket = flaresAround(missile);
            float chance = decoyChance(targetHeat, t.irccm, blanket);
            // Older flares lure less
            chance *= 0.5f + 0.5f * f.heat();
            chance *= 1f - Math.max(0f, Math.min(1f, t.eccm));
            if (Math.random() < chance) return f;
        }
        return null;
    }

    /**
     * Chance one flare decoys a missile, before ECCM and flare age. IRCCM makes hot targets near impossible,
     * offset by a bonus for dense enemy flares.
     */
    public static float decoyChance(float targetHeat, boolean irccm, int flaresAround) {
        float h = Math.max(0f, Math.min(1f, targetHeat));
        if (!irccm) return TcoSettings.flareChanceCold + (TcoSettings.flareChanceHot - TcoSettings.flareChanceCold) * h;
        float chance = TcoSettings.irccmChanceCold * (1f - h) * (1f - h);
        chance += Math.max(0, flaresAround - TcoSettings.irccmBlanketStart) * TcoSettings.irccmBlanketPerFlare;
        return Math.min(TcoSettings.irccmChanceMax, chance);
    }

    private int flaresAround(MissileAPI missile) {
        int n = 0;
        for (Flare f : allFlares) {
            if (f.burning() && f.owner != missile.getOwner()
                    && distance(missile.getLocation(), f.loc) <= TcoSettings.irccmBlanketRadius) {
                n++;
            }
        }
        return n;
    }

    /** The ship (or a fighter's carrier) has IRCCM. */
    public static boolean hasIrccm(ShipAPI ship) {
        if (ship == null) return false;
        if (ship.isFighter() && ship.getWing() != null && ship.getWing().getSourceShip() != null) {
            ship = ship.getWing().getSourceShip();
        }
        return ship.getVariant() != null && ship.getVariant().hasHullMod(IRCCM_ID);
    }

    private void takeOver(MissileAPI missile, Tracked t, Flare decoy) {
        if (t.seeker == null) {
            MissileAIPlugin own = missile.getUnwrappedMissileAI();
            t.original = own != null ? own : missile.getMissileAI();
            t.seeker = new Seeker(missile);
            missile.setMissileAI(t.seeker);
        }
        t.seeker.ship = null;
        t.seeker.flare = decoy;
        t.nextScan = engine.getTotalElapsedTime(false) + (decoy != null ? 0f : 0.4f);
    }

    private void fly(MissileAPI missile, Tracked t, float now) {
        Seeker s = t.seeker;
        if (s.flare != null) {
            // Flare burnt out
            if (!s.flare.burning()) s.flare = null;
            return;
        }
        if (now < t.nextScan) return;
        t.nextScan = now + SCAN_INTERVAL;
        // Search the cone ahead
        ShipAPI bestShip = null;
        Flare bestFlare = null;
        float best = 0f;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() == missile.getOwner() || ship.getOwner() == 100 || !ship.isAlive() || ship.isHulk()) continue;
            if (ship.isPhased() || ship.isShuttlePod()) continue;
            float d = distance(missile.getLocation(), ship.getLocation());
            if (d > SEEKER_RANGE) continue;
            if (Math.abs(angleDiff(angleTo(missile.getLocation(), ship.getLocation()), missile.getFacing())) > SEEKER_CONE) continue;
            float h = heat(ship, missile.getLocation());
            if (Math.random() >= h) continue;
            float score = h / (200f + d);
            // Prefer ships over small craft
            if (RadarCombatPlugin.isSmallCraft(ship)) score /= 3f;
            if (score > best) {
                best = score;
                bestShip = ship;
                bestFlare = null;
            }
        }
        if (TcoSettings.flaresEnabled && !t.ignoresFlares) {
            for (Flare f : allFlares) {
                if (!f.burning() || f.owner == missile.getOwner()) continue;
                float d = distance(missile.getLocation(), f.loc);
                if (d > SEEKER_RANGE) continue;
                if (Math.abs(angleDiff(angleTo(missile.getLocation(), f.loc), missile.getFacing())) > SEEKER_CONE) continue;
                float h = f.heat() * (t.irccm ? TcoSettings.irccmSeekFlareMult : 1f);
                if (Math.random() >= h) continue;
                float score = h / (200f + d);
                if (score > best) {
                    best = score;
                    bestFlare = f;
                    bestShip = null;
                }
            }
        }
        if (bestFlare != null) {
            s.flare = bestFlare;
        } else if (bestShip != null) {
            // Locked on: hand back to its own AI
            missile.setMissileAI(t.original);
            MissileAIPlugin ai = missile.getUnwrappedMissileAI();
            if (!(ai instanceof GuidedMissileAI)) ai = missile.getMissileAI();
            if (ai instanceof GuidedMissileAI) ((GuidedMissileAI) ai).setTarget(bestShip);
            t.seeker = null;
            t.original = null;
            t.nextCheck = now + CHECK_INTERVAL;
        }
    }

    private void autoDispense(Set<ShipAPI> threatened, float now) {
        ShipAPI player = engine.getPlayerShip();
        for (ShipAPI ship : threatened) {
            if (!carriesFlares(ship)) continue;
            // Player-flown ship only drops on the key
            if (AiUtil.playerFlying(engine, ship)) continue;
            if (ship.getOwner() == 0 && !TcoSettings.flaresAutoPlayer) continue;
            Float r = autoReady.get(ship);
            if (r != null && now < r) continue;
            drop(ship, true);
        }
    }

    private void collectFlares() {
        allFlares.clear();
        allFlares.addAll(flares);
        if (!TcoSettings.flaresEnabled) return;
        for (MissileAPI missile : engine.getMissiles()) {
            if (!missile.isFlare() || missile.isFading() || missile.isFizzling()) continue;
            // Flares from a reload-converted system are removed
            if (missile.getSource() != null && TcoSettings.flareSystemReloads && reloadsInstead(missile.getSource())) {
                engine.removeEntity(missile);
                continue;
            }
            Flare f = vanillaFlares.get(missile);
            if (f == null) {
                f = new Flare();
                f.entity = missile;
                vanillaFlares.put(missile, f);
            }
            f.loc.set(missile.getLocation());
            f.vel.set(missile.getVelocity());
            f.owner = missile.getOwner();
            allFlares.add(f);
        }
    }

    private void advanceFlares(float amount) {
        float drag = 1f - Math.min(1f, 0.7f * amount);
        for (Iterator<Flare> it = flares.iterator(); it.hasNext();) {
            Flare f = it.next();
            f.age += amount;
            if (!f.burning()) {
                it.remove();
                continue;
            }
            f.vel.scale(drag);
            f.loc.x += f.vel.x * amount;
            f.loc.y += f.vel.y * amount;
            if (!visible(f.loc, f.owner) || !engine.getViewport().isNearViewport(f.loc, 100f)) continue;
            float h = f.heat();
            engine.addHitParticle(f.loc, f.vel, 10f + 14f * h, 1f, 0.06f, FLARE_CORE);
            engine.addSmoothParticle(f.loc, f.vel, 30f + 40f * h, 0.5f * h, 0.08f, FLARE_GLOW);
            f.nextSmoke -= amount;
            if (f.nextSmoke <= 0f) {
                f.nextSmoke = 0.05f;
                engine.addNebulaParticle(f.loc, new Vector2f(f.vel.x * 0.2f, f.vel.y * 0.2f), 14f, 2.2f, 0.1f, 0.2f,
                        1.2f + (float) Math.random() * 0.6f, FLARE_SMOKE);
            }
        }
    }

    private static boolean visible(Vector2f loc, int owner) {
        return owner == 0 || FogOfWar.isVisible(loc);
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Flies a missile with no ship to home on. */
    public static final class Seeker implements MissileAIPlugin, GuidedMissileAI {
        final MissileAPI missile;
        ShipAPI ship;
        Flare flare;

        Seeker(MissileAPI missile) {
            this.missile = missile;
        }

        @Override
        public void advance(float amount) {
            missile.giveCommand(ShipCommand.ACCELERATE);
            if (flare == null) return;
            float off = angleDiff(angleTo(missile.getLocation(), flare.loc), missile.getFacing());
            if (off > 3f) missile.giveCommand(ShipCommand.TURN_LEFT);
            else if (off < -3f) missile.giveCommand(ShipCommand.TURN_RIGHT);
        }

        @Override
        public CombatEntityAPI getTarget() {
            return ship;
        }

        @Override
        public void setTarget(CombatEntityAPI target) {
            ship = target instanceof ShipAPI ? (ShipAPI) target : null;
        }
    }
}
