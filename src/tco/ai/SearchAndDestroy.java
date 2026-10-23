package tco.ai;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AssignmentTargetAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.TcoSettings;
import tco.ammo.AmmoRules;
import tco.battle.DeathThroes;
import tco.hullmods.CombatInformationCenter;
import tco.radar.RadarCombatPlugin;
import tco.radar.SensorPickets;
import tco.rts.FogOfWar;

/**
 * Search and destroy for the enemy side. Warships that know of no enemy within aiSearchEngageRange and have no other
 * order are grouped (up to aiSearchGroupSize) and sent, with a rally order on a waypoint, to the most promising place:
 * an enemy the side knows about now, then where one was last seen (moved on by its last speed), then an emitter its
 * Passive Sensor Suites hear, then the part of the map it has looked at least recently, leaning towards the enemy's
 * edge. A ship that finds an enemy, or gets another order from the admiral, leaves its group.
 */
public class SearchAndDestroy extends BaseEveryFrameCombatPlugin {

    private static final int SIDE = 1;
    private static final float THINK_INTERVAL = 1f;
    private static final float CELL = 2500f;
    private static final float GROUP_RADIUS = 2500f;
    private static final float ARRIVED = 700f;
    private static final float SPREAD = 2000f;
    private static final float EDGE_MARGIN = 1000f;
    private static final float FIRST_THINK = 5f;
    private static final float CHASE_REPLAN = 5f;

    private static final class Group {
        final List<ShipAPI> ships = new ArrayList<ShipAPI>();
        AssignmentInfo info;
        Vector2f goal;
        String kind;
        float plannedAt;
        // Leads (remembered enemies, heard emitters) when this plan was made
        int leads;
    }

    private CombatEngineAPI engine;
    private float timer = FIRST_THINK;
    private final List<Group> groups = new ArrayList<Group>();
    private final Map<ShipAPI, Group> groupOf = new HashMap<ShipAPI, Group>();
    // Last time this side's sight covered each cell's centre
    private float[][] looked;
    private int cellsX, cellsY;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused() || engine.isCombatOver()) return;
        timer -= amount;
        if (timer > 0f) return;
        timer = THINK_INTERVAL;
        CombatFleetManagerAPI fleet = engine.getFleetManager(SIDE);
        CombatTaskManagerAPI tasks = fleet == null ? null : fleet.getTaskManager(false);
        if (tasks == null) return;
        if (!TcoSettings.aiSearchEnabled || fleet.getGoal() == FleetGoal.ESCAPE || tasks.isInFullRetreat()
                || !fleet.getStations().isEmpty()) {
            disbandAll(fleet, tasks);
            return;
        }
        AiAwareness awareness = AiAwareness.get(engine);
        if (awareness == null) return;
        float now = engine.getTotalElapsedTime(false);
        updateLooked(now);
        think(fleet, tasks, awareness.memory(SIDE), now);
    }

    private void think(CombatFleetManagerAPI fleet, CombatTaskManagerAPI tasks, Map<ShipAPI, AiAwareness.Known> memory,
            float now) {
        // Who is free to search; support and command ships too once they're all that's left
        boolean warships = false;
        for (ShipAPI ship : engine.getShips()) {
            if (searcher(ship, false)) {
                warships = true;
                break;
            }
        }
        lastResort = !warships;
        List<ShipAPI> idle = new ArrayList<ShipAPI>();
        for (ShipAPI ship : engine.getShips()) {
            if (!searcher(ship)) {
                leave(ship, fleet, tasks, false);
                continue;
            }
            Group group = groupOf.get(ship);
            AssignmentInfo order = tasks.getAssignmentFor(ship);
            boolean ours = order != null && group != null && order == group.info;
            // The admiral keeps ordering attacks on ships the side hasn't found; awareness takes them off again, so
            // counting them as a job left the ship standing still
            if (!ours && AiAwareness.isBlindOrder(engine, SIDE, order)) {
                try {
                    tasks.removeAssignment(order);
                } catch (RuntimeException e) {
                }
                order = null;
            }
            if ((order != null && !ours) || enemyNear(ship, memory, now)) {
                // The admiral has a job for it, or it has found something to fight
                leave(ship, fleet, tasks, ours);
                continue;
            }
            if (group != null && group.info != null && order == null) {
                // The order was taken off (by the admiral or the player's side); plan it again
                group.goal = null;
            }
            idle.add(ship);
        }
        for (Iterator<Group> it = groups.iterator(); it.hasNext();) {
            Group g = it.next();
            // Ships gone from the battle (retreated, removed) no longer show up above
            for (Iterator<ShipAPI> s = g.ships.iterator(); s.hasNext();) {
                ShipAPI ship = s.next();
                if (engine.isEntityInPlay(ship) && ship.isAlive()) continue;
                s.remove();
                groupOf.remove(ship);
            }
            if (!g.ships.isEmpty()) continue;
            removeOrder(tasks, g);
            it.remove();
        }
        form(idle);
        List<Vector2f> taken = new ArrayList<Vector2f>();
        for (Group g : groups) {
            if (g.goal != null) taken.add(g.goal);
        }
        for (Group g : groups) {
            Vector2f at = centre(g);
            boolean arrived = g.goal != null && dist(at, g.goal) < ARRIVED;
            boolean chasing = "known enemy".equals(g.kind) || "last seen".equals(g.kind);
            boolean stale = now - g.plannedAt > (chasing ? CHASE_REPLAN : TcoSettings.aiSearchReplanSeconds);
            // Something new to go on since the plan
            boolean lead = g.goal != null && leads(memory) > g.leads;
            boolean lost = g.info == null || !g.ships.isEmpty() && tasks.getAssignmentFor(g.ships.get(0)) != g.info;
            if (g.goal != null && !arrived && !stale && !lead && !lost) continue;
            if (g.goal != null) taken.remove(g.goal);
            plan(g, at, memory, taken, now);
            if (g.goal == null) continue;
            taken.add(g.goal);
            give(fleet, tasks, g);
        }
    }

    // No warships left: support and command ships search too
    private boolean lastResort;

    private boolean searcher(ShipAPI ship) {
        return searcher(ship, lastResort);
    }

    /** Warships of the searching side, not busy dying or leaving; support and command ships only as a last resort. */
    private boolean searcher(ShipAPI ship, boolean anyArmed) {
        if (ship.getOwner() != SIDE || !ship.isAlive() || ship.isHulk() || ship.isFighter() || ship.isDrone()) return false;
        if (ship.isStation() || ship.isStationModule() || ship.isShuttlePod() || ship.isPiece() || ship.isRetreating()) return false;
        if (DeathThroes.isDying(ship) || AmmoRules.isShuttle(ship) || ship.getCustomData().containsKey(RadarCombatPlugin.HELPER_KEY)) return false;
        // Civilian hulls never go hunting
        if (SupportRoles.isCivilian(ship) || SensorPickets.isPicket(ship)) return false;
        // Support and command ships stay with the fleet while it has warships
        if (!anyArmed && (SupportRoles.isSupplier(ship) || SupportRoles.isTender(ship) || SupportRoles.isBoarder(ship)
                || CombatInformationCenter.level(ship) > 0)) {
            return false;
        }
        return engine.getFleetManager(SIDE).getDeployedFleetMember(ship) != null;
    }

    private boolean enemyNear(ShipAPI ship, Map<ShipAPI, AiAwareness.Known> memory, float now) {
        float range = TcoSettings.aiSearchEngageRange;
        // What it sees or tracks itself, before any report
        for (ShipAPI other : engine.getShips()) {
            if (other.getOwner() == SIDE || other.getOwner() < 0 || other.getOwner() > 1 || !other.isAlive() || other.isHulk()
                    || other.isFighter()) {
                continue;
            }
            if (dist(ship.getLocation(), other.getLocation()) <= range + other.getCollisionRadius()
                    && AiAwareness.knowsShip(engine, ship, other)) {
                return true;
            }
        }
        for (Map.Entry<ShipAPI, AiAwareness.Known> e : memory.entrySet()) {
            AiAwareness.Known k = e.getValue();
            if (now - k.time > TcoSettings.aiBlindGraceSeconds) continue;
            if (dist(ship.getLocation(), k.loc) <= range + e.getKey().getCollisionRadius()) return true;
        }
        return false;
    }

    private int leads(Map<ShipAPI, AiAwareness.Known> memory) {
        return memory.size() + heard().size();
    }

    private Set<ShipAPI> heard() {
        RadarCombatPlugin radar = RadarCombatPlugin.get(engine);
        Set<ShipAPI> heard = radar == null ? null : radar.heard(SIDE);
        return heard == null ? Collections.<ShipAPI>emptySet() : heard;
    }

    /** Takes a ship out of its group; with its search order, back to the admiral's free hand. */
    private void leave(ShipAPI ship, CombatFleetManagerAPI fleet, CombatTaskManagerAPI tasks, boolean clearOrder) {
        Group g = groupOf.remove(ship);
        if (g == null) return;
        g.ships.remove(ship);
        if (!clearOrder) return;
        DeployedFleetMemberAPI member = fleet.getDeployedFleetMember(ship);
        if (member == null) return;
        try {
            tasks.orderSearchAndDestroy(member, false);
        } catch (RuntimeException e) {
        }
    }

    /** Idle ships join a group nearby with room, or start one around the biggest of them. */
    private void form(List<ShipAPI> idle) {
        int size = Math.max(1, TcoSettings.aiSearchGroupSize);
        List<ShipAPI> loose = new ArrayList<ShipAPI>();
        for (ShipAPI ship : idle) {
            if (groupOf.containsKey(ship)) continue;
            Group join = null;
            float best = GROUP_RADIUS;
            for (Group g : groups) {
                if (g.ships.size() >= size) continue;
                float d = dist(ship.getLocation(), centre(g));
                if (d < best) {
                    best = d;
                    join = g;
                }
            }
            if (join != null) {
                join.ships.add(ship);
                groupOf.put(ship, join);
                // A newcomer needs the order too
                join.goal = null;
            } else {
                loose.add(ship);
            }
        }
        Collections.sort(loose, new Comparator<ShipAPI>() {
            @Override
            public int compare(ShipAPI a, ShipAPI b) {
                return b.getHullSize().ordinal() - a.getHullSize().ordinal();
            }
        });
        while (!loose.isEmpty()) {
            ShipAPI leader = loose.remove(0);
            Group g = new Group();
            g.ships.add(leader);
            groupOf.put(leader, g);
            while (g.ships.size() < size) {
                ShipAPI nearest = null;
                float best = GROUP_RADIUS;
                for (ShipAPI other : loose) {
                    float d = dist(leader.getLocation(), other.getLocation());
                    if (d < best) {
                        best = d;
                        nearest = other;
                    }
                }
                if (nearest == null) break;
                loose.remove(nearest);
                g.ships.add(nearest);
                groupOf.put(nearest, g);
            }
            groups.add(g);
        }
    }

    /** Picks where a group goes next. */
    private void plan(Group g, Vector2f from, Map<ShipAPI, AiAwareness.Known> memory, List<Vector2f> taken, float now) {
        float diag = (float) Math.hypot(engine.getMapWidth(), engine.getMapHeight());
        float bestScore = -Float.MAX_VALUE;
        Vector2f best = null;
        String kind = null;
        float memorySpan = Math.max(1f, TcoSettings.aiSearchMemorySeconds);
        for (Map.Entry<ShipAPI, AiAwareness.Known> e : memory.entrySet()) {
            AiAwareness.Known k = e.getValue();
            float age = now - k.time;
            boolean current = age <= TcoSettings.aiBlindGraceSeconds;
            Vector2f at = new Vector2f(k.loc.x + k.vel.x * Math.min(age, 8f), k.loc.y + k.vel.y * Math.min(age, 8f));
            float score = (current ? 3f : 2f * (1f - age / memorySpan)) - dist(from, at) / diag - crowding(at, taken, 0.5f);
            if (score > bestScore) {
                bestScore = score;
                best = at;
                kind = current ? "known enemy" : "last seen";
            }
        }
        RadarCombatPlugin radar = RadarCombatPlugin.get(engine);
        for (ShipAPI emitter : heard()) {
            Vector2f at = radar == null ? null : radar.esmGuess(SIDE, emitter);
            if (at == null) continue;
            float score = 1.6f - dist(from, at) / diag - crowding(at, taken, 1f);
            if (score > bestScore) {
                bestScore = score;
                best = at;
                kind = "ESM";
            }
        }
        if (looked != null) {
            float h = engine.getMapHeight();
            // The player's side comes in from the bottom edge
            float enemyEdge = SIDE == 1 ? -h / 2f : h / 2f;
            for (int x = 0; x < looked.length; x++) {
                for (int y = 0; y < looked[x].length; y++) {
                    Vector2f at = cellCentre(x, y);
                    float staleness = Math.min(1f, (now - looked[x][y]) / 120f);
                    if (staleness < 0.3f) continue;
                    float enemyward = 1f - Math.abs(at.y - enemyEdge) / h;
                    float score = staleness + 0.5f * enemyward - dist(from, at) / diag - crowding(at, taken, 1f)
                            + (float) Math.random() * 0.05f;
                    if (score > bestScore) {
                        bestScore = score;
                        best = at;
                        kind = "sweep";
                    }
                }
            }
        }
        g.goal = best == null ? null : clamp(best);
        g.kind = kind;
        g.plannedAt = now;
        g.leads = leads(memory);
    }

    private float crowding(Vector2f at, List<Vector2f> taken, float weight) {
        for (Vector2f other : taken) {
            if (dist(at, other) < SPREAD) return weight;
        }
        return 0f;
    }

    /** Gives the group a rally order on a waypoint at its goal, replacing its last one. */
    private void give(CombatFleetManagerAPI fleet, CombatTaskManagerAPI tasks, Group g) {
        AssignmentInfo old = g.info;
        try {
            AssignmentTargetAPI waypoint = fleet.createWaypoint(g.goal, false);
            if (waypoint == null) return;
            AssignmentInfo info = tasks.createAssignment(CombatAssignmentType.RALLY_TASK_FORCE, waypoint, false);
            if (info == null) return;
            for (ShipAPI ship : g.ships) {
                DeployedFleetMemberAPI member = fleet.getDeployedFleetMember(ship);
                if (member != null) tasks.giveAssignment(member, info, false);
            }
            g.info = info;
            if (old != null && old != info) tasks.removeAssignment(old);
            tasks.clearEmptyWaypoints();
        } catch (RuntimeException e) {
            Global.getLogger(SearchAndDestroy.class).warn("TCO search: could not give a search order", e);
            return;
        }
        if (TcoSettings.aiSearchLog) {
            Global.getLogger(SearchAndDestroy.class).info("TCO search: " + g.ships.size() + " ship(s) led by "
                    + g.ships.get(0).getHullSpec().getHullName() + " -> " + g.kind + " at (" + Math.round(g.goal.x)
                    + ", " + Math.round(g.goal.y) + ")");
        }
    }

    private void removeOrder(CombatTaskManagerAPI tasks, Group g) {
        if (g.info == null) return;
        try {
            tasks.removeAssignment(g.info);
            tasks.clearEmptyWaypoints();
        } catch (RuntimeException e) {
        }
        g.info = null;
    }

    private void disbandAll(CombatFleetManagerAPI fleet, CombatTaskManagerAPI tasks) {
        if (groups.isEmpty()) return;
        for (Group g : groups) {
            if (tasks != null) removeOrder(tasks, g);
        }
        groups.clear();
        groupOf.clear();
    }

    private void updateLooked(float now) {
        if (looked == null) {
            cellsX = Math.max(1, (int) Math.ceil(engine.getMapWidth() / CELL));
            cellsY = Math.max(1, (int) Math.ceil(engine.getMapHeight() / CELL));
            looked = new float[cellsX][cellsY];
            for (float[] column : looked) Arrays.fill(column, -1000f);
        }
        for (ShipAPI ship : engine.getShips()) {
            if (!FogOfWar.reveals(ship, SIDE)) continue;
            float r = FogOfWar.sightRange(ship);
            for (int x = 0; x < looked.length; x++) {
                for (int y = 0; y < looked[x].length; y++) {
                    if (dist(ship.getLocation(), cellCentre(x, y)) <= r) looked[x][y] = now;
                }
            }
        }
    }

    private Vector2f cellCentre(int x, int y) {
        float w = engine.getMapWidth(), h = engine.getMapHeight();
        return new Vector2f(-w / 2f + (x + 0.5f) * (w / cellsX), -h / 2f + (y + 0.5f) * (h / cellsY));
    }

    private Vector2f clamp(Vector2f at) {
        float hw = Math.max(0f, engine.getMapWidth() / 2f - EDGE_MARGIN);
        float hh = Math.max(0f, engine.getMapHeight() / 2f - EDGE_MARGIN);
        return new Vector2f(Math.max(-hw, Math.min(hw, at.x)), Math.max(-hh, Math.min(hh, at.y)));
    }

    private static Vector2f centre(Group g) {
        Vector2f c = new Vector2f();
        if (g.ships.isEmpty()) return c;
        for (ShipAPI ship : g.ships) Vector2f.add(c, ship.getLocation(), c);
        c.scale(1f / g.ships.size());
        return c;
    }

    private static float dist(Vector2f a, Vector2f b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
