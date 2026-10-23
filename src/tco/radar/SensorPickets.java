package tco.radar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.TcoSettings;
import tco.ai.AiAwareness;
import tco.ammo.AmmoRules;
import tco.hullmods.CombatInformationCenter;

/**
 * Sensor pickets (stage 4): a CIC flies a squadron of picketsCic expendable sensor drones with picketReserveSquadronsCic
 * more squadrons in reserve, an Auxiliary CIC picketsAux (picketReserveSquadronsAux in reserve). Launched (the EW
 * panel; AI command ships at picketAiDelay and to replace losses) they fan out picketDistance ahead of their command
 * ship, towards the nearest enemy its side knows or the enemy's edge, picketSpread degrees apart, and hold there
 * (PicketAI), or on PATROL roam the battlefield, each to the part of the map its side has looked at least recently
 * (leaning towards the enemy's edge, away from known enemies and the other pickets' waypoints). AI command ships patrol
 * while their side knows of no enemy and hold station once it does. Each is a sight eye for its side (FogOfWar.reveals) and a small datalinked radar (RadarCombatPlugin:
 * picketRadarPower), so it is also an emitter enemy ESM hears and ARMs home on. Fragile (the repair drone hull).
 * Recalled ones fly home and go back into the stock; lost ones are gone.
 */
public class SensorPickets extends BaseEveryFrameCombatPlugin {

    public static final String ENGINE_KEY = "tco_sensorPickets";
    public static final String PICKET_KEY = "tco_picket";
    private static final String VARIANT = "tco_repair_drone_Hull";
    private static final float INTERVAL = 0.5f;
    // A station moves on only past this, so pickets hold still
    private static final float RESTATION = 400f;
    private static final float AI_RELAUNCH_SECONDS = 15f;

    private static final float CELL = 2500f;
    private static final float WAYPOINT_SECONDS = 30f;
    private static final float WAYPOINT_REACHED = 400f;

    private static final class Picket {
        ShipAPI ship, mother;
        int slot;
        PicketAI ai;
        boolean recalling;
        Vector2f waypoint;
        float waypointAt;
    }

    // Command ships whose pickets patrol (true) or hold station (false); AI ones choose for themselves
    private final Map<ShipAPI, Boolean> patrol = new HashMap<ShipAPI, Boolean>();
    // Per side: when each map cell's centre was last in sight
    private final float[][][] looked = new float[2][][];

    private CombatEngineAPI engine;
    private float timer;
    private final List<Picket> pickets = new ArrayList<Picket>();
    // Pickets each command ship has out or lost (recalled ones come back off this)
    private final Map<ShipAPI, Integer> used = new HashMap<ShipAPI, Integer>();
    private final Map<ShipAPI, Float> aiNextLaunch = new HashMap<ShipAPI, Float>();

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
    }

    private static SensorPickets get(CombatEngineAPI engine) {
        Object plugin = engine == null ? null : engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof SensorPickets ? (SensorPickets) plugin : null;
    }

    public static boolean isPicket(ShipAPI ship) {
        return ship != null && ship.getCustomData().containsKey(PICKET_KEY);
    }

    /** The squadron: pickets out at once. */
    public static int capacity(ShipAPI ship) {
        int level = CombatInformationCenter.level(ship);
        return level == 2 ? TcoSettings.picketsCic : level == 1 ? TcoSettings.picketsAux : 0;
    }

    /** Every picket the ship carries into the battle: the squadron and its reserve squadrons. */
    public static int stock(ShipAPI ship) {
        int level = CombatInformationCenter.level(ship);
        int reserve = level == 2 ? TcoSettings.picketReserveSquadronsCic : level == 1 ? TcoSettings.picketReserveSquadronsAux : 0;
        return capacity(ship) * (1 + Math.max(0, reserve));
    }

    /** Pickets still aboard (the reserve). */
    public static int aboard(CombatEngineAPI engine, ShipAPI mother) {
        SensorPickets sp = get(engine);
        Integer n = sp == null ? null : sp.used.get(mother);
        return Math.max(0, stock(mother) - (n == null ? 0 : n));
    }

    /** Pickets out (flying, not recalled). */
    public static int out(CombatEngineAPI engine, ShipAPI mother) {
        SensorPickets sp = get(engine);
        if (sp == null) return 0;
        int n = 0;
        for (Picket p : sp.pickets) {
            if (p.mother == mother && !p.recalling) n++;
        }
        return n;
    }

    /** Fills the squadron (up to capacity out) from the pickets aboard. */
    public static int launch(CombatEngineAPI engine, ShipAPI mother) {
        SensorPickets sp = get(engine);
        if (sp == null || !TcoSettings.radarEnabled) return 0;
        int n = 0;
        while (out(engine, mother) < capacity(mother) && aboard(engine, mother) > 0 && sp.launchOne(mother)) n++;
        return n;
    }

    public static boolean isPatrolling(CombatEngineAPI engine, ShipAPI mother) {
        SensorPickets sp = get(engine);
        return sp != null && Boolean.TRUE.equals(sp.patrol.get(mother));
    }

    /** STATION (false) or PATROL (true) for a command ship's pickets. */
    public static void setPatrol(CombatEngineAPI engine, ShipAPI mother, boolean on) {
        SensorPickets sp = get(engine);
        if (sp == null || capacity(mother) <= 0) return;
        sp.patrol.put(mother, on);
        for (Picket p : sp.pickets) {
            if (p.mother == mother) p.waypoint = null;
        }
    }

    public static int recall(CombatEngineAPI engine, ShipAPI mother) {
        SensorPickets sp = get(engine);
        if (sp == null) return 0;
        int n = 0;
        for (Picket p : sp.pickets) {
            if (p.mother == mother && !p.recalling) {
                p.recalling = true;
                n++;
            }
        }
        return n;
    }

    private boolean launchOne(ShipAPI mother) {
        if (!mother.isAlive() || mother.isHulk() || !engine.isEntityInPlay(mother)) return false;
        // The lowest free slot of the fan
        int slot = 0;
        boolean taken = true;
        while (taken) {
            taken = false;
            for (Picket p : pickets) {
                if (p.mother == mother && !p.recalling && p.slot == slot) {
                    taken = true;
                    slot++;
                    break;
                }
            }
        }
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(mother.getOwner());
            boolean quiet = fleet.isSuppressDeploymentMessages();
            fleet.setSuppressDeploymentMessages(true);
            ShipAPI ship;
            try {
                ship = fleet.spawnShipOrWing(VARIANT, new Vector2f(mother.getLocation()), mother.getFacing());
            } finally {
                fleet.setSuppressDeploymentMessages(quiet);
            }
            fleet.removeDeployed(ship, false);
            // Out of ammo, resupply, boarding and RTS selection like TCO's other craft
            ship.setCustomData(AmmoRules.SHUTTLE_KEY, Boolean.TRUE);
            ship.setCustomData("tco_ammo_done", Boolean.TRUE);
            ship.setCustomData(PICKET_KEY, Boolean.TRUE);
            ship.setCollisionClass(CollisionClass.FIGHTER);
            ship.setInvalidTransferCommandTarget(true);
            Picket p = new Picket();
            p.ship = ship;
            p.mother = mother;
            p.slot = slot;
            p.ai = new PicketAI(ship);
            ship.setShipAI(p.ai);
            ship.setAnimatedLaunch();
            ship.setLaunchingShip(mother);
            Vector2f[] st = station(p);
            p.ai.flyTo(st[0], st[1]);
            pickets.add(p);
            Integer n = used.get(mother);
            used.put(mother, n == null ? 1 : n + 1);
            return true;
        } catch (RuntimeException e) {
            Global.getLogger(SensorPickets.class).warn("TCO: could not launch a sensor picket", e);
            return false;
        }
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused() || !TcoSettings.radarEnabled) return;
        timer -= amount;
        if (timer > 0f) return;
        timer = INTERVAL;
        float now = engine.getTotalElapsedTime(false);
        updateLooked(now);
        // AI command ships send theirs out once the battle is under way, and replace losses
        if (now >= TcoSettings.picketAiDelay) {
            for (ShipAPI ship : engine.getShips()) {
                if (capacity(ship) <= 0 || !ship.isAlive() || ship.isHulk()) continue;
                boolean player = engine.getPlayerShip() != null && ship.getOwner() == engine.getPlayerShip().getOwner() && !ship.isAlly();
                if (player) continue;
                Float next = aiNextLaunch.get(ship);
                if (next != null && now < next) continue;
                aiNextLaunch.put(ship, now + AI_RELAUNCH_SECONDS);
                launch(engine, ship);
                // Patrol while searching, hold station once the side has found something
                patrol.put(ship, !sideKnowsEnemy(ship.getOwner(), now));
            }
        }
        for (Iterator<Picket> it = pickets.iterator(); it.hasNext();) {
            Picket p = it.next();
            if (!p.ship.isAlive() || p.ship.isHulk() || !engine.isEntityInPlay(p.ship)) {
                it.remove();
                continue;
            }
            boolean motherOk = p.mother.isAlive() && !p.mother.isHulk() && engine.isEntityInPlay(p.mother);
            if (p.recalling && motherOk) {
                p.ai.flyTo(p.mother.getLocation(), p.mother.getLocation());
                float d = Vector2f.sub(p.ship.getLocation(), p.mother.getLocation(), null).length();
                if (d < p.mother.getCollisionRadius() + 300f) {
                    // Back aboard: ready to go again
                    engine.removeEntity(p.ship);
                    Integer n = used.get(p.mother);
                    if (n != null) used.put(p.mother, Math.max(0, n - 1));
                    it.remove();
                }
                continue;
            }
            if (!motherOk) continue;
            if (Boolean.TRUE.equals(patrol.get(p.mother))) {
                boolean reached = p.waypoint != null && Vector2f.sub(p.ship.getLocation(), p.waypoint, null).length() < WAYPOINT_REACHED;
                if (p.waypoint == null || reached || now - p.waypointAt > WAYPOINT_SECONDS) {
                    p.waypoint = waypoint(p, now);
                    p.waypointAt = now;
                }
                // Looks where it is going
                Vector2f dir = Vector2f.sub(p.waypoint, p.ship.getLocation(), null);
                Vector2f look = new Vector2f(p.waypoint.x + dir.x * 0.2f, p.waypoint.y + dir.y * 0.2f);
                p.ai.flyTo(p.waypoint, look);
                continue;
            }
            p.waypoint = null;
            Vector2f[] st = station(p);
            Vector2f cur = p.ai.dest();
            if (cur == null || Vector2f.sub(cur, st[0], null).length() > RESTATION) p.ai.flyTo(st[0], st[1]);
        }
        used.keySet().removeIf(s -> !engine.isEntityInPlay(s));
        aiNextLaunch.keySet().removeIf(s -> !engine.isEntityInPlay(s));
        patrol.keySet().removeIf(s -> !engine.isEntityInPlay(s));
    }

    private boolean sideKnowsEnemy(int side, float now) {
        AiAwareness awareness = AiAwareness.get(engine);
        if (awareness == null || side < 0 || side > 1) return false;
        for (AiAwareness.Known k : awareness.memory(side).values()) {
            if (now - k.time <= TcoSettings.aiBlindGraceSeconds) return true;
        }
        return false;
    }

    /** Marks the map cells each side's eyes (ships and pickets) cover now. */
    private void updateLooked(float now) {
        int cx = Math.max(1, (int) Math.ceil(engine.getMapWidth() / CELL));
        int cy = Math.max(1, (int) Math.ceil(engine.getMapHeight() / CELL));
        for (int side = 0; side <= 1; side++) {
            if (looked[side] == null || looked[side].length != cx) {
                looked[side] = new float[cx][cy];
                for (float[] col : looked[side]) java.util.Arrays.fill(col, -1000f);
            }
        }
        for (ShipAPI ship : engine.getShips()) {
            int side = ship.getOwner();
            if (side < 0 || side > 1 || !tco.rts.FogOfWar.reveals(ship, side)) continue;
            float r = tco.rts.FogOfWar.sightRange(ship);
            for (int x = 0; x < cx; x++) {
                for (int y = 0; y < cy; y++) {
                    Vector2f c = cellCentre(x, y, cx, cy);
                    float dx = c.x - ship.getLocation().x, dy = c.y - ship.getLocation().y;
                    if (dx * dx + dy * dy <= r * r) looked[side][x][y] = now;
                }
            }
        }
    }

    private Vector2f cellCentre(int x, int y, int cx, int cy) {
        float w = engine.getMapWidth(), h = engine.getMapHeight();
        return new Vector2f(-w / 2f + (x + 0.5f) * (w / cx), -h / 2f + (y + 0.5f) * (h / cy));
    }

    /**
     * A patrol waypoint: the cell its side has looked at least recently (staleness over 120 s), leaning towards the
     * enemy's edge, nearer ones first, away from known enemies (3000) and from the other pickets' waypoints (3000).
     */
    private Vector2f waypoint(Picket p, float now) {
        int side = p.mother.getOwner();
        float[][] grid = side >= 0 && side <= 1 ? looked[side] : null;
        if (grid == null) return station(p)[0];
        int cx = grid.length, cy = grid[0].length;
        float h = engine.getMapHeight();
        float diag = (float) Math.hypot(engine.getMapWidth(), h);
        float enemyEdge = side == 1 ? -h / 2f : h / 2f;
        AiAwareness awareness = AiAwareness.get(engine);
        Vector2f best = null;
        float bestScore = -Float.MAX_VALUE;
        for (int x = 0; x < cx; x++) {
            for (int y = 0; y < cy; y++) {
                Vector2f c = cellCentre(x, y, cx, cy);
                float score = Math.min(1f, (now - grid[x][y]) / 120f) + 0.4f * (1f - Math.abs(c.y - enemyEdge) / h)
                        - 0.8f * Vector2f.sub(c, p.ship.getLocation(), null).length() / diag + (float) Math.random() * 0.05f;
                for (Picket o : pickets) {
                    if (o != p && o.waypoint != null && Vector2f.sub(o.waypoint, c, null).length() < 3000f) score -= 0.6f;
                }
                if (awareness != null) {
                    for (AiAwareness.Known k : awareness.memory(side).values()) {
                        if (now - k.time < 20f && Vector2f.sub(k.loc, c, null).length() < 3000f) {
                            score -= 1f;
                            break;
                        }
                    }
                }
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                }
            }
        }
        return best == null ? station(p)[0] : best;
    }

    /** Where a picket stands (a fan picketDistance ahead of its command ship, towards the enemy) and where it looks. */
    private Vector2f[] station(Picket p) {
        ShipAPI mother = p.mother;
        int side = mother.getOwner();
        Vector2f to = null;
        AiAwareness awareness = AiAwareness.get(engine);
        float now = engine.getTotalElapsedTime(false);
        float best = Float.MAX_VALUE;
        if (awareness != null && side >= 0 && side <= 1) {
            for (AiAwareness.Known k : awareness.memory(side).values()) {
                if (now - k.time > TcoSettings.aiSearchMemorySeconds) continue;
                float d = Vector2f.sub(k.loc, mother.getLocation(), null).length();
                if (d < best) {
                    best = d;
                    to = k.loc;
                }
            }
        }
        if (to == null) to = new Vector2f(mother.getLocation().x, side == 1 ? -engine.getMapHeight() / 2f : engine.getMapHeight() / 2f);
        float base = (float) Math.atan2(to.y - mother.getLocation().y, to.x - mother.getLocation().x);
        int n = Math.max(1, capacity(mother));
        float offset = (float) Math.toRadians((p.slot - (n - 1) / 2f) * TcoSettings.picketSpread);
        float a = base + offset;
        float hw = engine.getMapWidth() / 2f - 500f, hh = engine.getMapHeight() / 2f - 500f;
        float x = mother.getLocation().x + (float) Math.cos(a) * TcoSettings.picketDistance;
        float y = mother.getLocation().y + (float) Math.sin(a) * TcoSettings.picketDistance;
        Vector2f at = new Vector2f(Math.max(-hw, Math.min(hw, x)), Math.max(-hh, Math.min(hh, y)));
        Vector2f look = new Vector2f(at.x + (float) Math.cos(a) * 1000f, at.y + (float) Math.sin(a) * 1000f);
        return new Vector2f[] { at, look };
    }
}
