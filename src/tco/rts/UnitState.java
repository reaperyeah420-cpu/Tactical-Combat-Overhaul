package tco.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;

import tco.tug.TowCombatPlugin;

/** Per-ship RTS state: current and queued orders, and who flies the ship. */
public class UnitState {

    public enum OrderType { HOLD, MOVE, FACE, ATTACK, ESCORT, CAPTURE, BOARD, RETREAT, ATTACK_MOVE, FORMATION, TOW }

    /** A queued or pending order. */
    public static class Order {
        public final OrderType type;
        public final float x;
        public final float y;
        public final ShipAPI target;
        public final BattleObjectiveAPI objective;
        public final Float facing;
        public final boolean strafe;

        private Order(OrderType type, float x, float y, ShipAPI target, BattleObjectiveAPI objective,
                Float facing, boolean strafe) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.target = target;
            this.objective = objective;
            this.facing = facing;
            this.strafe = strafe;
        }

        public static Order move(float x, float y) {
            return new Order(OrderType.MOVE, x, y, null, null, null, false);
        }

        public static Order move(float x, float y, Float facing, boolean strafe) {
            return new Order(OrderType.MOVE, x, y, null, null, facing, strafe);
        }

        public static Order face(float facing) {
            return new Order(OrderType.FACE, 0f, 0f, null, null, facing, false);
        }

        public static Order attack(ShipAPI enemy) {
            return new Order(OrderType.ATTACK, 0f, 0f, enemy, null, null, false);
        }

        public static Order escort(ShipAPI friend) {
            return new Order(OrderType.ESCORT, 0f, 0f, friend, null, null, false);
        }

        public static Order capture(BattleObjectiveAPI objective) {
            return new Order(OrderType.CAPTURE, 0f, 0f, null, objective, null, false);
        }

        /** Close to boarding range and launch. */
        public static Order board(ShipAPI enemy) {
            return new Order(OrderType.BOARD, 0f, 0f, enemy, null, null, false);
        }

        public static Order retreat() {
            return new Order(OrderType.RETREAT, 0f, 0f, null, null, null, false);
        }

        /** Move, fighting whatever is met on the way. */
        public static Order attackMove(float x, float y) {
            return new Order(OrderType.ATTACK_MOVE, x, y, null, null, null, false);
        }

        /** Go take a stranded friendly ship in tow. */
        public static Order tow(ShipAPI stranded) {
            return new Order(OrderType.TOW, 0f, 0f, stranded, null, null, false);
        }

        /** Keep station on the formation leader. */
        public static Order formation() {
            return new Order(OrderType.FORMATION, 0f, 0f, null, null, null, false);
        }

        public Vector2f getLocation() {
            switch (type) {
                case MOVE:
                case ATTACK_MOVE:
                    return new Vector2f(x, y);
                case ATTACK:
                case ESCORT:
                case BOARD:
                case TOW:
                    return target == null || !target.isAlive() ? null : target.getLocation();
                case CAPTURE:
                    return objective == null ? null : objective.getLocation();
                default:
                    return null;
            }
        }
    }

    public final ShipAPI ship;
    public final PassiveShipAI ai;

    public OrderType order = OrderType.HOLD;
    public final Vector2f point = new Vector2f();
    public ShipAPI target;
    public BattleObjectiveAPI objective;
    public Float facing;
    public boolean strafe;
    public final List<Order> queue = new ArrayList<Order>();

    public boolean vanillaAi = false;
    // the game's AI on the engine's player ship, ticked by us when the engine doesn't (not on autopilot)
    ShipAIPlugin manualVanillaAi;
    Object manualHandle;
    // personality the stance gave the game's AI (null: the ship's own)
    public String vanillaPersonality;

    public boolean holdFire = false;

    public boolean[] savedAutofire;

    // getShipAI() returns a wrapper, so track this ourselves
    public boolean aiInstalled = false;
    public int lastSeenEngineTicks = 0;
    public float stallTime = 0f;

    public boolean attackAssigned = false;
    public boolean retreatAssigned = false;
    public boolean escortAssigned = false;
    public boolean cancelRetreat = false;

    public Stance stance = Stance.DEFENSIVE;
    public Stance savedStance = Stance.DEFENSIVE;
    public Formation formation;
    public boolean autoEngaged = false;
    public ShipAPI engageTarget;
    public final Vector2f engageAnchor = new Vector2f();
    public Vector2f idleAnchor;
    public ShipAPI rescueTarget;
    public boolean rescueMove;
    public final Vector2f rescuePoint = new Vector2f();
    public boolean pendingClear = false;
    // auto-engagement bookkeeping: when the target last changed, how long it's been out of reach, and
    // targets given up on (until engine time)
    public float lastSwitch = -100f;
    public float outOfReach = 0f;
    public final Map<ShipAPI, Float> ignoreUntil = new HashMap<ShipAPI, Float>();
    // a Defensive ship flying back to its spot while still on HOLD, so it can fight on the way
    public Vector2f returnTo;
    public boolean evadeMove;
    public final Vector2f evadePoint = new Vector2f();

    public UnitState(ShipAPI ship) {
        this.ship = ship;
        this.ai = new PassiveShipAI(ship, this);
    }

    /** With append, queued behind the current order. */
    public void give(Order next, boolean append) {
        if (append && order != OrderType.HOLD && order != OrderType.RETREAT) {
            queue.add(next);
            return;
        }
        queue.clear();
        apply(next);
    }

    private void apply(Order next) {
        if (attackAssigned || escortAssigned || autoEngaged) pendingClear = true;
        autoEngaged = false;
        engageTarget = null;
        idleAnchor = null;
        returnTo = null;
        evadeMove = false;
        vanillaAi = false;
        order = next.type;
        point.set(next.x, next.y);
        target = next.target;
        objective = next.objective;
        facing = next.facing;
        strafe = next.strafe;
        attackAssigned = false;
        retreatAssigned = false;
        escortAssigned = false;
        if (next.type != OrderType.RETREAT) cancelRetreat = true;
    }

    /** Drops all orders and ends free AI control. */
    public void hold() {
        vanillaAi = false;
        cancelRetreat = true;
        returnTo = null;
        queue.clear();
        clearCurrent();
    }

    /** Moves on to the next queued order. */
    public void completeOrder() {
        if (queue.isEmpty()) clearCurrent();
        else apply(queue.remove(0));
    }

    private void clearCurrent() {
        if (attackAssigned || escortAssigned || autoEngaged) pendingClear = true;
        attackAssigned = false;
        escortAssigned = false;
        autoEngaged = false;
        engageTarget = null;
        idleAnchor = null;
        order = OrderType.HOLD;
        target = null;
        objective = null;
        facing = null;
        strafe = false;
    }

    /** Attack orders are flown by the default AI. */
    public boolean isEngaging() {
        return order == OrderType.ATTACK;
    }

    /** The default AI should fly this ship. */
    public boolean isAiFlown() {
        return vanillaAi || autoEngaged || order == OrderType.ATTACK || order == OrderType.RETREAT || isEscortingByAi();
    }

    /**
     * Escorting the player's own ships uses the game's escort assignment; allied ships can't be targets, so
     * that's a plain follow.
     */
    public boolean isEscortingByAi() {
        return order == OrderType.ESCORT && target != null && !target.isAlly() && target.getOwner() == ship.getOwner();
    }

    /** Where the order points, or null when holding. */
    public Vector2f getOrderLocation() {
        switch (order) {
            case MOVE:
            case ATTACK_MOVE:
                return point;
            case ATTACK:
            case ESCORT:
            case BOARD:
            case TOW:
                return target == null || !target.isAlive() ? null : target.getLocation();
            case CAPTURE:
                return objective == null ? null : objective.getLocation();
            default:
                return null;
        }
    }

    public String describe() {
        if (ship.isRetreating() || order == OrderType.RETREAT) return "Retreat";
        String text;
        switch (order) {
            case MOVE:
                text = "Move";
                break;
            case FACE:
                text = "Turn";
                break;
            case ATTACK:
                text = target != null && target.isAlive() ? "Attack" : "Engage";
                break;
            case ESCORT:
                text = "Escort";
                break;
            case BOARD:
                text = "Board";
                break;
            case CAPTURE:
                text = "Capture";
                break;
            case ATTACK_MOVE:
                text = "Attack-move";
                break;
            case FORMATION:
                text = "Formation";
                break;
            case TOW:
                text = "Tow";
                break;
            default:
                text = vanillaAi ? "AI" : "Hold";
                break;
        }
        if (autoEngaged) text += " (fighting)";
        if (TowCombatPlugin.towing(Global.getCombatEngine(), ship) != null) text += " (towing)";
        if (!queue.isEmpty()) text += " +" + queue.size();
        if (holdFire) text += " HF";
        Object supply = ship.getCustomData().get("tco_supply");
        if (supply instanceof Float) text += " S" + Math.round((Float) supply);
        return text;
    }
}
