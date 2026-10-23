package tco.hullmods;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamageAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.listeners.DamageTakenModifier;

import tco.TcoSettings;
import tco.missile.TandemCombatPlugin;
import tco.armor.ArmorEffects;

/**
 * Explosive Reactive Armor: charges per armor cell that stop HE missiles and blunt HE shells.
 * Each cell reloads once; after that only a tender or dock rearms it. Non-HE hits on worn armor can
 * blow tiles off. Each hit is decided once ({@link #heMult}), so it never uses two charges.
 */
public class ExplosiveReactiveArmor extends BaseHullMod {

    public static final String ID = "tco_era";

    private static final Color FLASH_CORE = new Color(255, 245, 220);
    private static final Color FLASH = new Color(255, 190, 90);
    private static final Color BLAST = new Color(255, 140, 40);
    private static final Color SPARK = new Color(255, 225, 150);
    private static final Color SMOKE = new Color(90, 85, 80);
    private static final Color DARK_SMOKE = new Color(45, 42, 40);
    private static final Color EMBER = new Color(255, 120, 40);
    private static final Color GLINT = new Color(190, 225, 255);
    private static final float BURST_INTERVAL = 0.08f;

    /** One ship's tiles: charges per cell and each cell's self-reload. */
    private static class State {
        int max;
        int[][] charges;
        boolean[][] reloaded;
        float[][] reloadAt;
        /**
         * Cells at least partly on the hull (hits land on the edge, where centres fall outside the outline).
         */
        boolean[][] onHull;
        float[][] armedArmor;
        float[][] beamRoll;
        int hullCells;
        float lastBurst = -1f;
        // {time, x, y, size, kind} in the ship's frame
        final List<float[]> pending = new ArrayList<float[]>();
    }

    /** One hit's ERA outcome, shared by every listener. */
    private static class Decision {
        ShipAPI ship;
        float mult;
        boolean missile;
        boolean spent;
    }

    private static final Map<ShipAPI, State> states = new WeakHashMap<ShipAPI, State>();
    private static final Map<Object, Decision> decisions = new WeakHashMap<Object, Decision>();

    private static final int POP = 0;
    private static final int EMBERS = 1;
    private static final int GLINTS = 2;

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        TcoSettings.load();
        float mult = 1f - TcoSettings.eraManeuverPenalty;
        stats.getAcceleration().modifyMult(id, mult);
        stats.getDeceleration().modifyMult(id, mult);
        stats.getTurnAcceleration().modifyMult(id, mult);
        stats.getMaxTurnRate().modifyMult(id, mult);
    }

    @Override
    public void applyEffectsAfterShipCreation(ShipAPI ship, String id) {
        if (!ship.hasListenerOfClass(Listener.class)) ship.addListener(new Listener(ship));
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return "" + TcoSettings.eraChargesFrigate;
        if (index == 1) return "" + TcoSettings.eraChargesDestroyer;
        if (index == 2) return "" + TcoSettings.eraChargesCruiser;
        if (index == 3) return "" + TcoSettings.eraChargesCapital;
        if (index == 4) return Math.round((1f - TcoSettings.eraShellDamageMult) * 100f) + "%";
        if (index == 5) return "" + Math.round(TcoSettings.eraReloadSeconds);
        if (index == 6) return Math.round(TcoSettings.eraManeuverPenalty * 100f) + "%";
        return null;
    }

    @Override
    public void advanceInCombat(ShipAPI ship, float amount) {
        CombatEngineAPI engine = Global.getCombatEngine();
        State s = states.get(ship);
        if (engine == null || s == null || engine.isPaused()) return;
        if (!ship.isAlive() || ship.isHulk()) {
            s.pending.clear();
            return;
        }
        float now = engine.getTotalElapsedTime(false);

        // Self-reloads due
        List<int[]> reloadedNow = null;
        for (int x = 0; x < s.charges.length; x++) {
            for (int y = 0; y < s.charges[x].length; y++) {
                if (s.reloadAt[x][y] < 0f || now < s.reloadAt[x][y]) continue;
                s.reloadAt[x][y] = -1f;
                s.reloaded[x][y] = true;
                s.charges[x][y] = s.max;
                s.armedArmor[x][y] = fittedArmor(ship, x, y);
                if (reloadedNow == null) reloadedNow = new ArrayList<int[]>();
                reloadedNow.add(new int[] {x, y});
            }
        }
        if (reloadedNow != null) showReload(engine, ship, s, reloadedNow, now, "ERA reloaded");

        // Delayed effects
        Iterator<float[]> it = s.pending.iterator();
        while (it.hasNext()) {
            float[] p = it.next();
            if (now < p[0]) continue;
            it.remove();
            Vector2f at = toWorld(ship, p[1], p[2]);
            if (!engine.getViewport().isNearViewport(at, 300f)) continue;
            Vector2f vel = ship.getVelocity();
            if (p[4] == POP) {
                engine.addHitParticle(at, vel, 40f * p[3], 1f, 0.07f, FLASH_CORE);
                engine.spawnExplosion(at, vel, BLAST, 16f * p[3], 0.22f);
                for (int i = 0; i < 3; i++) {
                    double a = Math.random() * Math.PI * 2.0;
                    float speed = 120f + (float) Math.random() * 160f;
                    engine.addHitParticle(at, new Vector2f(vel.x + (float) Math.cos(a) * speed, vel.y + (float) Math.sin(a) * speed),
                            3f + (float) Math.random() * 3f, 1f, 0.2f + (float) Math.random() * 0.15f, SPARK);
                }
            } else if (p[4] == EMBERS) {
                engine.addHitParticle(at, vel, (8f + (float) Math.random() * 8f) * p[3], 0.8f, 0.3f, EMBER);
                if (Math.random() < 0.5) {
                    engine.addSmokeParticle(at, drift(vel, 20f), (18f + (float) Math.random() * 14f) * p[3], 0.35f,
                            1f + (float) Math.random() * 0.6f, SMOKE);
                }
            } else {
                engine.addHitParticle(at, vel, 22f, 1f, 0.15f, GLINT);
                engine.addHitParticle(at, vel, 6f, 1f, 0.25f, Color.WHITE);
            }
        }
    }

    private static int chargesFor(HullSize size) {
        if (size == HullSize.CAPITAL_SHIP) return TcoSettings.eraChargesCapital;
        if (size == HullSize.CRUISER) return TcoSettings.eraChargesCruiser;
        if (size == HullSize.DESTROYER) return TcoSettings.eraChargesDestroyer;
        return TcoSettings.eraChargesFrigate;
    }

    private static boolean has(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(ID);
    }

    private static State state(ShipAPI ship) {
        State s = states.get(ship);
        if (s != null || !has(ship)) return s;
        ArmorGridAPI grid = ship.getArmorGrid();
        if (grid == null) return null;
        float[][] cells = grid.getGrid();
        s = new State();
        s.max = Math.max(1, chargesFor(ship.getHullSize()));
        s.charges = new int[cells.length][];
        s.reloaded = new boolean[cells.length][];
        s.reloadAt = new float[cells.length][];
        s.onHull = new boolean[cells.length][];
        s.armedArmor = new float[cells.length][];
        s.beamRoll = new float[cells.length][];
        float full = grid.getMaxArmorInCell();
        BoundsAPI bounds = ship.getExactBounds();
        List<SegmentAPI> segments = null;
        if (bounds != null) {
            bounds.update(ship.getLocation(), ship.getFacing());
            segments = bounds.getSegments();
        }
        for (int x = 0; x < cells.length; x++) {
            s.charges[x] = new int[cells[x].length];
            s.reloaded[x] = new boolean[cells[x].length];
            s.reloadAt[x] = new float[cells[x].length];
            s.onHull[x] = new boolean[cells[x].length];
            s.armedArmor[x] = new float[cells[x].length];
            s.beamRoll[x] = new float[cells[x].length];
            for (int y = 0; y < cells[x].length; y++) {
                s.charges[x][y] = s.max;
                s.reloadAt[x][y] = -1f;
                s.armedArmor[x][y] = full;
                s.beamRoll[x][y] = -1f;
                Vector2f at = grid.getLocation(x, y);
                boolean on = segments == null || (at != null && overlapsHull(segments, at, grid.getCellSize() * 0.5f));
                s.onHull[x][y] = on;
                if (on) s.hullCells++;
            }
        }
        states.put(ship, s);
        return s;
    }

    /**
     * ERA damage multiplier for this hit, using a charge if it applies: HE missiles eraMissileDamageMult,
     * HE shells eraShellDamageMult, else 1. Decided once per hit.
     */
    public static float heMult(ShipAPI ship, Vector2f point, Object param, DamageType type) {
        Decision d = decide(ship, point, param, type);
        return d == null ? 1f : d.mult;
    }

    private static Decision decide(ShipAPI ship, Vector2f point, Object param, DamageType type) {
        if (ship == null || point == null || type != DamageType.HIGH_EXPLOSIVE) return null;
        if (!(param instanceof DamagingProjectileAPI) || param instanceof BeamAPI) return null;
        if (!has(ship)) return null;
        Decision d = decisions.get(param);
        if (d != null && d.ship == ship) return d;

        d = new Decision();
        d.ship = ship;
        d.mult = 1f;
        d.missile = param instanceof MissileAPI;
        decisions.put(param, d);
        State s = state(ship);
        ArmorGridAPI grid = ship.getArmorGrid();
        if (s == null || grid == null) return d;
        int[] cell = grid.getCellAtLocation(point);
        if (cell == null || cell[0] < 0 || cell[1] < 0 || cell[0] >= s.charges.length || cell[1] >= s.charges[cell[0]].length) {
            return d;
        }
        int x = cell[0];
        int y = cell[1];
        // A hit cell is on the hull
        if (!s.onHull[x][y]) {
            s.onHull[x][y] = true;
            s.hullCells++;
        }
        if (s.charges[x][y] <= 0) return d;
        s.charges[x][y]--;
        d.mult = Math.max(0f, d.missile ? TcoSettings.eraMissileDamageMult : TcoSettings.eraShellDamageMult);
        // Tandem warheads: the precursor defeats the charge at the cost of part of the warhead; one that already went
        // through a shield has nothing left
        if (d.missile && TandemWarheads.fired((MissileAPI) param)) {
            float loss = Math.max(0f, Math.min(1f, TcoSettings.tandemPenetrationLoss));
            d.mult = TandemCombatPlugin.wentThroughShield(param) ? Math.max(0f, 1f - 2f * loss) / Math.max(0.01f, 1f - loss) : 1f - loss;
        }
        if (s.charges[x][y] <= 0) {
            d.spent = true;
            CombatEngineAPI engine = Global.getCombatEngine();
            if (!s.reloaded[x][y] && engine != null) {
                s.reloadAt[x][y] = engine.getTotalElapsedTime(false) + TcoSettings.eraReloadSeconds;
            }
        }
        return d;
    }

    private static float fittedArmor(ShipAPI ship, int x, int y) {
        ArmorGridAPI grid = ship.getArmorGrid();
        if (grid == null) return 1f;
        float full = grid.getMaxArmorInCell();
        return Math.max(full * 0.1f, grid.getArmorValue(x, y));
    }

    /**
     * Non-HE hits on worn armor can blow off tiles in the struck cell and its neighbours. Beams roll every
     * 0.5 s.
     * Returns how many cells lost their tiles.
     */
    private static int damageTiles(ShipAPI ship, Vector2f point, Object param) {
        State s = state(ship);
        ArmorGridAPI grid = ship.getArmorGrid();
        CombatEngineAPI engine = Global.getCombatEngine();
        if (s == null || grid == null || engine == null || point == null) return 0;
        int[] cell = grid.getCellAtLocation(point);
        if (cell == null) return 0;
        boolean beam = param instanceof BeamAPI;
        float now = engine.getTotalElapsedTime(false);
        int lost = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int x = cell[0] + dx;
                int y = cell[1] + dy;
                if (x < 0 || y < 0 || x >= s.charges.length || y >= s.charges[x].length) continue;
                if (s.charges[x][y] <= 0) continue;
                if (beam) {
                    if (s.beamRoll[x][y] >= 0f && now - s.beamRoll[x][y] < 0.5f) continue;
                    s.beamRoll[x][y] = now;
                }
                float share = grid.getArmorValue(x, y) / Math.max(1f, s.armedArmor[x][y]);
                boolean destroy = share < TcoSettings.eraTileLossFraction
                        || (share < TcoSettings.eraTileRiskFraction && Math.random() < TcoSettings.eraTileRiskChance);
                if (!destroy) continue;
                s.charges[x][y] = 0;
                s.reloadAt[x][y] = -1f;
                s.reloaded[x][y] = true;
                lost++;
            }
        }
        if (lost > 0) tilesLost(engine, ship, point, param);
        return lost;
    }

    private static void tilesLost(CombatEngineAPI engine, ShipAPI ship, Vector2f point, Object param) {
        if (!engine.getViewport().isNearViewport(point, 300f)) return;
        Vector2f shipVel = ship.getVelocity();
        float dx = point.x - ship.getLocation().x;
        float dy = point.y - ship.getLocation().y;
        float outAngle = (float) Math.toDegrees(Math.atan2(dy, dx));
        engine.addHitParticle(point, shipVel, 35f, 0.8f, 0.1f, FLASH);
        engine.spawnDebrisSmall(point, shipVel, 3, outAngle, 120f, 60f, 90f, 540f);
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(outAngle + (Math.random() - 0.5) * 140.0);
            float speed = 80f + (float) Math.random() * 140f;
            engine.addHitParticle(point, new Vector2f(shipVel.x + (float) Math.cos(a) * speed, shipVel.y + (float) Math.sin(a) * speed),
                    3f + (float) Math.random() * 3f, 0.9f, 0.3f + (float) Math.random() * 0.3f, SPARK);
        }
        engine.addSmokeParticle(point, drift(shipVel, 20f), 30f, 0.4f, 1.4f, DARK_SMOKE);
        State s = states.get(ship);
        if (s != null) {
            float now = engine.getTotalElapsedTime(false);
            float[] local = toLocal(ship, point);
            int pops = 1 + (int) (Math.random() * 2);
            for (int i = 0; i < pops; i++) {
                s.pending.add(new float[] {now + 0.05f + (float) Math.random() * 0.2f,
                        local[0] + jitter(14f), local[1] + jitter(14f), 0.5f, POP});
            }
        }
        ArmorEffects.floatText(engine, point, "ERA tiles lost", new Color(255, 130, 80), ship);
    }

    /** Tiles used and no self-reload pending. */
    public static boolean needsRearm(ShipAPI ship, int x, int y) {
        State s = states.get(ship);
        if (s == null || x < 0 || y < 0 || x >= s.charges.length || y >= s.charges[x].length) return false;
        return s.onHull[x][y] && s.charges[x][y] < s.max && s.reloadAt[x][y] < 0f;
    }

    /** {width, height}, or null without ERA. */
    public static int[] gridSize(ShipAPI ship) {
        State s = states.get(ship);
        if (s == null || s.charges.length == 0) return null;
        return new int[] {s.charges.length, s.charges[0].length};
    }

    /** Charge share of a cell, 0-1 (1 without ERA). */
    public static float chargeShare(ShipAPI ship, int x, int y) {
        State s = states.get(ship);
        if (s == null || x < 0 || y < 0 || x >= s.charges.length || y >= s.charges[x].length) return 1f;
        return (float) s.charges[x][y] / s.max;
    }

    /** Rearms the cells around (cx, cy). */
    public static void rearm(ShipAPI ship, int cx, int cy) {
        State s = states.get(ship);
        CombatEngineAPI engine = Global.getCombatEngine();
        if (s == null || engine == null) return;
        List<int[]> done = new ArrayList<int[]>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int x = cx + dx;
                int y = cy + dy;
                if (x < 0 || y < 0 || x >= s.charges.length || y >= s.charges[x].length) continue;
                if (s.charges[x][y] >= s.max || s.reloadAt[x][y] >= 0f) continue;
                s.charges[x][y] = s.max;
                s.armedArmor[x][y] = fittedArmor(ship, x, y);
                done.add(new int[] {x, y});
            }
        }
        if (!done.isEmpty()) showReload(engine, ship, s, done, engine.getTotalElapsedTime(false), null);
    }

    /** {charges left, charges when full}, or null without ERA. */
    public static int[] charges(ShipAPI ship) {
        State s = state(ship);
        if (s == null) return null;
        int left = 0;
        for (int x = 0; x < s.charges.length; x++) {
            for (int y = 0; y < s.charges[x].length; y++) {
                if (s.onHull[x][y]) left += s.charges[x][y];
            }
        }
        return new int[] {left, s.hullCells * s.max};
    }

    private static void tileBurst(CombatEngineAPI engine, ShipAPI ship, State s, Vector2f point, Object param,
            float shotDamage, boolean missile, boolean spent, boolean full) {
        Vector2f shipVel = ship.getVelocity();
        float scale = Math.max(0.7f, Math.min(2f, (float) Math.sqrt(Math.max(1f, shotDamage) / 150f)));
        if (missile) scale = Math.min(2.4f, scale * 1.3f);
        engine.addHitParticle(point, shipVel, 70f * scale, 1f, 0.08f, FLASH_CORE);
        if (!full) return;
        if (!engine.getViewport().isNearViewport(point, 300f)) return;

        // Outward direction
        float ox = 0f;
        float oy = 0f;
        if (param instanceof DamagingProjectileAPI) {
            Vector2f v = ((DamagingProjectileAPI) param).getVelocity();
            float len = v.length();
            if (len > 1f) {
                ox = -v.x / len;
                oy = -v.y / len;
            }
        }
        if (ox == 0f && oy == 0f) {
            float dx = point.x - ship.getLocation().x;
            float dy = point.y - ship.getLocation().y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            ox = len > 1f ? dx / len : 1f;
            oy = len > 1f ? dy / len : 0f;
        }
        float outAngle = (float) Math.toDegrees(Math.atan2(oy, ox));

        engine.addHitParticle(point, shipVel, 150f * scale, 0.5f, 0.18f, FLASH);
        Vector2f blastAt = new Vector2f(point.x + ox * 10f * scale, point.y + oy * 10f * scale);
        engine.spawnExplosion(blastAt, new Vector2f(shipVel.x + ox * 70f, shipVel.y + oy * 70f), BLAST, 40f * scale, 0.35f);
        engine.spawnExplosion(point, shipVel, FLASH, 22f * scale, 0.2f);
        if (missile) {
            // Flash and blast
            engine.addHitParticle(point, shipVel, 260f * scale, 0.35f, 0.22f, FLASH_CORE);
            engine.spawnExplosion(blastAt, new Vector2f(shipVel.x + ox * 40f, shipVel.y + oy * 40f), FLASH, 65f * scale, 0.45f);
        }

        // Spark cone
        int sparks = Math.round((missile ? 14 : 8) * scale);
        for (int i = 0; i < sparks; i++) {
            double a = Math.toRadians(outAngle + (Math.random() - 0.5) * 70.0);
            float speed = (220f + (float) Math.random() * 280f) * scale;
            Vector2f vel = new Vector2f(shipVel.x + (float) Math.cos(a) * speed, shipVel.y + (float) Math.sin(a) * speed);
            engine.addHitParticle(point, vel, 4f + (float) Math.random() * 5f, 1f,
                    0.25f + (float) Math.random() * 0.3f, SPARK);
        }
        engine.spawnDebrisSmall(point, shipVel, Math.round((missile ? 4 : 2) * scale), outAngle, 60f, 80f, 120f, 360f);
        Vector2f smokeAt = new Vector2f(point.x + ox * 18f * scale, point.y + oy * 18f * scale);
        engine.addSmokeParticle(smokeAt, new Vector2f(shipVel.x + ox * 35f, shipVel.y + oy * 35f),
                (35f + (float) Math.random() * 25f) * scale, 0.45f, 1.2f + (float) Math.random() * 0.8f, SMOKE);
        Global.getSoundPlayer().playSound("explosion_flak", missile ? 0.95f : 1.15f, missile ? 0.9f : 0.7f, point, shipVel);

        float now = engine.getTotalElapsedTime(false);
        float[] local = toLocal(ship, point);
        // Smoulder
        for (int i = 0; i < 5; i++) {
            s.pending.add(new float[] {now + 0.15f + i * 0.2f, local[0] + jitter(6f), local[1] + jitter(6f), scale, EMBERS});
        }
        if (missile) {
            // Neighbour pops
            int pops = 2 + (int) (Math.random() * 3);
            for (int i = 0; i < pops; i++) {
                s.pending.add(new float[] {now + 0.05f + (float) Math.random() * 0.3f,
                        local[0] + jitter(22f * scale), local[1] + jitter(22f * scale), 0.6f + (float) Math.random() * 0.4f, POP});
            }
        }
        if (spent) {
            // Last charge fizzle
            for (int i = 0; i < 6; i++) {
                double a = Math.random() * Math.PI * 2.0;
                float speed = 40f + (float) Math.random() * 60f;
                engine.addHitParticle(point, new Vector2f(shipVel.x + (float) Math.cos(a) * speed, shipVel.y + (float) Math.sin(a) * speed),
                        3f + (float) Math.random() * 2f, 0.8f, 0.6f + (float) Math.random() * 0.4f, EMBER);
            }
            engine.addSmokeParticle(point, drift(shipVel, 15f), 40f * scale, 0.6f, 2.2f, DARK_SMOKE);
            ArmorEffects.floatText(engine, point, "ERA spent", new Color(255, 150, 90), ship);
        }
    }

    private static void showReload(CombatEngineAPI engine, ShipAPI ship, State s, List<int[]> cells, float now, String label) {
        ArmorGridAPI grid = ship.getArmorGrid();
        if (grid == null) return;
        Vector2f centre = null;
        int shown = 0;
        for (int[] c : cells) {
            if (!s.onHull[c[0]][c[1]]) continue;
            Vector2f at = grid.getLocation(c[0], c[1]);
            if (at == null) continue;
            if (centre == null) centre = new Vector2f(at);
            if (shown++ >= 24) break;
            float[] local = toLocal(ship, at);
            s.pending.add(new float[] {now + shown * 0.03f, local[0], local[1], 1f, GLINTS});
        }
        if (centre == null || !engine.getViewport().isNearViewport(centre, 400f)) return;
        Global.getSoundPlayer().playSound("hit_glancing", 1.6f, 0.4f, centre, ship.getVelocity());
        if (label != null) ArmorEffects.floatText(engine, centre, label, GLINT, ship);
    }

    private static Vector2f toWorld(ShipAPI ship, float lx, float ly) {
        double a = Math.toRadians(ship.getFacing());
        return new Vector2f(ship.getLocation().x + (float) (lx * Math.cos(a) - ly * Math.sin(a)),
                ship.getLocation().y + (float) (lx * Math.sin(a) + ly * Math.cos(a)));
    }

    private static float[] toLocal(ShipAPI ship, Vector2f at) {
        float dx = at.x - ship.getLocation().x;
        float dy = at.y - ship.getLocation().y;
        double a = Math.toRadians(-ship.getFacing());
        return new float[] {(float) (dx * Math.cos(a) - dy * Math.sin(a)), (float) (dx * Math.sin(a) + dy * Math.cos(a))};
    }

    private static float jitter(float spread) {
        return ((float) Math.random() - 0.5f) * 2f * spread;
    }

    private static Vector2f drift(Vector2f vel, float speed) {
        double a = Math.random() * Math.PI * 2.0;
        float sp = speed * (0.3f + (float) Math.random() * 0.7f);
        return new Vector2f(vel.x + (float) Math.cos(a) * sp, vel.y + (float) Math.sin(a) * sp);
    }

    private static boolean overlapsHull(List<SegmentAPI> segments, Vector2f centre, float half) {
        if (contains(segments, centre)) return true;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4.0 * i;
            Vector2f p = new Vector2f(centre.x + (float) Math.cos(a) * half, centre.y + (float) Math.sin(a) * half);
            if (contains(segments, p)) return true;
        }
        return false;
    }

    private static boolean contains(List<SegmentAPI> segments, Vector2f point) {
        boolean inside = false;
        for (SegmentAPI segment : segments) {
            Vector2f a = segment.getP1();
            Vector2f b = segment.getP2();
            if ((a.y > point.y) != (b.y > point.y)) {
                float crossX = a.x + (point.y - a.y) * (b.x - a.x) / (b.y - a.y);
                if (point.x < crossX) inside = !inside;
            }
        }
        return inside;
    }

    /** Applies ERA's cut and plays the burst. */
    public static class Listener implements DamageTakenModifier {
        private final ShipAPI ship;

        public Listener(ShipAPI ship) {
            this.ship = ship;
        }

        @Override
        public String modifyDamageTaken(Object param, CombatEntityAPI target, DamageAPI damage, Vector2f point,
                boolean shieldHit) {
            if (shieldHit || target != ship) return null;
            if (damage.getType() != DamageType.HIGH_EXPLOSIVE) {
                damageTiles(ship, point, param);
                return null;
            }
            Decision d = decide(ship, point, param, damage.getType());
            if (d == null || d.mult >= 1f) return null;
            damage.getModifier().modifyMult(ID, d.mult);
            CombatEngineAPI engine = Global.getCombatEngine();
            State s = states.get(ship);
            if (engine != null && s != null) {
                float now = engine.getTotalElapsedTime(false);
                boolean full = s.lastBurst < 0f || now - s.lastBurst >= BURST_INTERVAL || d.missile || d.spent;
                if (full) s.lastBurst = now;
                tileBurst(engine, ship, s, point, param, damage.getDamage(), d.missile, d.spent, full);
            }
            return ID;
        }
    }
}
