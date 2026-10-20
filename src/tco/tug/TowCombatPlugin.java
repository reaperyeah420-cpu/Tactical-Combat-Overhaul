package tco.tug;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.AiUtil;
import tco.Msg;
import tco.TcoSettings;
import tco.ammo.AmmoCombatPlugin;
import tco.ammo.AmmoRules;
import tco.battle.DeathThroes;
import tco.dock.RepairDock;
import tco.fob.SupplyFob;
import tco.power.PowerCombatPlugin;
import tco.repair.RepairCombatPlugin;
import tco.rts.FogOfWar;
import tco.rts.RtsCombatPlugin;
import tco.rts.UnitState;
import tco.rts.UnitState.Order;
import tco.rts.UnitState.OrderType;

/**
 * Tugs hook onto immobile friendly ships and tow them to a Repair Dock, tender, FOB or supply ship.
 * At a dock or tender the tow ends once drones can reach; at a FOB or supply ship it waits for the player.
 * Ordered in RTS mode; AI-flown tugs do it on their own.
 */
public class TowCombatPlugin extends BaseEveryFrameCombatPlugin {

    public static final String HULLMOD_ID = "tco_tug_rig";
    private static final String SPEED_ID = "tco_tow";
    private static final float SCAN_INTERVAL = 0.25f;
    private static final float HOOK_GAP = 150f;

    private static class Tow {
        ShipAPI tug;
        ShipAPI towed;
        ShipAPI destination;
        UnitState aiState;
        Object handle;
        boolean hooked;
        boolean braking;
        float time;
    }

    private CombatEngineAPI engine;
    private final Map<ShipAPI, Tow> tows = new HashMap<ShipAPI, Tow>();
    private float scanTimer = 0f;

    public static boolean hasTug(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(HULLMOD_ID);
    }

    /** Reactor down or every engine out. */
    public static boolean isImmobilized(CombatEngineAPI engine, ShipAPI ship) {
        if (ship == null || !ship.isAlive() || ship.isHulk() || ship.isStation() || ship.isStationModule()) return false;
        if (!isTowable(ship) || DeathThroes.isDying(ship)) return false;
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        if (power != null && power.isMeltedDown(ship)) return true;
        return ship.getEngineController() != null && !ship.getEngineController().getShipEngines().isEmpty()
                && ship.getEngineController().isFlamedOut();
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine != null && engine.isPaused()) amount = 0f;
        if (engine == null || amount <= 0f || engine.isCombatOver()) return;

        scanTimer -= amount;
        if (scanTimer <= 0f) {
            scanTimer = SCAN_INTERVAL;
            pickUpRtsOrders();
            startAiTows();
            checkTows();
        }
        for (Tow tow : tows.values()) {
            tow.time += amount;
            if (tow.hooked) pull(tow, amount);
        }
    }

    private void pickUpRtsOrders() {
        for (ShipAPI ship : engine.getShips()) {
            if (!hasTug(ship) || tows.containsKey(ship)) continue;
            UnitState unit = RtsCombatPlugin.unitOf(engine, ship);
            if (unit == null || unit.order != OrderType.TOW || unit.target == null) continue;
            Tow tow = new Tow();
            tow.tug = ship;
            tow.towed = unit.target;
            tows.put(ship, tow);
        }
    }

    private void startAiTows() {
        for (ShipAPI tug : engine.getShips()) {
            if (!hasTug(tug) || tows.containsKey(tug) || !isFreeAiShip(tug)) continue;
            ShipAPI best = null;
            float bestDist = TcoSettings.tugAutoRange;
            for (ShipAPI other : engine.getShips()) {
                if (other == tug || other.getOwner() != tug.getOwner() || !isImmobilized(engine, other)) continue;
                // AI only tows to repairs
                if (isBeingTowed(other) || destinationFor(other, tug.getOwner(), true) == null) continue;
                float dist = distance(tug.getLocation(), other.getLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = other;
                }
            }
            if (best == null) continue;
            Tow tow = new Tow();
            tow.tug = tug;
            tow.towed = best;
            tow.aiState = new UnitState(tug);
            tow.aiState.give(Order.tow(best), false);
            tow.handle = AiUtil.install(tug, tow.aiState.ai);
            tows.put(tug, tow);
        }
    }

    private boolean isFreeAiShip(ShipAPI ship) {
        if (!ship.isAlive() || ship.isHulk() || ship.isRetreating() || ship.isFighter()) return false;
        if (ship == engine.getPlayerShip() && !engine.isUIAutopilotOn()) return false;
        if (RtsCombatPlugin.controls(engine, ship)) return false;
        if (isImmobilized(engine, ship)) return false;
        return AiUtil.isGameAi(ship);
    }

    public static boolean isTowed(CombatEngineAPI engine, ShipAPI ship) {
        Object plugin = engine == null ? null : engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof TowCombatPlugin && ((TowCombatPlugin) plugin).isBeingTowed(ship);
    }

    private boolean isBeingTowed(ShipAPI ship) {
        for (Tow tow : tows.values()) {
            if (tow.towed == ship) return true;
        }
        return false;
    }

    private ShipAPI destinationFor(ShipAPI towed, int side, boolean repairOnly) {
        RepairCombatPlugin repair = RepairCombatPlugin.get(engine);
        AmmoCombatPlugin ammo = AmmoCombatPlugin.get(engine);
        List<ShipAPI> docks = new ArrayList<ShipAPI>();
        List<ShipAPI> tenders = new ArrayList<ShipAPI>();
        List<ShipAPI> fobs = new ArrayList<ShipAPI>();
        List<ShipAPI> suppliers = new ArrayList<ShipAPI>();
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != side || !ship.isAlive() || ship.isHulk() || ship == towed) continue;
            if (RepairDock.isDock(ship)) docks.add(ship);
            else if (SupplyFob.isFob(ship)) fobs.add(ship);
        }
        if (repair != null) {
            for (ShipAPI tender : repair.tenders()) {
                if (tender.getOwner() == side && tender != towed && !RepairDock.isDock(tender) && repair.canWork(tender)) tenders.add(tender);
            }
        }
        if (ammo != null) {
            for (ShipAPI supplier : ammo.stockedSuppliers()) {
                if (supplier.getOwner() == side && supplier != towed && !SupplyFob.isFob(supplier)) suppliers.add(supplier);
            }
        }
        List<List<ShipAPI>> order = new ArrayList<List<ShipAPI>>();
        order.add(docks);
        order.add(tenders);
        if (!repairOnly) {
            order.add(fobs);
            order.add(suppliers);
        }
        for (List<ShipAPI> kind : order) {
            ShipAPI best = null;
            float bestDist = Float.MAX_VALUE;
            for (ShipAPI ship : kind) {
                float dist = distance(ship.getLocation(), towed.getLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    best = ship;
                }
            }
            if (best != null) return best;
        }
        return null;
    }

    private static boolean repairsAt(ShipAPI destination) {
        return RepairDock.isDock(destination) || RepairCombatPlugin.hasBay(destination);
    }

    private float dropRange(ShipAPI destination) {
        if (RepairDock.isDock(destination) || RepairCombatPlugin.hasBay(destination)) {
            return RepairCombatPlugin.rangeOf(destination) * 0.7f;
        }
        AmmoCombatPlugin ammo = AmmoCombatPlugin.get(engine);
        return ammo == null ? 800f : ammo.supplyRange(destination) * 0.7f;
    }

    private void checkTows() {
        Iterator<Map.Entry<ShipAPI, Tow>> it = tows.entrySet().iterator();
        while (it.hasNext()) {
            Tow tow = it.next().getValue();
            UnitState unit = tow.aiState != null ? tow.aiState : RtsCombatPlugin.unitOf(engine, tow.tug);
            String end = whyOver(tow, unit);
            if (end != null) {
                finish(tow, unit, end);
                it.remove();
                continue;
            }
            if (!tow.hooked) {
                float gap = distance(tow.tug.getLocation(), tow.towed.getLocation())
                        - tow.tug.getCollisionRadius() - tow.towed.getCollisionRadius();
                if (gap > HOOK_GAP) continue;
                tow.hooked = true;
                tow.time = 0f;
                tow.destination = destinationFor(tow.towed, tow.tug.getOwner(), tow.aiState != null);
                if (tow.destination != null) {
                    Vector2f to = tow.destination.getLocation();
                    unit.give(Order.move(to.x, to.y), false);
                } else {
                    unit.give(Order.move(tow.tug.getLocation().x, tow.tug.getLocation().y), false);
                }
                if (tow.tug.getOwner() == 0) {
                    Msg.about(engine, tow.tug, tow.tug.getName() + " has " + tow.towed.getName() + " in tow"
                            + (tow.destination != null ? ", heading for " + tow.destination.getName() + "." : "."));
                }
                continue;
            }
            // At a FOB or supply ship: stop and wait for the player
            if (tow.destination != null && !repairsAt(tow.destination) && unit.order == OrderType.MOVE
                    && withinReach(tow)) {
                unit.hold();
                if (tow.tug.getOwner() == 0) {
                    Msg.about(engine, tow.tug, tow.tug.getName() + " has brought " + tow.towed.getName() + " to "
                            + tow.destination.getName() + " and is holding the tow (Stop to let go).");
                }
                continue;
            }
            // Follow moving help
            if (tow.destination != null && tow.aiState != null && unit.order == OrderType.MOVE
                    && distance(unit.point, tow.destination.getLocation()) > 400f) {
                unit.give(Order.move(tow.destination.getLocation().x, tow.destination.getLocation().y), false);
            }
        }
    }

    private String whyOver(Tow tow, UnitState unit) {
        ShipAPI tug = tow.tug;
        ShipAPI towed = tow.towed;
        if (!tug.isAlive() || tug.isHulk() || !engine.isEntityInPlay(tug) || tug.isRetreating()) return "lost";
        if (!towed.isAlive() || towed.isHulk() || !engine.isEntityInPlay(towed)) return "lost";
        if (unit == null) return "lost";
        if (tow.aiState != null && !AiUtil.isOurs(tug, tow.handle)) return "taken";
        if (!isImmobilized(engine, towed)) return "free";
        if (!tow.hooked) return unit.order == OrderType.TOW && unit.target == towed ? null : "cancelled";
        // Move orders steer; anything else drops the tow
        if (unit.order != OrderType.MOVE && unit.order != OrderType.HOLD) return "cancelled";
        // Docks and tenders take the ship; the tug brakes the load first
        if (tow.destination != null && tow.destination.isAlive() && repairsAt(tow.destination) && withinReach(tow)) {
            if (!tow.braking) {
                tow.braking = true;
                if (unit.order == OrderType.MOVE) unit.hold();
            }
            if (towed.getVelocity().length() < 8f && tug.getVelocity().length() < 15f) return "arrived";
        }
        return null;
    }

    private boolean withinReach(Tow tow) {
        return distance(tow.towed.getLocation(), tow.destination.getLocation()) - tow.towed.getCollisionRadius()
                - tow.destination.getCollisionRadius() < dropRange(tow.destination);
    }

    private void finish(Tow tow, UnitState unit, String why) {
        tow.tug.getMutableStats().getMaxSpeed().unmodify(SPEED_ID);
        tow.tug.getMutableStats().getAcceleration().unmodify(SPEED_ID);
        if (tow.aiState != null) {
            tow.aiState.ai.restoreAutofire();
            if (AiUtil.isOurs(tow.tug, tow.handle)) tow.tug.resetDefaultAI();
        } else if (unit != null && "arrived".equals(why) && unit.order == OrderType.MOVE) {
            unit.hold();
        }
        if (tow.hooked && tow.tug.getOwner() == 0 && tow.towed.isAlive()) {
            if ("arrived".equals(why)) Msg.about(engine, tow.tug, tow.tug.getName() + " has brought " + tow.towed.getName() + " in.");
            else if ("cancelled".equals(why)) Msg.about(engine, tow.tug, tow.tug.getName() + " has let go of " + tow.towed.getName() + ".");
        }
    }

    private void pull(Tow tow, float amount) {
        ShipAPI tug = tow.tug;
        ShipAPI towed = tow.towed;
        // Cable: pulls only when stretched, toward the tug
        float dx = tug.getLocation().x - towed.getLocation().x;
        float dy = tug.getLocation().y - towed.getLocation().y;
        float dist = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist < 1f) return;
        float ux = dx / dist;
        float uy = dy / dist;
        float clear = tug.getCollisionRadius() + towed.getCollisionRadius();
        float cable = clear + TcoSettings.towLength;
        Vector2f v = towed.getVelocity();
        float blend = Math.min(1f, amount * 3f);
        if (tow.braking) {
            // Haul in to a stop
            float stop = Math.min(1f, amount * 2f);
            v.set(v.x + (tug.getVelocity().x - v.x) * stop, v.y + (tug.getVelocity().y - v.y) * stop);
            towed.setAngularVelocity(towed.getAngularVelocity() * (1f - stop));
            return;
        }

        if (dist > cable) {
            // Taut: keep up along the cable
            float tugAlong = tug.getVelocity().x * ux + tug.getVelocity().y * uy;
            float want = Math.max(0f, tugAlong) + (dist - cable) * 1.2f;
            float along = v.x * ux + v.y * uy;
            if (along < want) {
                float add = (want - along) * blend;
                v.set(v.x + ux * add, v.y + uy * add);
            }
        } else if (dist < clear + 60f) {
            // Too close: ease off
            float along = v.x * ux + v.y * uy;
            float want = -40f;
            if (along > want) {
                float add = (along - want) * blend;
                v.set(v.x - ux * add, v.y - uy * add);
            }
        }
        // Slack drag
        float drag = 1f - Math.min(1f, amount * 0.4f);
        v.set(v.x * drag, v.y * drag);
        float max = tug.getMutableStats().getMaxSpeed().getModifiedValue() * 1.3f + 30f;
        float speed = v.length();
        if (speed > max) v.scale(max / speed);
        towed.setAngularVelocity(towed.getAngularVelocity() * (1f - Math.min(1f, amount * 2f)));

        float tugMass = Math.max(1f, tug.getMass());
        float share = tugMass / (tugMass + Math.max(1f, towed.getMass()));
        float mult = Math.max(TcoSettings.tugMinSpeedMult, Math.min(1f, share * TcoSettings.tugPullStrength));
        tug.getMutableStats().getMaxSpeed().modifyMult(SPEED_ID, mult);
        tug.getMutableStats().getAcceleration().modifyMult(SPEED_ID, mult);
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (tows.isEmpty()) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2f);
        for (Tow tow : tows.values()) {
            if (!tow.hooked || FogOfWar.isHidden(tow.tug)) continue;
            ShipAPI tug = tow.tug;
            double f = Math.toRadians(tug.getFacing());
            // From the side facing the load
            float lx = tow.towed.getLocation().x - tug.getLocation().x;
            float ly = tow.towed.getLocation().y - tug.getLocation().y;
            float len = Math.max(1f, (float) Math.sqrt(lx * lx + ly * ly));
            float sx = tug.getLocation().x + lx / len * tug.getCollisionRadius() * 0.6f;
            float sy = tug.getLocation().y + ly / len * tug.getCollisionRadius() * 0.6f;
            if (!viewport.isNearViewport(tug.getLocation(), tug.getCollisionRadius() + 600f)) continue;
            GL11.glColor4f(0.85f, 0.8f, 0.55f, 0.8f);
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(sx, sy);
            GL11.glVertex2f(tow.towed.getLocation().x, tow.towed.getLocation().y);
            GL11.glEnd();
        }
        GL11.glPopAttrib();
    }

    /** The towed ship, or null. */
    public static ShipAPI towing(CombatEngineAPI engine, ShipAPI tug) {
        Object plugin = engine == null ? null : engine.getCustomData().get(ENGINE_KEY);
        if (!(plugin instanceof TowCombatPlugin)) return null;
        Tow tow = ((TowCombatPlugin) plugin).tows.get(tug);
        return tow != null && tow.hooked ? tow.towed : null;
    }

    public static final String ENGINE_KEY = "tco_tow_plugin";

    static boolean isTowable(ShipAPI ship) {
        return !ship.isFighter() && !ship.isDrone() && !ship.isShuttlePod() && !AmmoRules.isShuttle(ship);
    }
}
