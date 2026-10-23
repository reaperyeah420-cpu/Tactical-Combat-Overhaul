package tco.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.AiUtil;
import tco.TcoSettings;
import tco.ammo.AmmoCombatPlugin;
import tco.ammo.AmmoRules;
import tco.dock.RepairDock;
import tco.fob.SupplyFob;
import tco.power.PowerCombatPlugin;
import tco.repair.RepairCombatPlugin;
import tco.rescue.EscapePodPlugin;
import tco.rts.RtsCombatPlugin;
import tco.rts.UnitState;
import tco.rts.UnitState.Order;

/**
 * Logistics for AI-flown ships: support ships hold station behind the line, ships low on ammo or
 * damaged fall back to a supply ship or tender. Errands are flown by the passive AI, then handed back.
 */
public class LogisticsCombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final float INTERVAL = 1f;
    private static final float ERRAND_TIMEOUT = 90f;
    private static final float ERRAND_COOLDOWN = 20f;
    // Waiting beside the support ship with no ammo or repairs coming: give up after this long
    private static final float STALL_TIMEOUT = 30f;
    private static final float ARRIVED = 700f;
    // Past the ship's longest gun, for an enemy to break off an errand
    private static final float THREAT_MARGIN = 300f;
    private static final float STATION_MOVE_THRESHOLD = 300f;

    private enum Kind { STATION, AMMO, REPAIR, RESTOCK }

    private static class Errand {
        Kind kind;
        UnitState state;
        Object handle;
        ShipAPI support;
        float time = 0f;
        final Vector2f dest = new Vector2f();
        // Ammo level, or minus broken parts, when it last went up; time since then while beside the support ship
        float progress = -Float.MAX_VALUE;
        float stalled = 0f;
    }

    private CombatEngineAPI engine;
    private final Map<ShipAPI, Errand> errands = new HashMap<ShipAPI, Errand>();
    private final Map<ShipAPI, Float> cooldowns = new HashMap<ShipAPI, Float>();
    private final Set<ShipAPI> spent = new HashSet<ShipAPI>();
    private final Set<ShipAPI> alone = new HashSet<ShipAPI>();
    private float timer = 0f;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        // Paused: the game still passes frame time
        if (engine != null && engine.isPaused()) amount = 0f;
        if (engine == null || amount <= 0f || !TcoSettings.logisticsAiEnabled || engine.isCombatOver()) return;
        for (Errand errand : errands.values()) errand.time += amount;
        timer -= amount;
        if (timer > 0f) return;
        timer = INTERVAL;

        Iterator<Map.Entry<ShipAPI, Float>> cool = cooldowns.entrySet().iterator();
        while (cool.hasNext()) {
            Map.Entry<ShipAPI, Float> entry = cool.next();
            float left = entry.getValue() - INTERVAL;
            if (left <= 0f) cool.remove();
            else entry.setValue(left);
        }

        AmmoCombatPlugin ammo = AmmoCombatPlugin.get(engine);
        RepairCombatPlugin repair = RepairCombatPlugin.get(engine);
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        for (int side = 0; side <= 1; side++) {
            plan(side, ammo, repair, power);
        }
    }

    private void plan(int side, AmmoCombatPlugin ammo, RepairCombatPlugin repair, PowerCombatPlugin power) {
        List<ShipAPI> supplies = new ArrayList<ShipAPI>();
        if (ammo != null) {
            for (ShipAPI ship : ammo.stockedSuppliers()) {
                if (ship.getOwner() == side && !SupplyFob.isFob(ship)) supplies.add(ship);
            }
        }
        List<ShipAPI> tenders = new ArrayList<ShipAPI>();
        List<ShipAPI> allSupport = new ArrayList<ShipAPI>();
        List<ShipAPI> fobs = new ArrayList<ShipAPI>();
        if (repair != null) {
            for (ShipAPI ship : repair.tenders()) {
                if (ship.getOwner() != side) continue;
                allSupport.add(ship);
                if (repair.canWork(ship)) tenders.add(ship);
            }
        }
        if (ammo != null) {
            for (ShipAPI ship : ammo.supplyShips()) {
                if (ship.getOwner() == side && !allSupport.contains(ship)) allSupport.add(ship);
            }
            for (ShipAPI ship : ammo.stockedSuppliers()) {
                if (ship.getOwner() == side && SupplyFob.isFob(ship)) fobs.add(ship);
            }
        }

        // Line position and the way back
        Vector2f friends = centroid(side, true);
        Vector2f foes = centroid(1 - side, false);
        Vector2f back = new Vector2f(0f, side == 0 ? -1f : 1f);
        if (friends != null && foes != null) {
            float dx = friends.x - foes.x;
            float dy = friends.y - foes.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 1f) back.set(dx / len, dy / len);
        }

        // Support ships someone is heading to
        Set<ShipAPI> wanted = new HashSet<ShipAPI>();
        for (Errand errand : errands.values()) {
            if ((errand.kind == Kind.AMMO || errand.kind == Kind.REPAIR) && errand.support != null
                    && errand.support.getOwner() == side) {
                wanted.add(errand.support);
            }
        }
        List<ShipAPI> stationing = new ArrayList<ShipAPI>();
        Map<ShipAPI, Vector2f> stationBase = new HashMap<ShipAPI, Vector2f>();
        List<float[]> threats = threats(side);

        // Support ships
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != side || !allSupport.contains(ship) || !canTake(ship)) continue;
            boolean supplier = supplies.contains(ship);
            boolean tender = tenders.contains(ship);
            Errand current = errands.get(ship);
            if (current != null && current.kind == Kind.RESTOCK) {
                if (!restocked(ship, current, ammo, repair)) continue;
                release(ship, current, false);
                current = null;
            }
            if (!supplier && !tender) {
                boolean canRestock = ammo != null && ammo.stockOf(ship) >= 0f && !fobs.isEmpty();
                // Tenders restock at a Repair Dock
                boolean isTender = repair != null && repair.tenders().contains(ship) && !RepairDock.isDock(ship);
                List<ShipAPI> docks = new ArrayList<ShipAPI>();
                if (isTender) {
                    for (ShipAPI other : engine.getShips()) {
                        if (other.getOwner() == side && other.isAlive() && !other.isHulk() && RepairDock.isDock(other)
                                && repair.partsLeft(other) > 0f) {
                            docks.add(other);
                        }
                    }
                }
                if (!docks.isEmpty()) {
                    if (current != null) release(ship, current, false);
                    ShipAPI dock = nearest(ship, docks);
                    if (dock != null) start(ship, Kind.RESTOCK, dock, back);
                } else if (canRestock) {
                    if (current != null) release(ship, current, false);
                    ShipAPI fob = nearest(ship, fobs);
                    if (fob != null) start(ship, Kind.RESTOCK, fob, back);
                } else if (friends == null && isPlayers(ship)) {
                    spent.add(ship);
                    keepClear(ship, current, side, back, threats);
                } else {
                    // Also hands back one kept clear above once warships turn up again
                    boolean first = spent.add(ship);
                    if (current != null) release(ship, current, false);
                    if (first) goHomeOrFight(ship);
                }
                continue;
            }
            spent.remove(ship);
            if (friends == null) {
                // No warships left to support. The game's AI parks a civilian hull with no friends in place,
                // so the player's are kept clear of the enemy instead
                if (isPlayers(ship)) {
                    keepClear(ship, current, side, back, threats);
                    continue;
                }
                if (current != null) release(ship, current, false);
                // A retreat order the fleet dropped is given again
                if (alone.add(ship) || SupportRoles.isCivilian(ship)) goHomeOrFight(ship);
                continue;
            }
            alone.remove(ship);
            float range = supplier && ammo != null ? ammo.supplyRange(ship)
                    : RepairCombatPlugin.rangeOf(ship) + ship.getCollisionRadius();
            Vector2f rescue = null;
            if (tender && power != null) rescue = rescuePoint(ship, side, range, back, repair, power);
            // AI fleets' support ships keep back; the player's fight unless needed
            boolean keepBack = SupportRoles.isCivilian(ship) || ship.getOwner() != 0 || ship.isAlly();
            if (!keepBack && rescue == null && !wanted.contains(ship)) {
                if (current != null && current.kind == Kind.STATION) release(ship, current, false);
                continue;
            }
            if (rescue != null) {
                station(ship, current, safe(ship, rescue, side, back, threats));
                continue;
            }
            float standoff = Math.max(500f, range * TcoSettings.logisticsStationDepth);
            stationBase.put(ship, new Vector2f(friends.x + back.x * standoff, friends.y + back.y * standoff));
            stationing.add(ship);
        }

        // Spread station points across the line
        if (!stationing.isEmpty()) {
            Collections.sort(stationing, new Comparator<ShipAPI>() {
                @Override
                public int compare(ShipAPI a, ShipAPI b) {
                    String ka = a.getFleetMemberId() == null ? "" : a.getFleetMemberId();
                    String kb = b.getFleetMemberId() == null ? "" : b.getFleetMemberId();
                    return ka.compareTo(kb);
                }
            });
            float spacing = 0f;
            for (ShipAPI ship : stationing) spacing = Math.max(spacing, ship.getCollisionRadius() * 2f);
            spacing += 250f;
            float sideX = -back.y;
            float sideY = back.x;
            for (int i = 0; i < stationing.size(); i++) {
                ShipAPI ship = stationing.get(i);
                float shift = (i - (stationing.size() - 1) / 2f) * spacing;
                Vector2f base = stationBase.get(ship);
                Vector2f dest = clamp(new Vector2f(base.x + sideX * shift, base.y + sideY * shift));
                station(ship, errands.get(ship), safe(ship, dest, side, back, threats));
            }
        }

        // Ships needing resupply or repair
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != side || !canTake(ship) || allSupport.contains(ship)) continue;
            Errand current = errands.get(ship);
            if (current != null && current.kind == Kind.STATION) {
                release(ship, current, false);
                current = null;
            }
            if (power != null && power.isMeltedDown(ship)) {
                if (current != null) release(ship, current, false);
                continue;
            }

            if (current != null) {
                if (finished(ship, current, ammo, repair) || enemyClose(ship) || stalled(ship, current, ammo, repair)) {
                    release(ship, current, true);
                } else {
                    Vector2f dest = besidePoint(current.support, ship, back);
                    if (distance(dest, current.dest) > 200f) {
                        current.dest.set(dest);
                        current.state.give(Order.move(dest.x, dest.y), false);
                    }
                }
                continue;
            }
            if (cooldowns.containsKey(ship)) continue;
            // Not while an enemy is at hand: a ship on an errand doesn't fight
            if (enemyClose(ship)) continue;

            if (ammo != null && !supplies.isEmpty() && ammo.ammoLevel(ship) < TcoSettings.logisticsAmmoLow) {
                ShipAPI nearest = nearest(ship, supplies);
                if (nearest != null) start(ship, Kind.AMMO, nearest, back);
                continue;
            }
            if (repair != null && !tenders.isEmpty() && repair.brokenParts(ship) >= TcoSettings.logisticsRepairParts) {
                ShipAPI nearest = nearest(ship, tenders);
                if (nearest != null) start(ship, Kind.REPAIR, nearest, back);
            }
        }

        // Drop finished errands
        Iterator<Map.Entry<ShipAPI, Errand>> it = errands.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ShipAPI, Errand> entry = it.next();
            ShipAPI ship = entry.getKey();
            if (ship.getOwner() != side) continue;
            Errand errand = entry.getValue();
            boolean gone = !ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship) || ship.isRetreating();
            boolean takenOver = !AiUtil.isOurs(ship, errand.handle) || RtsCombatPlugin.controls(engine, ship)
                    || AiUtil.playerFlying(engine, ship);
            if (gone || takenOver) {
                errand.state.ai.restoreAutofire();
                it.remove();
                // A retreat is flown by the game's AI: our errand AI would hold it in place
                if (ship.isAlive() && ship.isRetreating() && AiUtil.isOurs(ship, errand.handle)) AiUtil.resetDefault(ship);
            }
        }
    }

    private boolean restocked(ShipAPI ship, Errand errand, AmmoCombatPlugin ammo, RepairCombatPlugin repair) {
        if (errand.time > ERRAND_TIMEOUT * 2f) return true;
        if (RepairDock.isDock(errand.support)) {
            // Tender at a Repair Dock
            ShipAPI dock = errand.support;
            if (repair == null || !dock.isAlive() || dock.isHulk() || repair.partsLeft(dock) <= 0f) return true;
            return repair.partsLeft(ship) >= RepairCombatPlugin.partsOf(ship) * TcoSettings.logisticsRestockDone
                    && repair.dronesLeft(ship) >= RepairCombatPlugin.dronesOf(ship);
        }
        if (ammo == null) return true;
        ShipAPI fob = errand.support;
        if (!fob.isAlive() || fob.isHulk() || !engine.isEntityInPlay(fob) || ammo.stockOf(fob) <= 0f) return true;
        Object max = ship.getCustomData().get(AmmoRules.SUPPLY_MAX_KEY);
        if (!(max instanceof Float)) return true;
        return ammo.stockOf(ship) >= (Float) max * TcoSettings.logisticsRestockDone;
    }

    /**
     * Out of stock: civilian hulls of AI fleets retreat, warships fight, the player's go back to the game's
     * AI.
     */
    private void goHomeOrFight(ShipAPI ship) {
        if (ship.getOwner() == 0 && !ship.isAlly()) return;
        if (!SupportRoles.isCivilian(ship)) return;
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(ship.getOwner());
            DeployedFleetMemberAPI member = fleet.getDeployedFleetMember(ship);
            if (member != null) fleet.getTaskManager(ship.isAlly()).orderRetreat(member, false, false);
        } catch (RuntimeException e) {
            Global.getLogger(LogisticsCombatPlugin.class).warn("TCO: could not order a spent support ship home", e);
        }
    }

    private static boolean isPlayers(ShipAPI ship) {
        return ship.getOwner() == 0 && !ship.isAlly();
    }

    /** Falls back to its own edge of the map, moving off whenever an enemy comes within reach. */
    private void keepClear(ShipAPI ship, Errand current, int side, Vector2f back, List<float[]> threats) {
        if (current != null && current.kind != Kind.STATION) {
            release(ship, current, false);
            current = null;
        }
        Vector2f edge = clamp(new Vector2f(ship.getLocation().x + back.x * 20000f, ship.getLocation().y + back.y * 20000f));
        station(ship, current, safe(ship, edge, side, back, threats));
    }

    private Vector2f rescuePoint(ShipAPI tender, int side, float range, Vector2f back,
            RepairCombatPlugin repair, PowerCombatPlugin power) {
        ShipAPI best = null;
        float bestDist = Float.MAX_VALUE;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != side || ship == tender || !power.isMeltedDown(ship)) continue;
            if (!ship.isAlive() || ship.isHulk()) continue;
            if (inReachOfAnyTender(ship, side, repair)) {
                if (distance(ship.getLocation(), tender.getLocation()) < range + ship.getCollisionRadius()) return null;
                continue;
            }
            float dist = distance(ship.getLocation(), tender.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = ship;
            }
        }
        if (best == null) return null;
        float reach = range * 0.5f;
        return clamp(new Vector2f(best.getLocation().x + back.x * reach, best.getLocation().y + back.y * reach));
    }

    private boolean inReachOfAnyTender(ShipAPI ship, int side, RepairCombatPlugin repair) {
        for (ShipAPI tender : repair.tenders()) {
            if (tender.getOwner() != side || !repair.canWork(tender)) continue;
            float reach = RepairCombatPlugin.rangeOf(tender) + tender.getCollisionRadius() + ship.getCollisionRadius();
            if (distance(ship.getLocation(), tender.getLocation()) < reach) return true;
        }
        return false;
    }

    private boolean finished(ShipAPI ship, Errand errand, AmmoCombatPlugin ammo, RepairCombatPlugin repair) {
        if (errand.time > ERRAND_TIMEOUT) return true;
        ShipAPI support = errand.support;
        if (!support.isAlive() || support.isHulk() || !engine.isEntityInPlay(support)) return true;
        if (errand.kind == Kind.AMMO) {
            if (ammo == null || ammo.stockOf(support) <= 0f) return true;
            return ammo.ammoLevel(ship) >= TcoSettings.logisticsAmmoDone;
        }
        if (repair == null || !repair.canWork(support)) return true;
        return repair.brokenParts(ship) == 0;
    }

    /** A known enemy warship within the ship's longest gun range (plus a margin). */
    private boolean enemyClose(ShipAPI ship) {
        float reach = 600f;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (!weapon.isDecorative()) reach = Math.max(reach, weapon.getRange());
        }
        reach += THREAT_MARGIN;
        for (ShipAPI other : engine.getShips()) {
            if (other.getOwner() == ship.getOwner() || other.getOwner() == 100) continue;
            if (!other.isAlive() || other.isHulk() || other.isFighter() || other.isShuttlePod() || AmmoRules.isShuttle(other)) continue;
            if (distance(ship.getLocation(), other.getLocation()) - other.getCollisionRadius() > reach) continue;
            if (AiAwareness.knowsShip(engine, ship, other)) return true;
        }
        return false;
    }

    /** Beside the support ship and nothing improving for STALL_TIMEOUT. */
    private boolean stalled(ShipAPI ship, Errand errand, AmmoCombatPlugin ammo, RepairCombatPlugin repair) {
        float level = errand.kind == Kind.AMMO ? (ammo == null ? 0f : ammo.ammoLevel(ship))
                : (repair == null ? 0f : -repair.brokenParts(ship));
        if (level > errand.progress + 0.001f) {
            errand.progress = level;
            errand.stalled = 0f;
            return false;
        }
        if (distance(ship.getLocation(), errand.dest) < ARRIVED) errand.stalled += INTERVAL;
        return errand.stalled >= STALL_TIMEOUT;
    }

    private boolean canTake(ShipAPI ship) {
        if (!ship.isAlive() || ship.isHulk() || ship.isFighter() || ship.isDrone() || ship.isShuttlePod()) return false;
        if (ship.isStation() || ship.isStationModule() || ship.isPiece() || ship.isRetreating()) return false;
        if (AmmoRules.isShuttle(ship) || SupplyFob.isFobPart(ship) || ship.getCustomData().containsKey("tco_captured")) return false;
        if (AiUtil.playerFlying(engine, ship)) return false;
        if (RtsCombatPlugin.controls(engine, ship)) return false;
        Errand errand = errands.get(ship);
        if (errand != null) return AiUtil.isOurs(ship, errand.handle);
        return AiUtil.isGameAi(ship);
    }

    private void station(ShipAPI ship, Errand current, Vector2f dest) {
        if (current == null) {
            current = new Errand();
            current.kind = Kind.STATION;
            current.state = new UnitState(ship);
            current.handle = AiUtil.install(ship, current.state.ai);
            errands.put(ship, current);
        } else if (distance(dest, current.dest) < STATION_MOVE_THRESHOLD && current.state.order != UnitState.OrderType.HOLD) {
            return;
        } else if (distance(dest, current.dest) < STATION_MOVE_THRESHOLD && distance(ship.getLocation(), dest) < STATION_MOVE_THRESHOLD) {
            return;
        }
        current.dest.set(dest);
        current.state.give(Order.move(dest.x, dest.y), false);
    }

    private void start(ShipAPI ship, Kind kind, ShipAPI support, Vector2f back) {
        Errand errand = new Errand();
        errand.kind = kind;
        errand.support = support;
        errand.state = new UnitState(ship);
        Vector2f dest = besidePoint(support, ship, back);
        errand.dest.set(dest);
        errand.state.give(Order.move(dest.x, dest.y), false);
        errand.handle = AiUtil.install(ship, errand.state.ai);
        errands.put(ship, errand);
    }

    private void release(ShipAPI ship, Errand errand, boolean cooldown) {
        errands.remove(ship);
        errand.state.ai.restoreAutofire();
        if (AiUtil.isOurs(ship, errand.handle)) AiUtil.resetDefault(ship);
        if (cooldown) cooldowns.put(ship, ERRAND_COOLDOWN);
    }

    private static final float SAFETY_MARGIN = 600f;

    private List<float[]> threats(int side) {
        List<float[]> out = new ArrayList<float[]>();
        for (ShipAPI foe : engine.getShips()) {
            if (foe.getOwner() == side || foe.getOwner() == 100 || !foe.isAlive() || foe.isHulk()) continue;
            if (foe.isFighter() || foe.isDrone() || foe.isShuttlePod() || AmmoRules.isShuttle(foe) || foe.isStationModule()) continue;
            if (foe.getCustomData().containsKey(EscapePodPlugin.POD_KEY)) continue;
            float reach = 0f;
            for (WeaponAPI w : foe.getAllWeapons()) {
                if (w.getType() == WeaponAPI.WeaponType.MISSILE || w.isDecorative()) continue;
                reach = Math.max(reach, w.getRange());
            }
            reach = Math.min(3000f, Math.max(reach, 600f)) + foe.getCollisionRadius();
            out.add(new float[] {foe.getLocation().x, foe.getLocation().y, reach});
        }
        return out;
    }

    private Vector2f safe(ShipAPI ship, Vector2f dest, int side, Vector2f back, List<float[]> threats) {
        if (threats.isEmpty()) return dest;
        float pad = ship.getCollisionRadius() + SAFETY_MARGIN;
        Vector2f p = new Vector2f(dest);
        if (inDanger(ship.getLocation(), pad, threats)) {
            p.set(ship.getLocation().x + back.x * 1500f, ship.getLocation().y + back.y * 1500f);
        }
        for (int pass = 0; pass < 4; pass++) {
            boolean moved = false;
            for (float[] t : threats) {
                float dx = p.x - t[0], dy = p.y - t[1];
                float d = (float) Math.sqrt(dx * dx + dy * dy);
                float need = t[2] + pad;
                if (d >= need) continue;
                if (d < 1f) {
                    dx = back.x;
                    dy = back.y;
                    d = 1f;
                }
                p.set(t[0] + dx / d * need, t[1] + dy / d * need);
                moved = true;
            }
            clamp(p);
            if (!moved) break;
        }
        return p;
    }

    private static boolean inDanger(Vector2f at, float pad, List<float[]> threats) {
        for (float[] t : threats) {
            float dx = at.x - t[0], dy = at.y - t[1];
            float need = t[2] + pad * 0.5f;
            if (dx * dx + dy * dy < need * need) return true;
        }
        return false;
    }

    private Vector2f besidePoint(ShipAPI support, ShipAPI ship, Vector2f back) {
        float gap = support.getCollisionRadius() + ship.getCollisionRadius() + 150f;
        return clamp(new Vector2f(support.getLocation().x + back.x * gap, support.getLocation().y + back.y * gap));
    }

    private Vector2f centroid(int side, boolean excludeSupport) {
        float x = 0f;
        float y = 0f;
        int n = 0;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != side || !ship.isAlive() || ship.isHulk() || ship.isFighter() || ship.isDrone()) continue;
            if (ship.isShuttlePod() || AmmoRules.isShuttle(ship) || SupplyFob.isFobPart(ship) || ship.isStationModule()) continue;
            if (excludeSupport) {
                if (ship.getCustomData().containsKey(AmmoRules.SUPPLY_MAX_KEY) || RepairCombatPlugin.hasBay(ship)) continue;
                Errand errand = errands.get(ship);
                if (errand != null && errand.kind != Kind.STATION) continue;
            }
            x += ship.getLocation().x;
            y += ship.getLocation().y;
            n++;
        }
        return n == 0 ? null : new Vector2f(x / n, y / n);
    }

    private ShipAPI nearest(ShipAPI ship, List<ShipAPI> options) {
        ShipAPI best = null;
        float bestDist = Float.MAX_VALUE;
        for (ShipAPI option : options) {
            if (option == ship) continue;
            float dist = distance(ship.getLocation(), option.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = option;
            }
        }
        return best;
    }

    private Vector2f clamp(Vector2f point) {
        float halfW = engine.getMapWidth() / 2f - 600f;
        float halfH = engine.getMapHeight() / 2f - 600f;
        point.x = Math.max(-halfW, Math.min(halfW, point.x));
        point.y = Math.max(-halfH, Math.min(halfH, point.y));
        return point;
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
