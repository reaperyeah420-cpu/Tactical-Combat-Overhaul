package tco.repair;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BoundsAPI;
import com.fs.starfarer.api.combat.BoundsAPI.SegmentAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipEngineControllerAPI.ShipEngineAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.Msg;
import tco.TcoSettings;
import tco.hullmods.DamageControl;
import tco.ai.SupportRoles;
import tco.ammo.AmmoRules;
import tco.ammo.ShuttleAI;
import tco.battle.DeathThroes;
import tco.dock.RepairDock;
import tco.hangar.HangarCombatPlugin;
import tco.hullmods.ExplosiveReactiveArmor;
import tco.power.PowerCombatPlugin;
import tco.rts.FogOfWar;

/**
 * Fleet tenders and permanent damage. Knocked-out nozzles may stay out (capped per ship); cook-offs destroy
 * weapons.
 * Tenders send drones to damaged friendly ships in range; drones shot down are lost.
 */
public class RepairCombatPlugin extends BaseEveryFrameCombatPlugin {

    public static final String ENGINE_KEY = "tco_repair_plugin";
    public static final String HULLMOD_ID = "tco_repair_bay";
    private static final String DRONE_VARIANT = "tco_repair_drone_Hull";
    private static final String TENDER_KEY = "tco_tender_done";

    private static final float DISPATCH_INTERVAL = 1f;
    private static final float ENGINE_SCAN_INTERVAL = 0.25f;
    private static final float OUTBOUND_TIMEOUT = 90f;
    private static final float LANDING_TIMEOUT = 6f;
    private static final float ARMOR_JOB_SECONDS = 12f;
    private static final float ENGINE_JOB_SECONDS = 8f;
    private static final float WEAPON_RELIGHT_SECONDS = 8f;
    private static final float ENGINE_RELIGHT_SECONDS = 4f;

    private enum JobType { REACTOR, WEAPON, ENGINE, ARMOR, ERA }

    private static class Job {
        JobType type;
        WeaponAPI weapon;
        ShipEngineAPI engine;
        int cellX;
        int cellY;
        float needed;
        float work = 0f;
        float parts = 0f;
        boolean done = false;
        Object reservation;
    }

    private enum Leg { OUTBOUND, WORKING, RETURN, LANDING }

    private static class Drone {
        ShipAPI ship;
        ShuttleAI ai;
        ShipAPI tender;
        ShipAPI client;
        Job job;
        Leg leg = Leg.OUTBOUND;
        float legTime = 0f;
        float sparkTimer = 0f;
    }

    private CombatEngineAPI engine;
    private final Random random = new Random();

    private final Map<ShipAPI, Integer> dronesLeft = new LinkedHashMap<ShipAPI, Integer>();
    private final Map<ShipAPI, Float> partsLeft = new HashMap<ShipAPI, Float>();
    private final Set<ShipAPI> toldOutOfParts = new HashSet<ShipAPI>();
    private final List<Drone> drones = new ArrayList<Drone>();
    private final Map<ShipAPI, Integer> dronesOnClient = new HashMap<ShipAPI, Integer>();
    private final Set<Object> reserved = new HashSet<Object>();
    private final Map<ShipEngineAPI, Boolean> engineWasOut = new WeakHashMap<ShipEngineAPI, Boolean>();
    private final Map<String, Float> recentlyPatched = new HashMap<String, Float>();
    private static final float PATCH_COOLDOWN = 20f;
    /**
     * Repaired parts keep the game's "destroyed" flag, so the game won't relight them; this plugin does.
     * Value: seconds out (0 while working).
     */
    private final Map<Object, Float> repairedParts = new HashMap<Object, Float>();

    private float dispatchTimer = 0f;
    private float engineTimer = 0f;
    private float scanTimer = 0f;

    public static RepairCombatPlugin get(CombatEngineAPI engine) {
        if (engine == null) return null;
        Object plugin = engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof RepairCombatPlugin ? (RepairCombatPlugin) plugin : null;
    }

    /** A Repair Tender Bay, or an AI tender role. */
    public static boolean hasBay(ShipAPI ship) {
        return ship != null && SupportRoles.isTender(ship);
    }

    public static int dronesFor(HullSize size) {
        if (size == HullSize.CAPITAL_SHIP) return TcoSettings.repairDronesCapital;
        if (size == HullSize.CRUISER) return TcoSettings.repairDronesCruiser;
        if (size == HullSize.DESTROYER) return TcoSettings.repairDronesDestroyer;
        return TcoSettings.repairDronesFrigate;
    }

    public static float partsFor(HullSize size) {
        if (size == HullSize.CAPITAL_SHIP) return TcoSettings.repairPartsCapital;
        if (size == HullSize.CRUISER) return TcoSettings.repairPartsCruiser;
        if (size == HullSize.DESTROYER) return TcoSettings.repairPartsDestroyer;
        return TcoSettings.repairPartsFrigate;
    }

    public List<ShipAPI> tenders() {
        return new ArrayList<ShipAPI>(dronesLeft.keySet());
    }

    /** Has drones, parts and a working reactor. */
    public boolean canWork(ShipAPI tender) {
        if (!tender.isAlive() || tender.isHulk() || dronesLeft(tender) <= 0) return false;
        Float parts = partsLeft.get(tender);
        if (parts == null || parts < Math.min(TcoSettings.repairPartsArmor, TcoSettings.repairPartsWeaponSmall)) return false;
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        return power == null || !power.isMeltedDown(tender);
    }

    /** Destroyed weapons and nozzles a tender could fix. */
    public int brokenParts(ShipAPI ship) {
        int broken = 0;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (!weapon.isDecorative() && isDestroyed(weapon) && !repairedParts.containsKey(weapon)) broken++;
        }
        if (ship.getEngineController() != null) {
            for (ShipEngineAPI nozzle : ship.getEngineController().getShipEngines()) {
                if (isDestroyed(nozzle) && !repairedParts.containsKey(nozzle)) broken++;
            }
        }
        return broken;
    }

    /** Spare parts left, or -1 if not a tender. */
    public float partsLeft(ShipAPI ship) {
        Float parts = partsLeft.get(ship);
        return parts == null ? -1f : parts;
    }

    private static float partsCost(Job job) {
        if (job.type == JobType.REACTOR) return TcoSettings.repairPartsReactor;
        if (job.type == JobType.ENGINE) return TcoSettings.repairPartsEngine;
        if (job.type == JobType.ARMOR) return TcoSettings.repairPartsArmor;
        if (job.type == JobType.ERA) return TcoSettings.repairPartsEra;
        WeaponSize size = job.weapon.getSize();
        if (size == WeaponSize.LARGE) return TcoSettings.repairPartsWeaponLarge;
        if (size == WeaponSize.MEDIUM) return TcoSettings.repairPartsWeaponMedium;
        return TcoSettings.repairPartsWeaponSmall;
    }

    /** Drones allowed on one ship at a time. */
    public static int dronesPerShip(HullSize size) {
        return size == HullSize.CRUISER || size == HullSize.CAPITAL_SHIP ? 4 : 2;
    }

    /** By hull size, or the dock's own number. */
    public static int dronesOf(ShipAPI tender) {
        return RepairDock.isDock(tender) ? TcoSettings.dockDrones : dronesFor(tender.getHullSize());
    }

    public static float partsOf(ShipAPI tender) {
        return RepairDock.isDock(tender) ? TcoSettings.dockParts : partsFor(tender.getHullSize());
    }

    /** Drone reach from the tender's edge. */
    public static float rangeOf(ShipAPI tender) {
        return RepairDock.isDock(tender) ? TcoSettings.dockRange : rangeFor(tender.getHullSize());
    }

    public static float rangeFor(HullSize size) {
        return AmmoRules.resupplyRange(size) / Math.max(0.01f, TcoSettings.resupplyRangeMult) * TcoSettings.repairRangeMult;
    }

    /** Destroyed and out (repaired weapons keep the disabled flag). */
    public static boolean isDestroyed(WeaponAPI weapon) {
        return weapon.isPermanentlyDisabled() && weapon.isDisabled();
    }

    public static boolean isDestroyed(ShipEngineAPI nozzle) {
        return nozzle.isPermanentlyDisabled() && nozzle.isDisabled();
    }

    /** Marks a part destroyed for good, so it isn't relit. */
    public static void markDestroyed(CombatEngineAPI engine, Object part) {
        RepairCombatPlugin plugin = get(engine);
        if (plugin != null) plugin.repairedParts.remove(part);
    }

    /** Drones left, aboard or working, or -1 if not a tender. */
    public int dronesLeft(ShipAPI ship) {
        Integer aboard = dronesLeft.get(ship);
        if (aboard == null) return -1;
        int out = 0;
        for (Drone drone : drones) {
            if (drone.tender == ship) out++;
        }
        return aboard + out;
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
        if (engine == null || amount <= 0f || !TcoSettings.repairEnabled) return;

        scanTimer -= amount;
        if (scanTimer <= 0f) {
            scanTimer = 0.5f;
            for (ShipAPI ship : engine.getShips()) {
                if (ship.getCustomData().containsKey(TENDER_KEY)) continue;
                ship.setCustomData(TENDER_KEY, Boolean.TRUE);
                if ((hasBay(ship) || RepairDock.isDock(ship)) && !ship.isFighter() && !AmmoRules.isShuttle(ship)) {
                    dronesLeft.put(ship, dronesOf(ship));
                    partsLeft.put(ship, partsOf(ship));
                }
            }
        }

        engineTimer -= amount;
        if (engineTimer <= 0f) {
            engineTimer = ENGINE_SCAN_INTERVAL;
            makeEngineDamageStick();
            relightRepairedParts(ENGINE_SCAN_INTERVAL);
            fieldPatch(ENGINE_SCAN_INTERVAL);
        }

        if (!recentlyPatched.isEmpty()) {
            Iterator<Map.Entry<String, Float>> cooling = recentlyPatched.entrySet().iterator();
            while (cooling.hasNext()) {
                Map.Entry<String, Float> entry = cooling.next();
                float left = entry.getValue() - amount;
                if (left <= 0f) cooling.remove();
                else entry.setValue(left);
            }
        }

        dispatchTimer -= amount;
        if (dispatchTimer <= 0f) {
            dispatchTimer = DISPATCH_INTERVAL;
            dispatch();
        }

        updateDrones(amount);
        restockFromDocks(amount);
    }

    private void makeEngineDamageStick() {
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        for (ShipAPI ship : engine.getShips()) {
            if (!ship.isAlive() || ship.isHulk() || ship.isFighter() || ship.getEngineController() == null) continue;
            if (AmmoRules.isShuttle(ship)) continue;
            List<ShipEngineAPI> nozzles = ship.getEngineController().getShipEngines();
            if (nozzles.isEmpty()) continue;
            if (power != null && power.isMeltedDown(ship)) {
                for (ShipEngineAPI nozzle : nozzles) engineWasOut.put(nozzle, nozzle.isDisabled());
                continue;
            }
            int permanent = 0;
            for (ShipEngineAPI nozzle : nozzles) {
                if (isDestroyed(nozzle) && !repairedParts.containsKey(nozzle)) permanent++;
            }
            int cap = (int) Math.floor(nozzles.size() * TcoSettings.enginePermanentCap);
            for (ShipEngineAPI nozzle : nozzles) {
                boolean out = nozzle.isDisabled();
                Boolean before = engineWasOut.put(nozzle, out);
                if (!out || (before != null && before)) continue;
                if (nozzle.isPermanentlyDisabled() && !repairedParts.containsKey(nozzle)) continue;
                if (permanent < cap && random.nextFloat() < TcoSettings.enginePermanentChance) {
                    nozzle.disable(true);
                    repairedParts.remove(nozzle);
                    permanent++;
                }
            }
        }
    }

    // Automated Repair Unit: seconds spent on the one field patch, and ships that have used it this battle
    private final Map<ShipAPI, Float> patchProgress = new HashMap<ShipAPI, Float>();
    private final java.util.Set<ShipAPI> patched = new java.util.HashSet<ShipAPI>();

    /**
     * Ships with the game's Automated Repair Unit patch one destroyed weapon or engine back per battle, with no
     * tender, after repairUnitPatchSeconds with something broken.
     */
    private void fieldPatch(float elapsed) {
        if (TcoSettings.repairUnitPatchSeconds <= 0f) return;
        for (ShipAPI ship : engine.getShips()) {
            if (patched.contains(ship) || !DamageControl.has(ship) || !ship.isAlive() || ship.isHulk() || ship.isFighter()) continue;
            if (brokenParts(ship) <= 0) continue;
            Float done = patchProgress.get(ship);
            float now = (done == null ? 0f : done) + elapsed;
            if (now < TcoSettings.repairUnitPatchSeconds) {
                patchProgress.put(ship, now);
                continue;
            }
            patchProgress.remove(ship);
            if (fixOnePart(ship)) {
                patched.add(ship);
                if (ship.getOwner() == 0) {
                    Msg.about(engine, ship, ship.getName() + "'s repair crews patched a destroyed part back into service.");
                }
            }
        }
    }

    private void relightRepairedParts(float elapsed) {
        if (repairedParts.isEmpty()) return;
        Iterator<Map.Entry<Object, Float>> it = repairedParts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Object, Float> entry = it.next();
            Object part = entry.getKey();
            float out = entry.getValue() + elapsed;
            if (part instanceof WeaponAPI) {
                WeaponAPI weapon = (WeaponAPI) part;
                if (weapon.getShip() == null || !weapon.getShip().isAlive()) {
                    it.remove();
                } else if (!weapon.isDisabled() || weapon.isForceDisabled()) {
                    entry.setValue(0f);
                } else if (out >= WEAPON_RELIGHT_SECONDS) {
                    weapon.repair();
                    entry.setValue(0f);
                } else {
                    entry.setValue(out);
                }
            } else {
                ShipEngineAPI nozzle = (ShipEngineAPI) part;
                if (!nozzle.isDisabled()) {
                    entry.setValue(0f);
                } else if (out >= ENGINE_RELIGHT_SECONDS) {
                    nozzle.repair();
                    nozzle.setHitpoints(nozzle.getMaxHitpoints());
                    engineWasOut.put(nozzle, false);
                    entry.setValue(0f);
                } else {
                    entry.setValue(out);
                }
            }
        }
    }

    private void dispatch() {
        Iterator<Map.Entry<ShipAPI, Integer>> it = dronesLeft.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ShipAPI, Integer> entry = it.next();
            ShipAPI tender = entry.getKey();
            if (!tender.isAlive() || tender.isHulk() || !engine.isEntityInPlay(tender)) {
                it.remove();
                continue;
            }
            int left = entry.getValue();
            if (left <= 0 || tender.getFluxTracker().isOverloaded() || HangarCombatPlugin.isHeld(tender)) continue;
            PowerCombatPlugin tenderPower = PowerCombatPlugin.get(engine);
            if (tenderPower != null && tenderPower.isMeltedDown(tender)) continue;
            float parts = partsLeft.containsKey(tender) ? partsLeft.get(tender) : 0f;
            float partsBefore = parts;
            boolean shortOfParts = false;

            float range = rangeOf(tender) + tender.getCollisionRadius();
            Vector2f loc = tender.getLocation();
            for (ShipAPI ship : engine.getShips()) {
                if (left <= 0) break;
                if (ship == tender || ship.getOwner() != tender.getOwner()) continue;
                if (!ship.isAlive() || ship.isHulk() || ship.isFighter() || ship.isDrone() || ship.isShuttlePod()) continue;
                if (AmmoRules.isShuttle(ship) || ship.getCustomData().containsKey("tco_captured")) continue;
                if (DeathThroes.isDying(ship)) continue;
                float dx = ship.getLocation().x - loc.x;
                float dy = ship.getLocation().y - loc.y;
                float reach = range + ship.getCollisionRadius();
                if (dx * dx + dy * dy > reach * reach) continue;

                int working = dronesOnClient.containsKey(ship) ? dronesOnClient.get(ship) : 0;
                // Docks can put more drones on one ship
                int cap = dronesPerShip(ship.getHullSize())
                        * (RepairDock.isDock(tender) ? Math.max(1, TcoSettings.dockDronesPerShipMult) : 1);
                while (left > 0 && working < cap) {
                    Job job = findJob(ship);
                    if (job == null) break;
                    job.parts = partsCost(job);
                    if (job.parts > parts + 0.001f) {
                        shortOfParts = true;
                        break;
                    }
                    if (!launch(tender, ship, job)) {
                        left = 0;
                        break;
                    }
                    parts -= job.parts;
                    left--;
                    working++;
                }
            }
            if (left != entry.getValue()) entry.setValue(left);
            if (parts != partsBefore) partsLeft.put(tender, Math.max(0f, parts));
            if (shortOfParts && tender.getOwner() == 0 && toldOutOfParts.add(tender)) {
                Msg.about(engine, tender, tender.getName() + " is short of spare parts for the repairs needed.");
            }
        }
    }

    private Job findJob(ShipAPI ship) {
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        if (power != null && power.isMeltedDown(ship) && !reserved.contains(reactorKey(ship))) {
            Job job = new Job();
            job.type = JobType.REACTOR;
            HullSize size = ship.getHullSize();
            job.needed = size == HullSize.CAPITAL_SHIP ? TcoSettings.reactorRepairCapital
                    : size == HullSize.CRUISER ? TcoSettings.reactorRepairCruiser
                    : size == HullSize.DESTROYER ? TcoSettings.reactorRepairDestroyer : TcoSettings.reactorRepairFrigate;
            job.reservation = reactorKey(ship);
            return job;
        }
        if (ship.getEngineController() != null) {
            for (ShipEngineAPI nozzle : ship.getEngineController().getShipEngines()) {
                if (!isDestroyed(nozzle) || repairedParts.containsKey(nozzle) || reserved.contains(nozzle)) continue;
                Job job = new Job();
                job.type = JobType.ENGINE;
                job.engine = nozzle;
                job.needed = ENGINE_JOB_SECONDS;
                job.reservation = nozzle;
                return job;
            }
        }
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.isDecorative() || !isDestroyed(weapon) || repairedParts.containsKey(weapon) || reserved.contains(weapon)) continue;
            Job job = new Job();
            job.type = JobType.WEAPON;
            job.weapon = weapon;
            WeaponSize size = weapon.getSize();
            job.needed = size == WeaponSize.LARGE ? 15f : size == WeaponSize.MEDIUM ? 10f : 6f;
            job.reservation = weapon;
            return job;
        }

        // Worst armor cell not next to one being patched
        ArmorGridAPI grid = ship.getArmorGrid();
        if (grid == null || grid.getMaxArmorInCell() <= 0f) return null;
        Job armorJob = findArmorJob(ship, grid);
        if (armorJob != null) return armorJob;
        return findEraJob(ship);
    }

    private Job findEraJob(ShipAPI ship) {
        int[] size = ExplosiveReactiveArmor.gridSize(ship);
        if (size == null) return null;
        int bestX = -1;
        int bestY = -1;
        float lowest = 1f;
        for (int x = 0; x < size[0]; x++) {
            for (int y = 0; y < size[1]; y++) {
                if (!ExplosiveReactiveArmor.needsRearm(ship, x, y)) continue;
                float share = ExplosiveReactiveArmor.chargeShare(ship, x, y);
                if (share >= lowest || nearReservedEra(ship, x, y)) continue;
                if (recentlyPatched.containsKey(eraKey(ship, x, y))) continue;
                lowest = share;
                bestX = x;
                bestY = y;
            }
        }
        if (bestX < 0) return null;
        Job job = new Job();
        job.type = JobType.ERA;
        job.cellX = bestX;
        job.cellY = bestY;
        job.needed = TcoSettings.eraRearmSeconds;
        job.reservation = eraKey(ship, bestX, bestY);
        return job;
    }

    private String eraKey(ShipAPI ship, int x, int y) {
        return System.identityHashCode(ship) + ":era:" + x + ":" + y;
    }

    private boolean nearReservedEra(ShipAPI ship, int x, int y) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (reserved.contains(eraKey(ship, x + dx, y + dy))) return true;
            }
        }
        return false;
    }

    private Job findArmorJob(ShipAPI ship, ArmorGridAPI grid) {
        float[][] cells = grid.getGrid();
        float max = grid.getMaxArmorInCell();
        float limit = TcoSettings.armorRepairCap;
        int bestX = -1;
        int bestY = -1;
        float worst = limit * 0.9f;
        for (int x = 0; x < cells.length; x++) {
            for (int y = 0; y < cells[x].length; y++) {
                // Same measure as patching
                float fraction = grid.getArmorValue(x, y) / max;
                if (fraction >= worst || nearReserved(ship, x, y)) continue;
                if (recentlyPatched.containsKey(armorKey(ship, x, y))) continue;
                worst = fraction;
                bestX = x;
                bestY = y;
            }
        }
        if (bestX < 0) return null;
        Job job = new Job();
        job.type = JobType.ARMOR;
        job.cellX = bestX;
        job.cellY = bestY;
        job.needed = ARMOR_JOB_SECONDS;
        job.reservation = armorKey(ship, bestX, bestY);
        return job;
    }

    private String reactorKey(ShipAPI ship) {
        return System.identityHashCode(ship) + ":reactor";
    }

    private String armorKey(ShipAPI ship, int x, int y) {
        return System.identityHashCode(ship) + ":" + x + ":" + y;
    }

    private boolean nearReserved(ShipAPI ship, int x, int y) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (reserved.contains(armorKey(ship, x + dx, y + dy))) return true;
            }
        }
        return false;
    }

    private boolean launch(ShipAPI tender, ShipAPI client, Job job) {
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(tender.getOwner());
            boolean quiet = fleet.isSuppressDeploymentMessages();
            fleet.setSuppressDeploymentMessages(true);
            ShipAPI ship;
            try {
                Vector2f from = tender.getLocation();
                Vector2f to = client.getLocation();
                float facing = (float) Math.toDegrees(Math.atan2(to.y - from.y, to.x - from.x));
                ship = fleet.spawnShipOrWing(DRONE_VARIANT, new Vector2f(from), facing);
            } finally {
                fleet.setSuppressDeploymentMessages(quiet);
            }
            fleet.removeDeployed(ship, false);
            // Excluded from ammo, resupply, boarding and selection
            ship.setCustomData(AmmoRules.SHUTTLE_KEY, Boolean.TRUE);
            ship.setCustomData("tco_ammo_done", Boolean.TRUE);
            ship.setCustomData(TENDER_KEY, Boolean.TRUE);
            ship.setCollisionClass(CollisionClass.FIGHTER);
            ship.setInvalidTransferCommandTarget(true);
            ship.getMutableStats().getMaxSpeed().modifyMult(HULLMOD_ID, TcoSettings.repairDroneSpeedMult);

            Drone drone = new Drone();
            drone.ship = ship;
            drone.ai = new ShuttleAI(ship);
            drone.tender = tender;
            drone.client = client;
            drone.job = job;
            ship.setShipAI(drone.ai);
            ship.setAnimatedLaunch();
            ship.setLaunchingShip(tender);
            drone.ai.flyTo(workPoint(drone));
            drones.add(drone);
            reserved.add(job.reservation);
            dronesOnClient.put(client, (dronesOnClient.containsKey(client) ? dronesOnClient.get(client) : 0) + 1);
            return true;
        } catch (RuntimeException e) {
            Global.getLogger(RepairCombatPlugin.class).warn("TCO: could not launch a repair drone", e);
            return false;
        }
    }

    private Vector2f workPoint(Drone drone) {
        Job job = drone.job;
        if (job.type == JobType.REACTOR) {
            PowerCombatPlugin power = PowerCombatPlugin.get(engine);
            return power == null ? new Vector2f(drone.client.getLocation()) : power.reactorLocation(drone.client);
        }
        if (job.type == JobType.WEAPON) return new Vector2f(job.weapon.getLocation());
        // Keep off the exhaust
        if (job.type == JobType.ENGINE) return insideHull(drone.client, new Vector2f(job.engine.getLocation()));
        // Armor and ERA jobs
        ArmorGridAPI grid = drone.client.getArmorGrid();
        Vector2f at = grid == null ? null : grid.getLocation(job.cellX, job.cellY);
        if (at == null) return new Vector2f(drone.client.getLocation());
        return insideHull(drone.client, new Vector2f(at));
    }

    private Vector2f insideHull(ShipAPI ship, Vector2f point) {
        BoundsAPI bounds = ship.getExactBounds();
        if (bounds == null) return point;
        bounds.update(ship.getLocation(), ship.getFacing());
        List<SegmentAPI> segments = bounds.getSegments();
        Vector2f centre = ship.getLocation();
        for (int step = 0; step < 12 && !contains(segments, point); step++) {
            point.x += (centre.x - point.x) * 0.15f;
            point.y += (centre.y - point.y) * 0.15f;
        }
        return point;
    }

    private boolean contains(List<SegmentAPI> segments, Vector2f point) {
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

    private boolean stillNeeded(Drone drone) {
        Job job = drone.job;
        if (job.type == JobType.REACTOR) {
            PowerCombatPlugin power = PowerCombatPlugin.get(engine);
            return power != null && power.isMeltedDown(drone.client);
        }
        if (job.type == JobType.WEAPON) return isDestroyed(job.weapon) && !repairedParts.containsKey(job.weapon);
        if (job.type == JobType.ENGINE) return isDestroyed(job.engine) && !repairedParts.containsKey(job.engine);
        if (job.type == JobType.ERA) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (ExplosiveReactiveArmor.needsRearm(drone.client, job.cellX + dx, job.cellY + dy)) return true;
                }
            }
            return false;
        }
        return true;
    }

    private void release(Drone drone) {
        if (drone.job != null && drone.job.reservation != null) reserved.remove(drone.job.reservation);
        Integer working = dronesOnClient.get(drone.client);
        if (working != null) {
            if (working <= 1) dronesOnClient.remove(drone.client);
            else dronesOnClient.put(drone.client, working - 1);
        }
        drone.job = null;
    }

    private void updateDrones(float amount) {
        int shotDown = 0;
        Set<ShipAPI> lostFrom = new HashSet<ShipAPI>();
        Iterator<Drone> it = drones.iterator();
        while (it.hasNext()) {
            Drone drone = it.next();
            ShipAPI ship = drone.ship;
            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) {
                if (drone.leg != Leg.LANDING && drone.ship.getOwner() == 0) shotDown++;
                lostFrom.add(drone.tender);
                release(drone);
                it.remove();
                continue;
            }
            boolean clientOk = drone.client.isAlive() && !drone.client.isHulk() && engine.isEntityInPlay(drone.client);
            boolean tenderOk = drone.tender.isAlive() && !drone.tender.isHulk() && engine.isEntityInPlay(drone.tender);
            drone.legTime += amount;

            switch (drone.leg) {
                case OUTBOUND: {
                    if (!clientOk || !stillNeeded(drone) || drone.legTime > OUTBOUND_TIMEOUT || HangarCombatPlugin.isHeld(drone.tender)) {
                        goHome(drone);
                        break;
                    }
                    Vector2f point = workPoint(drone);
                    drone.ai.flyTo(point);
                    float dx = ship.getLocation().x - point.x;
                    float dy = ship.getLocation().y - point.y;
                    // Latch on when close, so fast ships aren't circled
                    float latch = drone.client.getCollisionRadius() + 80f;
                    if (dx * dx + dy * dy < latch * latch) {
                        drone.leg = Leg.WORKING;
                        drone.legTime = 0f;
                        drone.ai.hold();
                    }
                    break;
                }
                case WORKING: {
                    if (!clientOk || !stillNeeded(drone) || HangarCombatPlugin.isHeld(drone.tender)) {
                        goHome(drone);
                        break;
                    }
                    // Glide onto the spot and stay on it
                    Vector2f point = workPoint(drone);
                    Vector2f at = ship.getLocation();
                    float blend = Math.min(1f, amount * 4f);
                    at.set(at.x + (point.x - at.x) * blend, at.y + (point.y - at.y) * blend);
                    ship.getVelocity().set(drone.client.getVelocity());
                    ship.setFacing(drone.client.getFacing());
                    float ox = point.x - at.x;
                    float oy = point.y - at.y;
                    if (ox * ox + oy * oy > 15f * 15f) break;
                    drone.sparkTimer -= amount;
                    if (drone.sparkTimer <= 0f) {
                        drone.sparkTimer = 0.25f;
                        engine.addHitParticle(point, drone.client.getVelocity(), 14f + random.nextFloat() * 10f,
                                1f, 0.2f, drone.job.type == JobType.ERA ? new Color(255, 200, 120) : new Color(140, 220, 255));
                    }
                    float workBefore = drone.job.work;
                    drone.job.work += amount;
                    if (drone.job.type == JobType.ARMOR) {
                        patchArmor(drone.client, drone.job, amount);
                        // Refresh hull damage decals
                        if ((int) drone.job.work != (int) workBefore) drone.client.syncWithArmorGridState();
                    }
                    if (drone.job.work >= drone.job.needed) {
                        finish(drone);
                        goHome(drone);
                    }
                    break;
                }
                case RETURN: {
                    if (!tenderOk) {
                        engine.removeEntity(ship);
                        it.remove();
                        continue;
                    }
                    drone.ai.flyTo(drone.tender.getLocation());
                    float dx = ship.getLocation().x - drone.tender.getLocation().x;
                    float dy = ship.getLocation().y - drone.tender.getLocation().y;
                    float reach = drone.tender.getCollisionRadius() * 0.6f + 30f;
                    if (dx * dx + dy * dy < reach * reach) {
                        drone.leg = Leg.LANDING;
                        drone.legTime = 0f;
                        drone.ai.hold();
                        ship.beginLandingAnimation(drone.tender);
                    }
                    break;
                }
                default: {
                    if (tenderOk) {
                        ship.getLocation().set(drone.tender.getLocation());
                        ship.getVelocity().set(drone.tender.getVelocity());
                    }
                    if (!tenderOk || ship.isFinishedLanding() || drone.legTime > LANDING_TIMEOUT) {
                        // Back aboard
                        Integer aboard = dronesLeft.get(drone.tender);
                        if (tenderOk && aboard != null) dronesLeft.put(drone.tender, aboard + 1);
                        engine.removeEntity(ship);
                        it.remove();
                        continue;
                    }
                    break;
                }
            }
        }
        if (shotDown > 0) Msg.send(engine, Msg.PLAYER, shotDown == 1 ? "A repair drone was shot down." : shotDown + " repair drones were shot down.",
                "drones-lost", 6f);
        for (ShipAPI tender : lostFrom) {
            if (tender.getOwner() == 0 && tender.isAlive() && dronesLeft(tender) == 0) {
                Msg.about(engine, tender, tender.getName() + " has lost all of its repair drones.");
            }
        }
    }

    private void goHome(Drone drone) {
        if (drone.job != null && !drone.job.done && drone.job.work <= 0f) {
            Float parts = partsLeft.get(drone.tender);
            if (parts != null) partsLeft.put(drone.tender, parts + drone.job.parts);
        }
        if (drone.job != null) release(drone);
        drone.leg = Leg.RETURN;
        drone.legTime = 0f;
        drone.ai.flyTo(drone.tender.getLocation());
    }

    private void patchArmor(ShipAPI ship, Job job, float amount) {
        ArmorGridAPI grid = ship.getArmorGrid();
        if (grid == null) return;
        float max = grid.getMaxArmorInCell();
        float limit = max * TcoSettings.armorRepairCap;
        float step = max * TcoSettings.armorRepairRate * amount;
        float[][] cells = grid.getGrid();
        boolean any = false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                int x = job.cellX + dx;
                int y = job.cellY + dy;
                if (x < 0 || y < 0 || x >= cells.length || y >= cells[x].length) continue;
                float value = grid.getArmorValue(x, y);
                if (value >= limit) continue;
                grid.setArmorValue(x, y, Math.min(limit, value + step));
                any = true;
            }
        }
        if (!any) job.work = job.needed;
    }

    /** Adds parts and drones, capped at the starting stock. */
    public void addStock(ShipAPI tender, float parts, int drones) {
        Float have = partsLeft.get(tender);
        if (have != null && parts > 0f) partsLeft.put(tender, Math.min(partsOf(tender), have + parts));
        Integer aboard = dronesLeft.get(tender);
        if (aboard != null && drones > 0) {
            int room = dronesOf(tender) - dronesLeft(tender);
            if (room > 0) dronesLeft.put(tender, aboard + Math.min(room, drones));
        }
    }

    private float restockTick = 0f;
    private final Map<ShipAPI, Float> droneRestock = new HashMap<ShipAPI, Float>();

    private void restockFromDocks(float amount) {
        if (amount <= 0f) return;
        restockTick += amount;
        if (restockTick < 1f) return;
        restockTick -= 1f;
        List<ShipAPI> all = new ArrayList<ShipAPI>(dronesLeft.keySet());
        for (ShipAPI dock : all) {
            if (!RepairDock.isDock(dock) || !dock.isAlive() || dock.isHulk()) continue;
            for (ShipAPI tender : all) {
                if (tender == dock || RepairDock.isDock(tender) || tender.getOwner() != dock.getOwner()) continue;
                if (!tender.isAlive() || tender.isHulk()) continue;
                float dx = tender.getLocation().x - dock.getLocation().x;
                float dy = tender.getLocation().y - dock.getLocation().y;
                float reach = rangeOf(dock) + dock.getCollisionRadius() + tender.getCollisionRadius();
                if (dx * dx + dy * dy > reach * reach) continue;

                Float dockParts = partsLeft.get(dock);
                Float have = partsLeft.get(tender);
                if (dockParts != null && have != null) {
                    float move = Math.min(TcoSettings.dockRestockPartsPerSecond, Math.min(dockParts, partsOf(tender) - have));
                    if (move > 0f) {
                        partsLeft.put(dock, dockParts - move);
                        partsLeft.put(tender, have + move);
                        toldOutOfParts.remove(tender);
                    }
                }

                Integer dockAboard = dronesLeft.get(dock);
                Integer aboard = dronesLeft.get(tender);
                if (dockAboard != null && aboard != null && dockAboard > 0 && dronesLeft(tender) < dronesOf(tender)) {
                    float waited = (droneRestock.containsKey(tender) ? droneRestock.get(tender) : 0f) + 1f;
                    if (waited >= TcoSettings.dockDroneRestockSeconds) {
                        waited = 0f;
                        dronesLeft.put(dock, dockAboard - 1);
                        dronesLeft.put(tender, aboard + 1);
                    }
                    droneRestock.put(tender, waited);
                }
            }
        }
    }

    /** Restarts a melted-down reactor and relights its engines. */
    public void restartReactor(ShipAPI ship) {
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        if (power != null) power.restoreReactor(ship);
        if (ship.getEngineController() == null) return;
        for (ShipEngineAPI nozzle : ship.getEngineController().getShipEngines()) {
            if (isDestroyed(nozzle) && !repairedParts.containsKey(nozzle)) continue;
            nozzle.repair();
            nozzle.setHitpoints(nozzle.getMaxHitpoints());
            engineWasOut.put(nozzle, false);
        }
    }

    /** Fixes one destroyed weapon (first) or nozzle; false if none. */
    public boolean fixOnePart(ShipAPI ship) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.isDecorative() || !isDestroyed(weapon) || repairedParts.containsKey(weapon) || reserved.contains(weapon)) continue;
            int ammo = weapon.getAmmo();
            weapon.repair();
            if (weapon.usesAmmo()) weapon.setAmmo(Math.min(ammo, weapon.getAmmo()));
            repairedParts.put(weapon, 0f);
            return true;
        }
        if (ship.getEngineController() != null) {
            for (ShipEngineAPI nozzle : ship.getEngineController().getShipEngines()) {
                if (!isDestroyed(nozzle) || repairedParts.containsKey(nozzle) || reserved.contains(nozzle)) continue;
                nozzle.repair();
                nozzle.setHitpoints(nozzle.getMaxHitpoints());
                engineWasOut.put(nozzle, false);
                repairedParts.put(nozzle, 0f);
                return true;
            }
        }
        return false;
    }

    private void finish(Drone drone) {
        Job job = drone.job;
        job.done = true;
        if (job.type == JobType.REACTOR) {
            restartReactor(drone.client);
            return;
        }
        if (job.type == JobType.WEAPON) {
            WeaponAPI weapon = job.weapon;
            int ammo = weapon.getAmmo();
            weapon.repair();
            // Magazine lost with the weapon
            if (weapon.usesAmmo()) weapon.setAmmo(Math.min(ammo, weapon.getAmmo()));
            repairedParts.put(weapon, 0f);
            Global.getLogger(RepairCombatPlugin.class).info("TCO: repaired " + weapon.getId() + " on " + drone.client.getName()
                    + ": disabled=" + weapon.isDisabled() + " ammo=" + weapon.getAmmo() + "/" + weapon.getMaxAmmo());
            if (weapon.isDisabled()) {
                Global.getLogger(RepairCombatPlugin.class).warn("TCO: weapon " + weapon.getId() + " stayed disabled after repair");
            }
        } else if (job.type == JobType.ARMOR) {
            drone.client.syncWithArmorGridState();
            // Cooldown on this patch so drones move on
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    recentlyPatched.put(armorKey(drone.client, job.cellX + dx, job.cellY + dy), PATCH_COOLDOWN);
                }
            }
        } else if (job.type == JobType.ERA) {
            ExplosiveReactiveArmor.rearm(drone.client, job.cellX, job.cellY);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    recentlyPatched.put(eraKey(drone.client, job.cellX + dx, job.cellY + dy), PATCH_COOLDOWN);
                }
            }
        } else if (job.type == JobType.ENGINE) {
            job.engine.repair();
            job.engine.setHitpoints(job.engine.getMaxHitpoints());
            engineWasOut.put(job.engine, false);
            repairedParts.put(job.engine, 0f);
        }
    }


    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (engine == null || dronesLeft.isEmpty()) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(1.5f);
        for (Map.Entry<ShipAPI, Integer> entry : dronesLeft.entrySet()) {
            ShipAPI ship = entry.getKey();
            if (!ship.isAlive() || entry.getValue() <= 0 || FogOfWar.isHidden(ship)) continue;
            float range = rangeOf(ship) + ship.getCollisionRadius();
            Vector2f loc = ship.getLocation();
            if (!viewport.isNearViewport(loc, range)) continue;
            float left = (float) entry.getValue() / Math.max(1, dronesOf(ship));
            if (ship.getOwner() == 0) GL11.glColor4f(0.4f, 1f, 0.55f, 0.12f + 0.25f * left);
            else GL11.glColor4f(1f, 0.6f, 0.3f, 0.12f + 0.25f * left);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < 72; i++) {
                double a = Math.PI * 2.0 * i / 72.0;
                GL11.glVertex2f(loc.x + (float) Math.cos(a) * range, loc.y + (float) Math.sin(a) * range);
            }
            GL11.glEnd();
        }
        GL11.glPopAttrib();
    }
}
