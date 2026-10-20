package tco.rts;

import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipSystemAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.WeaponGroupAPI;

import tco.TcoSettings;
import tco.ammo.AmmoRules;
import tco.power.PowerCombatPlugin;

/**
 * RTS-mode ship AI: flies the current order and otherwise holds, defending itself only with shields and
 * autofire. Attack orders hand the ship to the default AI.
 */
public class PassiveShipAI implements ShipAIPlugin {

    private static final float SLOW_INTERVAL = 0.25f;
    private static final float SHIP_THREAT_RANGE = 1600f;
    private static final float PROJECTILE_THREAT_RANGE = 700f;
    private static final float SHIELD_HOLD_TIME = 2f;
    private static final float SHIELD_MAX_FLUX = 0.9f;
    private static final float ESCORT_GAP = 300f;
    private static final float VENT_SAFE_RANGE = 2500f;
    private static final float VENT_MIN_FLUX = 0.4f;
    private static final float VENT_QUIET_TIME = 3f;
    private static final float WAYPOINT_PASS_RADIUS = 250f;

    private final ShipAPI ship;
    private final UnitState state;
    private final ShipwideAIFlags flags = new ShipwideAIFlags();
    private final ShipAIConfig config = new ShipAIConfig();

    // counts engine calls, to notice when the engine stops ticking us
    public int engineTicks = 0;

    private float slowTimer = 0f;
    private float threatTimer = 0f;
    private float shieldToggleCooldown = 0f;
    private final Vector2f threatLoc = new Vector2f();
    private float nearestEnemy = Float.MAX_VALUE;
    private float quietTime = 0f;

    public PassiveShipAI(ShipAPI ship, UnitState state) {
        this.ship = ship;
        this.state = state;
    }

    @Override
    public void advance(float amount) {
        engineTicks++;
        tick(amount);
    }

    public void tick(float amount) {
        if (amount <= 0f || !ship.isAlive()) return;

        slowTimer -= amount;
        if (slowTimer <= 0f) {
            slowTimer = SLOW_INTERVAL;
            syncAutofire();
            scanThreats();
            useDefensiveSystem();
        }

        updateShield(amount);
        updateVent(amount);
        // Collision avoidance first
        if (!avoidCollisions()) followOrder();
    }

    private static final float AVOID_LOOKAHEAD = 3f;
    private static final float AVOID_MARGIN = 40f;

    /**
     * Side-steps any ship on a collision course within a few seconds (the game's AI won't steer around
     * ships flown by other AIs). True while dodging.
     */
    private boolean avoidCollisions() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || ship.getCollisionClass() == CollisionClass.NONE) return false;
        Vector2f p = ship.getLocation();
        Vector2f v = ship.getVelocity();
        float myRadius = ship.getCollisionRadius() * 0.75f;

        float bestT = Float.MAX_VALUE;
        float awayX = 0f;
        float awayY = 0f;
        boolean headingAtIt = false;
        for (ShipAPI other : engine.getShips()) {
            if (other == ship || other.isFighter() || other.isDrone() || other.isShuttlePod()) continue;
            if (other.getCollisionClass() == CollisionClass.NONE) continue;
            if (AmmoRules.isShuttle(other) || other.getParentStation() == ship || ship.getParentStation() == other) continue;
            float minDist = myRadius + other.getCollisionRadius() * 0.75f + AVOID_MARGIN;
            float dx = other.getLocation().x - p.x;
            float dy = other.getLocation().y - p.y;
            float wx = other.getVelocity().x - v.x;
            float wy = other.getVelocity().y - v.y;
            float w2 = wx * wx + wy * wy;
            float reach = minDist + (float) Math.sqrt(w2) * AVOID_LOOKAHEAD;
            if (dx * dx + dy * dy > reach * reach) continue;
            // Closest approach
            float t = w2 < 1f ? 0f : -(dx * wx + dy * wy) / w2;
            t = Math.max(0f, Math.min(AVOID_LOOKAHEAD, t));
            float cx = dx + wx * t;
            float cy = dy + wy * t;
            float miss = (float) Math.sqrt(cx * cx + cy * cy);
            if (miss >= minDist || t >= bestT) continue;
            bestT = t;
            if (miss > 1f) {
                awayX = -cx / miss;
                awayY = -cy / miss;
            } else {
                // Head-on: step aside
                float len = (float) Math.sqrt(wx * wx + wy * wy);
                if (len > 1f) {
                    awayX = -wy / len;
                    awayY = wx / len;
                } else {
                    float d = (float) Math.sqrt(dx * dx + dy * dy);
                    awayX = d > 1f ? -dx / d : 1f;
                    awayY = d > 1f ? -dy / d : 0f;
                }
            }
            headingAtIt = v.x * dx + v.y * dy > 0f;
        }
        if (bestT == Float.MAX_VALUE) return false;

        // Away, in the ship's frame
        double f = Math.toRadians(ship.getFacing());
        float fx = (float) Math.cos(f);
        float fy = (float) Math.sin(f);
        float forward = awayX * fx + awayY * fy;
        float left = -awayX * fy + awayY * fx;
        if (left > 0.2f) ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
        else if (left < -0.2f) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
        if (forward > 0.3f) ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        else if (forward < -0.3f) ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
        else if (headingAtIt) ship.giveCommand(ShipCommand.DECELERATE, null, 0);
        return true;
    }

    private void syncAutofire() {
        List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
        if (state.savedAutofire == null) {
            state.savedAutofire = new boolean[groups.size()];
            for (int i = 0; i < groups.size(); i++) {
                state.savedAutofire[i] = groups.get(i).isAutofiring();
            }
        }
        for (WeaponGroupAPI group : groups) {
            if (state.holdFire) {
                if (group.isAutofiring()) group.toggleOff();
            } else if (!group.isAutofiring()) {
                group.toggleOn();
            }
        }
    }

    private void scanThreats() {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return;
        int owner = ship.getOwner();
        Vector2f loc = ship.getLocation();
        float best = Float.MAX_VALUE;
        Vector2f bestLoc = null;
        nearestEnemy = Float.MAX_VALUE;

        for (ShipAPI other : engine.getShips()) {
            if (other.getOwner() == owner || other.getOwner() == 100) continue;
            if (other.isHulk() || !other.isAlive() || AmmoRules.isShuttle(other)) continue;
            float dist = RtsMath.distance(loc, other.getLocation())
                    - ship.getCollisionRadius() - other.getCollisionRadius();
            if (!other.isFighter() && !other.isDrone()) nearestEnemy = Math.min(nearestEnemy, dist);
            if (dist < SHIP_THREAT_RANGE * TcoSettings.weaponRangeMult && dist < best) {
                best = dist;
                bestLoc = other.getLocation();
            }
        }
        if (bestLoc == null) {
            for (DamagingProjectileAPI proj : engine.getProjectiles()) {
                if (proj.getOwner() == owner || proj.getOwner() == 100) continue;
                float dist = RtsMath.distance(loc, proj.getLocation()) - ship.getCollisionRadius();
                if (dist < PROJECTILE_THREAT_RANGE && dist < best) {
                    best = dist;
                    bestLoc = proj.getLocation();
                }
            }
        }
        if (bestLoc != null) {
            threatTimer = SHIELD_HOLD_TIME;
            threatLoc.set(bestLoc);
        }
    }

    private void updateShield(float amount) {
        threatTimer -= amount;
        shieldToggleCooldown -= amount;

        if (updatePhase(amount)) return;
        ShieldAPI shield = ship.getShield();
        if (shield == null) return;
        ShieldType type = shield.getType();
        if (type == ShieldType.NONE || type == ShieldType.PHASE) return;

        FluxTrackerAPI flux = ship.getFluxTracker();
        if (flux.isOverloadedOrVenting()) return;

        // Respect the power plugin's shield block
        PowerCombatPlugin power = PowerCombatPlugin.get(Global.getCombatEngine());
        boolean powered = power == null || !power.isOutOfPower(ship);
        boolean want = threatTimer > 0f && flux.getFluxLevel() < SHIELD_MAX_FLUX && powered;
        if (want != shield.isOn() && shieldToggleCooldown <= 0f) {
            if (want) shield.toggleOn();
            else shield.toggleOff();
            shieldToggleCooldown = 0.5f;
        }
        if (want && type == ShieldType.OMNI) {
            ship.setShieldTargetOverride(threatLoc.x, threatLoc.y);
        }
    }

    private float lastHull = -1f;
    private float recentHullLoss = 0f;

    /**
     * Defensive ship systems from tco_settings.json lists: damage reduction under fire, shield toggles,
     * flares against missiles, flak against missiles or fighters.
     */
    private void useDefensiveSystem() {
        float hull = ship.getHullLevel();
        if (lastHull >= 0f) recentHullLoss = recentHullLoss * 0.6f + Math.max(0f, lastHull - hull);
        lastHull = hull;

        ShipSystemAPI system = ship.getSystem();
        if (system == null || system.getId() == null) return;
        String id = system.getId();
        FluxTrackerAPI flux = ship.getFluxTracker();
        if (flux.isOverloadedOrVenting()) return;
        float hard = flux.getMaxFlux() > 0f ? flux.getHardFlux() / flux.getMaxFlux() : 0f;
        boolean underFire = threatTimer > 0f;

        if (TcoSettings.passiveSystemsShieldToggle.contains(id)) {
            ShieldAPI shield = ship.getShield();
            boolean want = underFire && shield != null && shield.isOn() && hard > 0.5f;
            boolean calm = !underFire || hard < 0.25f || shield == null || shield.isOff();
            if (want && !system.isOn() && system.canBeActivated()) ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0);
            else if (calm && system.isOn()) ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0);
            return;
        }
        if (system.isActive() || !system.canBeActivated()) return;
        boolean use = false;
        if (TcoSettings.passiveSystemsDamage.contains(id)) {
            use = underFire && (recentHullLoss > 0.004f || hard > 0.6f);
        } else if (TcoSettings.passiveSystemsFlares.contains(id)) {
            use = missilesInbound(1200f);
        } else if (TcoSettings.passiveSystemsFlak.contains(id)) {
            use = missilesInbound(700f) || fightersNear(700f);
        }
        if (use) ship.giveCommand(ShipCommand.USE_SYSTEM, null, 0);
    }

    private boolean missilesInbound(float range) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return false;
        Vector2f loc = ship.getLocation();
        for (MissileAPI missile : engine.getMissiles()) {
            if (missile.getOwner() == ship.getOwner() || missile.isFizzling()) continue;
            float dx = loc.x - missile.getLocation().x;
            float dy = loc.y - missile.getLocation().y;
            if (dx * dx + dy * dy > range * range) continue;
            if (dx * missile.getVelocity().x + dy * missile.getVelocity().y > 0f) return true;
        }
        return false;
    }

    private boolean fightersNear(float range) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return false;
        for (ShipAPI other : engine.getShips()) {
            if (!other.isFighter() || other.getOwner() == ship.getOwner() || !other.isAlive()) continue;
            if (RtsMath.distance(ship.getLocation(), other.getLocation()) < range + ship.getCollisionRadius()) return true;
        }
        return false;
    }

    private float phaseCalm = 0f;

    private boolean updatePhase(float amount) {
        ShipSystemAPI cloak = ship.getPhaseCloak();
        if (cloak == null) return false;
        FluxTrackerAPI flux = ship.getFluxTracker();
        if (flux.isOverloadedOrVenting()) return true;
        boolean phased = cloak.isOn() || cloak.isActive();
        boolean danger = threatTimer > 0f
                && (missilesInbound(700f) || shotsInbound(450f) || recentHullLoss > 0.003f);
        if (danger) phaseCalm = 0f;
        else phaseCalm += amount;
        boolean want = danger && flux.getFluxLevel() < 0.75f;
        boolean drop = phased && (flux.getFluxLevel() > 0.85f || phaseCalm > 1.5f);
        if (shieldToggleCooldown > 0f) return true;
        if ((want && !phased && cloak.getCooldownRemaining() <= 0f) || drop) {
            ship.giveCommand(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK, null, 0);
            shieldToggleCooldown = 0.5f;
        }
        return true;
    }

    private boolean shotsInbound(float range) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null) return false;
        Vector2f loc = ship.getLocation();
        for (DamagingProjectileAPI shot : engine.getProjectiles()) {
            if (shot.getOwner() == ship.getOwner() || shot.getOwner() == 100) continue;
            float dx = loc.x - shot.getLocation().x;
            float dy = loc.y - shot.getLocation().y;
            if (dx * dx + dy * dy > range * range) continue;
            if (dx * shot.getVelocity().x + dy * shot.getVelocity().y > 0f) return true;
        }
        return false;
    }

    private void updateVent(float amount) {
        if (threatTimer > 0f || nearestEnemy < VENT_SAFE_RANGE * TcoSettings.weaponRangeMult) {
            quietTime = 0f;
            return;
        }
        quietTime += amount;
        if (quietTime < VENT_QUIET_TIME) return;

        FluxTrackerAPI flux = ship.getFluxTracker();
        if (flux.isOverloadedOrVenting() || flux.getFluxLevel() < VENT_MIN_FLUX) return;
        ship.giveCommand(ShipCommand.VENT_FLUX, null, 0);
    }

    private void followOrder() {
        Vector2f loc = ship.getLocation();
        Vector2f vel = ship.getVelocity();
        float speed = vel.length();
        Vector2f dest = null;
        float arriveRadius = 60f;

        switch (state.order) {
            case FACE:
                if (speed > 2f) ship.giveCommand(ShipCommand.DECELERATE, null, 0);
                if (state.facing == null || isFacing(state.facing, 3f)) state.completeOrder();
                else turnToward(state.facing);
                return;
            case MOVE:
            case ATTACK_MOVE:
                dest = state.point;
                break;
            case FORMATION: {
                Vector2f slot = state.formation == null ? null : state.formation.slotPoint(state);
                if (slot == null) {
                    state.completeOrder();
                    break;
                }
                // Near the slot: match the leader's heading and velocity
                ShipAPI leader = state.formation.leader.ship;
                if (RtsMath.distance(loc, slot) <= Math.max(TcoSettings.formationSlideMin,
                        ship.getCollisionRadius() * TcoSettings.formationSlideRadii)) {
                    driveTo(slot, leader.getFacing(), leader.getVelocity());
                    return;
                }
                dest = slot;
                arriveRadius = 40f;
                break;
            }
            case CAPTURE:
                if (state.objective == null
                        || (!state.queue.isEmpty() && state.objective.getOwner() == ship.getOwner())) {
                    state.completeOrder();
                } else {
                    dest = state.objective.getLocation();
                    arriveRadius = 100f;
                }
                break;
            case BOARD: {
                // Close in; the RTS plugin launches shuttles in range
                ShipAPI enemy = state.target;
                if (enemy == null || !enemy.isAlive() || enemy.isHulk()) {
                    state.completeOrder();
                    break;
                }
                float gap = RtsMath.distance(loc, enemy.getLocation())
                        - enemy.getCollisionRadius() - ship.getCollisionRadius();
                if (gap > TcoSettings.boardingLaunchRange * 0.8f) dest = enemy.getLocation();
                break;
            }
            case TOW: {
                // Close in; the tow plugin hooks on
                ShipAPI stranded = state.target;
                if (stranded == null || !stranded.isAlive() || stranded.isHulk()) {
                    state.completeOrder();
                    break;
                }
                float gap = RtsMath.distance(loc, stranded.getLocation())
                        - stranded.getCollisionRadius() - ship.getCollisionRadius();
                if (gap > 60f) dest = stranded.getLocation();
                arriveRadius = stranded.getCollisionRadius() + ship.getCollisionRadius() + 60f;
                break;
            }
            case ESCORT: {
                ShipAPI friend = state.target;
                if (friend == null || !friend.isAlive() || friend.isHulk()) {
                    state.completeOrder();
                    break;
                }
                float gap = RtsMath.distance(loc, friend.getLocation())
                        - friend.getCollisionRadius() - ship.getCollisionRadius();
                if (gap > ESCORT_GAP) dest = friend.getLocation();
                break;
            }
            default:
                break;
        }

        if (dest == null) {
            if (speed > 2f) ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            return;
        }

        boolean isMove = state.order == UnitState.OrderType.MOVE || state.order == UnitState.OrderType.ATTACK_MOVE;
        Float facing = isMove ? state.facing : null;
        boolean strafe = facing != null && state.strafe;

        float dist = RtsMath.distance(loc, dest);
        float travelAngle = RtsMath.angle(loc, dest);

        // Pass through queued waypoints
        boolean passThrough = isMove && facing == null && !state.queue.isEmpty();
        if (passThrough && dist <= WAYPOINT_PASS_RADIUS) {
            state.completeOrder();
            return;
        }

        if (dist <= arriveRadius) {
            if (speed > 2f) ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            if (facing != null) {
                turnToward(facing);
                if (speed <= 2f && isFacing(facing, 3f)) state.completeOrder();
            } else if (speed <= 2f && isMove) {
                state.completeOrder();
            }
            return;
        }

        // Short hops and Shift + drag slide on thrusters instead of turning and burning
        float shortHop = Math.max(TcoSettings.slideMin, ship.getCollisionRadius() * TcoSettings.slideRadii);
        if (strafe || (!passThrough && dist < shortHop)) {
            float hold = facing != null ? facing : hopFacing(dest);
            driveTo(dest, hold, ZERO);
            return;
        }

        float decel = Math.max(ship.getDeceleration(), 1f);
        float brakeDist = speed * speed / (2f * decel) + arriveRadius * 0.5f;

        double travelRad = Math.toRadians(travelAngle);
        float dirX = (float) Math.cos(travelRad);
        float dirY = (float) Math.sin(travelRad);
        float closing = vel.x * dirX + vel.y * dirY;
        float drift = -vel.x * dirY + vel.y * dirX;

        turnToward(travelAngle);

        if (!passThrough && closing > 0f && dist <= brakeDist) {
            ship.giveCommand(ShipCommand.DECELERATE, null, 0);
            return;
        }

        float off = RtsMath.shortestRotation(ship.getFacing(), travelAngle);
        float absOff = Math.abs(off);
        if (absOff < 35f) {
            ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
            if (drift > 15f) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
            else if (drift < -15f) ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
        } else if (absOff > 145f && dist < 500f) {
            ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
        } else if (closing < speed * 0.5f && speed > 20f) {
            ship.giveCommand(ShipCommand.DECELERATE, null, 0);
        }
    }

    private static final Vector2f ZERO = new Vector2f();
    private final Vector2f hopDest = new Vector2f(Float.NaN, Float.NaN);
    private float hopHeading;

    private float hopFacing(Vector2f dest) {
        if (Float.isNaN(hopDest.x) || RtsMath.distance(hopDest, dest) > 50f) {
            hopDest.set(dest);
            hopHeading = ship.getFacing();
        }
        return hopHeading;
    }

    /**
     * Drives to a point (relative to a moving frame) while holding a heading, using whichever thrusters close
     * the velocity gap.
     */
    private void driveTo(Vector2f dest, float heading, Vector2f frame) {
        turnToward(heading);
        Vector2f loc = ship.getLocation();
        Vector2f vel = ship.getVelocity();
        float dx = dest.x - loc.x;
        float dy = dest.y - loc.y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        float top = ship.getMutableStats().getMaxSpeed().getModifiedValue();
        // Brake early: lateral and reverse thrust are weaker
        float brake = Math.max(10f, Math.min(ship.getDeceleration(), ship.getAcceleration()) * 0.5f);
        float want = dist <= 5f ? 0f : Math.min(top, (float) Math.sqrt(2f * brake * dist));
        float wx = frame.x + (dist > 0.1f ? dx / dist * want : 0f);
        float wy = frame.y + (dist > 0.1f ? dy / dist * want : 0f);
        thrust(wx - vel.x, wy - vel.y);
    }

    private void thrust(float dvx, float dvy) {
        double f = Math.toRadians(ship.getFacing());
        float fx = (float) Math.cos(f);
        float fy = (float) Math.sin(f);
        float ahead = dvx * fx + dvy * fy;
        float left = -dvx * fy + dvy * fx;
        float dead = 3f;
        if (ahead > dead) ship.giveCommand(ShipCommand.ACCELERATE, null, 0);
        else if (ahead < -dead) ship.giveCommand(ShipCommand.ACCELERATE_BACKWARDS, null, 0);
        if (left > dead) ship.giveCommand(ShipCommand.STRAFE_LEFT, null, 0);
        else if (left < -dead) ship.giveCommand(ShipCommand.STRAFE_RIGHT, null, 0);
    }

    private boolean isFacing(float angle, float tolerance) {
        return Math.abs(RtsMath.shortestRotation(ship.getFacing(), angle)) < tolerance
                && Math.abs(ship.getAngularVelocity()) < 6f;
    }

    private void turnToward(float angle) {
        float diff = RtsMath.shortestRotation(ship.getFacing(), angle);
        float angVel = ship.getAngularVelocity();
        if (Math.abs(diff) < 1f && Math.abs(angVel) < 2f) return;

        float turnAcc = Math.max(ship.getTurnAcceleration(), 1f);
        float stopAngle = angVel * Math.abs(angVel) / (2f * turnAcc);
        float remaining = diff - stopAngle;
        if (remaining > 0.5f) ship.giveCommand(ShipCommand.TURN_LEFT, null, 0);
        else if (remaining < -0.5f) ship.giveCommand(ShipCommand.TURN_RIGHT, null, 0);
    }

    /** Restores the weapon groups' original autofire. */
    public void restoreAutofire() {
        if (state.savedAutofire == null) return;
        List<WeaponGroupAPI> groups = ship.getWeaponGroupsCopy();
        for (int i = 0; i < groups.size() && i < state.savedAutofire.length; i++) {
            WeaponGroupAPI group = groups.get(i);
            if (group.isAutofiring() != state.savedAutofire[i]) {
                if (state.savedAutofire[i]) group.toggleOn();
                else group.toggleOff();
            }
        }
        state.savedAutofire = null;
    }

    @Override
    public void setDoNotFireDelay(float amount) {
    }

    @Override
    public void forceCircumstanceEvaluation() {
    }

    @Override
    public boolean needsRefit() {
        return false;
    }

    @Override
    public ShipwideAIFlags getAIFlags() {
        return flags;
    }

    @Override
    public void cancelCurrentManeuver() {
    }

    @Override
    public ShipAIConfig getConfig() {
        return config;
    }
}
