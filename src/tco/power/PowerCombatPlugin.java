package tco.power;

import java.awt.Color;
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
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;

import tco.AiUtil;
import tco.Msg;
import tco.TcoSettings;
import tco.ammo.AmmoRules;
import tco.armor.ArmorEffects;
import tco.rts.FogOfWar;

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

        if (TcoSettings.powerEnabled) countShots();
        aiTimer -= amount;
        boolean aiPass = aiTimer <= 0f;
        if (aiPass) aiTimer = AI_INTERVAL;

        Iterator<Map.Entry<ShipAPI, Reactor>> it = reactors.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ShipAPI, Reactor> entry = it.next();
            ShipAPI ship = entry.getKey();
            Reactor r = entry.getValue();
            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) {
                it.remove();
                continue;
            }

            if (!TcoSettings.powerEnabled) {
                // Meltdown kills engines and shields
                if (r.meltdown) {
                    keepEnginesDead(ship);
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

            float regen = ship.getMutableStats().getFluxDissipation().getModifiedValue() * TcoSettings.powerRegenMult;
            float ceiling = r.capacity;
            if (r.meltdown) {
                // Emergency trickle
                regen *= TcoSettings.reactorEmergencyRegenMult;
                ceiling = r.capacity * TcoSettings.reactorEmergencyCapMult;
                keepEnginesDead(ship);
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
            boolean aiFlown = isAiFlown(ship);
            holdUnaffordable(ship, r, aiFlown);
            if (aiFlown && aiPass && TcoSettings.powerAiEnabled) advise(ship, r);
        }
    }

    private boolean isAiFlown(ShipAPI ship) {
        return ship != engine.getPlayerShip() || engine.isUIAutopilotOn();
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
        if (weapon.getType() == WeaponType.ENERGY) return weapon.getFluxCostToFire() * TcoSettings.powerEnergyMult;
        if (weapon.getType() == WeaponType.BALLISTIC && weapon.getFluxCostToFire() > 0f) {
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
            if (type == WeaponType.ENERGY && keep <= 0f && !(r.meltdown && isSmallPd(weapon))) continue;
            float cost = weapon.isBeam() ? 0.01f : shotCost(weapon);
            if (r.power - cost < keep) weapon.setForceNoFireOneFrame(true);
        }
    }

    private float capacityFor(ShipAPI ship) {
        return Math.max(1f, ship.getMaxFlux() * TcoSettings.powerCapacityMult);
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

    // linked barrels and multi-projectile shots: each projectile pays its damage share of the shot
    private static float projectileCost(WeaponAPI weapon, DamagingProjectileAPI shot) {
        float full = shotCost(weapon);
        WeaponAPI.DerivedWeaponStatsAPI stats = weapon.getDerivedStats();
        float perShot = stats == null ? 0f : stats.getDamagePerShot();
        float damage = shot.getBaseDamageAmount();
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
            if (weapon.getType() != WeaponType.ENERGY) continue;
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
        if (!TcoSettings.reactorMeltdownEnabled) return;
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
        if (ship.getEngineController() != null) ship.getEngineController().forceFlameout(true);
        engine.spawnExplosion(core, ship.getVelocity(), new Color(120, 200, 255), 90f, 0.8f);
        engine.addHitParticle(core, ship.getVelocity(), 220f, 1f, 0.6f, new Color(160, 220, 255));
        engine.addFloatingText(core, "REACTOR MELTDOWN", 30f, new Color(120, 200, 255), ship, 0f, 0f);
        Global.getSoundPlayer().playSound("explosion_from_damage", 0.6f, 1.2f, core, ship.getVelocity());
        Msg.about(engine, ship, ship.getOwner() == 0 ? ship.getName() + " has lost its reactor. A fleet tender is needed to restart it."
                : ship.getName() + "'s reactor is down.");
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
            float amount = damage.getDamage();
            if (damage.isDps()) amount *= engine.getElapsedInLastFrame();
            float flux = amount * damage.getType().getShieldMult() * shield.getFluxPerPointOfDamage();
            r.power = Math.max(0f, r.power - flux * TcoSettings.powerShieldMult);
            if (!loggedShieldHit) {
                loggedShieldHit = true;
                Global.getLogger(PowerCombatPlugin.class).info("TCO power: shield hit on " + ship.getName()
                        + " drew " + Math.round(flux * TcoSettings.powerShieldMult) + " power");
            }
            return null;
        }
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        if (engine == null || !TcoSettings.powerEnabled || !TcoSettings.powerHud) return;
        ShipAPI ship = engine.getPlayerShip();
        Reactor r = ship == null ? null : reactors.get(ship);
        if (r == null || engine.getCombatUI() == null || engine.getCombatUI().isShowingCommandUI()) return;

        // Beside the game's hull and flux bars
        float x = TcoSettings.powerHudX;
        float y = TcoSettings.powerHudY;
        float width = 14f;
        float height = TcoSettings.powerHudHeight;
        float level = r.capacity > 0f ? Math.max(0f, Math.min(1f, r.power / r.capacity)) : 0f;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0.03f, 0.05f, 0.07f, 0.75f);
        quad(x - 2f, y - 2f, width + 4f, height + 4f);
        GL11.glColor4f(0.2f, 0.2f, 0.2f, 0.9f);
        quad(x, y, width, height);
        float[] c = barColor(r);
        GL11.glColor4f(c[0], c[1], c[2], 1f);
        quad(x, y, width, height * level);
        GL11.glPopAttrib();

        String text = "Power " + Math.round(r.power) + " / " + Math.round(r.capacity)
                + (r.meltdown ? "  REACTOR DOWN" : r.dry ? "  DEPLETED" : "");
        try {
            if (hudLabel == null) {
                hudLabel = Global.getSettings().createLabel(text, Fonts.DEFAULT_SMALL);
                hudText = text;
            } else if (!text.equals(hudText)) {
                hudLabel.setText(text);
                hudText = text;
            }
            hudLabel.setColor(r.meltdown || r.dry ? new Color(255, 150, 90) : new Color(210, 215, 220));
            hudLabel.getPosition().setSize(220f, 16f);
            hudLabel.getPosition().setLocation(x + width + 6f, y);
            hudLabel.render(1f);
        } catch (RuntimeException e) {
            hudLabel = null;
        }
    }

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (engine == null || !TcoSettings.powerEnabled || !TcoSettings.powerReticle || reactors.isEmpty()) return;
        if (engine.getCombatUI() != null && engine.getCombatUI().isShowingCommandUI()) return;
        float px = viewport.getViewMult();
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

            GL11.glColor4f(0f, 0f, 0f, 0.6f * alpha);
            quad(x - px, y - px, width + 2f * px, height + 2f * px);
            GL11.glColor4f(0.25f, 0.25f, 0.25f, alpha);
            quad(x, y, width, height);
            float[] c = barColor(r);
            GL11.glColor4f(c[0], c[1], c[2], alpha);
            quad(x, y, width * level, height);
        }
        GL11.glPopAttrib();
    }

    public float[] barColor(ShipAPI ship) {
        Reactor r = reactors.get(ship);
        return r == null ? new float[] {0.5f, 0.5f, 0.5f} : barColor(r);
    }

    private static float[] barColor(Reactor r) {
        if (r.meltdown) return new float[] {0.9f, 0.3f, 0.3f};
        if (r.dry) return new float[] {1f, 0.65f, 0.25f};
        return new float[] {1f, 0.85f, 0.3f};
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
