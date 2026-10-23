package tco.hangar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.ammo.AmmoRules;
import tco.ammo.ShuttleAI;
import tco.repair.RepairCombatPlugin;

/**
 * Hangar hold: fighters, supply shuttles and repair drones stay aboard.
 * Fighters aren't landed for real (the bay would relaunch them): they play the landing animation and
 * are held faded out on the carrier, still in their wing, until released.
 */
public class HangarCombatPlugin extends BaseEveryFrameCombatPlugin {

    public static final String HOLD_KEY = "tco_hangar_hold";
    private static final float LANDING_TIMEOUT = 6f;
    private static final float REARM_SECONDS = 10f;
    private static final float LANDING_REACH = 150f;
    private static final float LATE_SECONDS = 10f;
    private static final float LATE_REACH = 2000f;

    private enum Stage { INBOUND, LANDING, DOCKED }

    private static class Craft {
        ShipAPI carrier;
        FighterLaunchBayAPI bay;
        ShuttleAI ai;
        CollisionClass collision;
        Stage stage = Stage.INBOUND;
        float time = 0f;
    }

    private CombatEngineAPI engine;
    private final Map<ShipAPI, Craft> crafts = new HashMap<ShipAPI, Craft>();

    public static boolean isHeld(ShipAPI ship) {
        return ship != null && ship.getCustomData().containsKey(HOLD_KEY);
    }

    public static boolean hasCraft(ShipAPI ship) {
        if (ship == null || ship.isFighter()) return false;
        if (ship.hasLaunchBays() && !ship.getLaunchBaysCopy().isEmpty()) return true;
        if (ship.getCustomData().containsKey(AmmoRules.SUPPLY_MAX_KEY)) return true;
        return RepairCombatPlugin.hasBay(ship);
    }

    public static void setHeld(ShipAPI ship, boolean hold) {
        if (hold) ship.setCustomData(HOLD_KEY, Boolean.TRUE);
        else ship.removeCustomData(HOLD_KEY);
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine != null && engine.isPaused()) amount = 0f;
        if (engine == null) return;

        // Pull in fighters of held carriers
        for (ShipAPI carrier : engine.getShips()) {
            if (!isHeld(carrier) || !carrier.isAlive() || carrier.isHulk() || carrier.isFighter()) continue;
            for (FighterWingAPI wing : carrier.getAllWings()) {
                if (wing == null || wing.getSourceShip() != carrier) continue;
                for (ShipAPI fighter : new ArrayList<ShipAPI>(wing.getWingMembers())) {
                    if (crafts.containsKey(fighter) || !fighter.isAlive() || fighter.isHulk()) continue;
                    // Already landing on the game's orders
                    if (fighter.isLanding()) continue;
                    if (wing.isReturning(fighter)) wing.stopReturning(fighter);
                    takeIn(fighter, carrier, wing);
                }
            }
        }

        if (amount <= 0f || crafts.isEmpty()) return;
        Iterator<Map.Entry<ShipAPI, Craft>> it = crafts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ShipAPI, Craft> entry = it.next();
            ShipAPI fighter = entry.getKey();
            Craft craft = entry.getValue();
            ShipAPI carrier = craft.carrier;
            if (!fighter.isAlive() || fighter.isHulk() || !engine.isEntityInPlay(fighter)) {
                it.remove();
                continue;
            }
            boolean carrierOk = carrier.isAlive() && !carrier.isHulk() && engine.isEntityInPlay(carrier);
            if (!carrierOk || !isHeld(carrier)) {
                letOut(fighter, craft);
                it.remove();
                continue;
            }
            craft.time += amount;
            fighter.setHoldFireOneFrame(true);

            switch (craft.stage) {
                case INBOUND: {
                    Vector2f deck = deck(fighter, craft);
                    craft.ai.flyTo(deck);
                    float dx = fighter.getLocation().x - deck.x;
                    float dy = fighter.getLocation().y - deck.y;
                    // Anywhere over or near the hull: landing slides it onto the deck (follow). A small target on a
                    // moving carrier made fast fighters overshoot and circle it for good
                    float reach = carrier.getCollisionRadius() + LANDING_REACH;
                    // Still out there after a while: bring it in from further off
                    if (craft.time > LATE_SECONDS) reach = Math.max(reach, LATE_REACH);
                    if (dx * dx + dy * dy < reach * reach) {
                        craft.stage = Stage.LANDING;
                        craft.time = 0f;
                        craft.ai.hold();
                        fighter.beginLandingAnimation(carrier);
                    }
                    break;
                }
                case LANDING: {
                    follow(fighter, carrier, deck(fighter, craft), amount);
                    if (fighter.isFinishedLanding() || craft.time > LANDING_TIMEOUT) {
                        craft.stage = Stage.DOCKED;
                        craft.time = 0f;
                        fighter.setCollisionClass(CollisionClass.NONE);
                    }
                    break;
                }
                default:
                    follow(fighter, carrier, deck(fighter, craft), amount);
                    break;
            }
        }
    }

    private void takeIn(ShipAPI fighter, ShipAPI carrier, FighterWingAPI wing) {
        Craft craft = new Craft();
        craft.carrier = carrier;
        craft.bay = wing.getSource();
        if (craft.bay == null && !carrier.getLaunchBaysCopy().isEmpty()) craft.bay = carrier.getLaunchBaysCopy().get(0);
        craft.collision = fighter.getCollisionClass();
        craft.ai = new ShuttleAI(fighter);
        craft.ai.flyTo(deck(fighter, craft));
        fighter.setShipAI(craft.ai);
        crafts.put(fighter, craft);
    }

    private void letOut(ShipAPI fighter, Craft craft) {
        if (craft.stage == Stage.DOCKED && craft.time >= REARM_SECONDS) {
            fighter.setHitpoints(fighter.getMaxHitpoints());
            for (WeaponAPI weapon : fighter.getAllWeapons()) {
                if (weapon.usesAmmo() && weapon.getMaxAmmo() > 0) weapon.setAmmo(weapon.getMaxAmmo());
            }
        }
        if (fighter.isLanding()) fighter.abortLanding();
        if (craft.collision != null) fighter.setCollisionClass(craft.collision);
        fighter.resetDefaultAI();
    }

    private Vector2f deck(ShipAPI fighter, Craft craft) {
        Vector2f at = null;
        if (craft.bay != null) {
            try {
                at = craft.bay.getLandingLocation(fighter);
            } catch (RuntimeException e) {
                at = null;
            }
        }
        return new Vector2f(at != null ? at : craft.carrier.getLocation());
    }

    private void follow(ShipAPI fighter, ShipAPI carrier, Vector2f to, float amount) {
        Vector2f at = fighter.getLocation();
        float blend = Math.min(1f, amount * 3f);
        at.set(at.x + (to.x - at.x) * blend, at.y + (to.y - at.y) * blend);
        fighter.getVelocity().set(carrier.getVelocity());
        fighter.setAngularVelocity(0f);
    }
}
