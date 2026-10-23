package tco.ammo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.util.Misc;

import tco.AiUtil;
import tco.Msg;
import tco.TcoSettings;
import tco.ai.SupportRoles;
import tco.battle.DeathThroes;
import tco.fob.SupplyFob;
import tco.hangar.HangarCombatPlugin;
import tco.rts.FogOfWar;

/**
 * Ballistic magazines for every ship, and resupply: suppliers send a shuttle with a load to each
 * friendly ship in range that needs ammo. A shuttle shot down loses its load.
 */
public class AmmoCombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final String DONE_KEY = "tco_ammo_done";
    private static final float SCAN_INTERVAL = 0.5f;

    private CombatEngineAPI engine;
    private float scanTimer = 0f;

    private final Map<ShipAPI, Float> suppliers = new LinkedHashMap<ShipAPI, Float>();
    private final Map<WeaponAPI, Float> progress = new HashMap<WeaponAPI, Float>();
    private final Map<WeaponAPI, Clip> clips = new LinkedHashMap<WeaponAPI, Clip>();

    private static final float LAUNCH_INTERVAL = 1f;
    private static final float MIN_LOAD = 5f;
    private static final float OUTBOUND_TIMEOUT = 120f;
    private static final float DOCK_TIMEOUT = 90f;

    private enum Leg { OUTBOUND, LANDING, DOCKED, RETURN, LANDING_HOME }
    private static final float LANDING_TIMEOUT = 6f;

    private static class Shuttle {
        ShipAPI ship;
        ShuttleAI ai;
        ShipAPI source;
        ShipAPI client;
        float cargo;
        Leg leg = Leg.OUTBOUND;
        float legTime = 0f;
        // client's frame: x forward, y left
        final Vector2f dockLocal = new Vector2f();
        final Vector2f berth = new Vector2f();
    }

    private final List<Shuttle> shuttles = new ArrayList<Shuttle>();
    private final Map<ShipAPI, Shuttle> byClient = new HashMap<ShipAPI, Shuttle>();
    private float launchTimer = 0f;
    // if shuttles can't spawn, refill ships in range directly
    private boolean shuttlesBroken = false;

    private static class Clip {
        ShipAPI ship;
        float baseRegen;
        int reserve;
        int reserveMax;
        int lastAmmo;
        boolean dry;
    }

    public static final String ENGINE_KEY = "tco_ammo_plugin";

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
    }

    public static AmmoCombatPlugin get(CombatEngineAPI engine) {
        if (engine == null) return null;
        Object plugin = engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof AmmoCombatPlugin ? (AmmoCombatPlugin) plugin : null;
    }

    /** How full the ship's limited ammo is, 0 to 1, weighted by refill cost. 1 if it has none. */
    public float ammoLevel(ShipAPI ship) {
        float have = 0f;
        float full = 0f;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            float cost = AmmoRules.fullReloadCost(weapon);
            Clip clip = clips.get(weapon);
            if (clip != null) {
                int max = clip.reserveMax + Math.max(0, weapon.getMaxAmmo());
                if (max <= 0) continue;
                have += cost * (clip.reserve + Math.max(0, weapon.getAmmo())) / max;
                full += cost;
            } else if (AmmoRules.canResupply(weapon) && weapon.getMaxAmmo() > 0) {
                have += cost * weapon.getAmmo() / weapon.getMaxAmmo();
                full += cost;
            }
        }
        return full <= 0f ? 1f : have / full;
    }

    /** Stock left, or -1 if not a supplier. */
    public float stockOf(ShipAPI supplier) {
        Float stock = suppliers.get(supplier);
        return stock == null ? -1f : stock;
    }

    /** Resupply ships (not FOBs) in the battle. */
    public List<ShipAPI> supplyShips() {
        List<ShipAPI> out = new ArrayList<ShipAPI>();
        for (ShipAPI ship : suppliers.keySet()) {
            if (!SupplyFob.isFob(ship) && ship.isAlive() && !ship.isHulk()) out.add(ship);
        }
        return out;
    }

    /** Suppliers and FOBs with stock, any side. */
    public List<ShipAPI> stockedSuppliers() {
        List<ShipAPI> out = new ArrayList<ShipAPI>();
        for (Map.Entry<ShipAPI, Float> entry : suppliers.entrySet()) {
            ShipAPI ship = entry.getKey();
            if (entry.getValue() > MIN_LOAD && ship.isAlive() && !ship.isHulk()) out.add(ship);
        }
        return out;
    }

    public float supplyRange(ShipAPI supplier) {
        return rangeOf(supplier);
    }

    /** Rounds left behind a self-reloading gun, or -1. */
    public int reserveOf(WeaponAPI weapon) {
        Clip clip = clips.get(weapon);
        return clip == null ? -1 : clip.reserve;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine != null && engine.isPaused()) amount = 0f;
        if (engine == null || !TcoSettings.ammoEnabled) return;

        scanTimer -= amount;
        if (scanTimer <= 0f) {
            scanTimer = SCAN_INTERVAL;
            for (ShipAPI ship : engine.getShips()) {
                if (ship.getCustomData().containsKey(DONE_KEY)) continue;
                ship.setCustomData(DONE_KEY, Boolean.TRUE);
                setUp(ship);
            }
        }

        if (amount > 0f) {
            if (!clips.isEmpty()) trackClips();
            if (TcoSettings.ammoAiRation) rationAi();
            if (!suppliers.isEmpty()) resupply(amount);
            if (!shuttles.isEmpty()) updateShuttles(amount);
        }
    }

    private void trackClips() {
        Iterator<Map.Entry<WeaponAPI, Clip>> it = clips.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WeaponAPI, Clip> entry = it.next();
            WeaponAPI weapon = entry.getKey();
            Clip clip = entry.getValue();
            if (!clip.ship.isAlive() || clip.ship.isHulk()) {
                it.remove();
                continue;
            }

            int ammo = weapon.getAmmo();
            if (ammo > clip.lastAmmo) {
                int gained = ammo - clip.lastAmmo;
                if (gained > clip.reserve) {
                    // Reserve empty: undo the engine's reload
                    ammo = clip.lastAmmo + clip.reserve;
                    weapon.setAmmo(ammo);
                    gained = clip.reserve;
                }
                clip.reserve -= gained;
            }
            clip.lastAmmo = ammo;

            if (clip.reserve <= 0 && !clip.dry) {
                clip.dry = true;
                if (weapon.getAmmoTracker() != null) weapon.getAmmoTracker().setAmmoPerSecond(0f);
            }
        }
    }

    /** Share of a limited weapon's ammo left, magazine and reserve together; -1 if it isn't limited. */
    public float ammoFraction(WeaponAPI weapon) {
        Clip clip = clips.get(weapon);
        if (clip != null) {
            int max = clip.reserveMax + Math.max(0, weapon.getMaxAmmo());
            return max <= 0 ? -1f : (clip.reserve + Math.max(0, weapon.getAmmo())) / (float) max;
        }
        if (!AmmoRules.canResupply(weapon) || !weapon.usesAmmo() || weapon.getMaxAmmo() <= 0) return -1f;
        return weapon.getAmmo() / (float) weapon.getMaxAmmo();
    }

    /**
     * AI-flown ships running low hold their limited guns for good shots: close range, or a target that is
     * overloaded, venting or nearly dead. Point defence is left alone.
     */
    private void rationAi() {
        ShipAPI player = engine.getPlayerShip();
        for (ShipAPI ship : engine.getShips()) {
            if (!ship.isAlive() || ship.isHulk() || ship.isFighter() || ship.isDrone() || ship.isShuttlePod()) continue;
            if (AiUtil.playerFlying(engine, ship)) continue;
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                if (weapon.isDecorative() || weapon.hasAIHint(AIHints.PD) || weapon.hasAIHint(AIHints.PD_ONLY)) continue;
                float left = ammoFraction(weapon);
                if (left < 0f || left >= TcoSettings.ammoAiConserve) continue;
                if (!goodShot(ship, weapon, left)) weapon.setForceNoFireOneFrame(true);
            }
        }
    }

    private boolean goodShot(ShipAPI ship, WeaponAPI weapon, float left) {
        ShipAPI target = null;
        WeaponGroupAPI group = ship.getWeaponGroupFor(weapon);
        if (group != null && group.isAutofiring()) {
            AutofireAIPlugin autofire = group.getAutofirePlugin(weapon);
            if (autofire != null) target = autofire.getTargetShip();
        }
        if (target == null) target = ship.getShipTarget();
        if (target == null || !target.isAlive() || target.isHulk()) return false;
        boolean critical = left < TcoSettings.ammoAiCritical;
        // Last rounds aren't spent on fighters
        if (target.isFighter()) return !critical;
        if (target.getFluxTracker().isOverloadedOrVenting() || target.getHullLevel() < 0.35f) return true;
        float gap = Misc.getDistance(weapon.getLocation(), target.getLocation()) - target.getCollisionRadius();
        return gap <= weapon.getRange() * (critical ? 0.5f : 0.75f);
    }

    private boolean isExempt(ShipAPI ship) {
        if (ship.isFighter() || ship.isDrone() || ship.isShuttlePod() || ship.isHulk()) return true;
        ShipAPI root = ship.getParentStation() != null ? ship.getParentStation() : ship;
        if (SupplyFob.isFobPart(ship)) return true;
        return root.isStation() || (root.getHullSpec() != null
                && root.getHullSpec().getHints().contains(ShipTypeHints.STATION));
    }

    private void setUp(ShipAPI ship) {
        // Ballistics make less flux with ammo limits
        ship.getMutableStats().getBallisticWeaponFluxCostMod().modifyMult("tco_ammo", TcoSettings.ballisticFluxMult);
        if (AmmoRules.isShuttle(ship)) return;
        if (SupplyFob.isFob(ship)) {
            float capacity = AmmoRules.fobCapacity();
            suppliers.put(ship, capacity);
            ship.setCustomData(AmmoRules.SUPPLY_MAX_KEY, capacity);
            ship.setCustomData(AmmoRules.SUPPLY_KEY, capacity);
            return;
        }
        if (isExempt(ship)) return;

        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (!AmmoRules.shouldLimit(weapon)) continue;
            int magazine = AmmoRules.magazineFor(weapon, ship);
            weapon.setMaxAmmo(magazine);
            weapon.setAmmo(magazine);
        }
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (!AmmoRules.isClipFed(weapon) || clips.containsKey(weapon)) continue;
            Clip clip = new Clip();
            clip.ship = ship;
            clip.baseRegen = weapon.getAmmoPerSecond();
            clip.reserveMax = AmmoRules.reserveFor(weapon, ship);
            clip.reserve = clip.reserveMax;
            clip.lastAmmo = weapon.getAmmo();
            clips.put(weapon, clip);
        }

        if (SupportRoles.isSupplier(ship)) {
            float capacity = AmmoRules.supplyCapacity(ship.getHullSize());
            suppliers.put(ship, capacity);
            ship.setCustomData(AmmoRules.SUPPLY_MAX_KEY, capacity);
            ship.setCustomData(AmmoRules.SUPPLY_KEY, capacity);
        }
    }

    private void resupply(float amount) {
        boolean direct = shuttlesBroken || !TcoSettings.supplyShuttles;
        launchTimer -= amount;
        boolean launchNow = launchTimer <= 0f;
        if (launchNow) launchTimer = LAUNCH_INTERVAL;

        Iterator<Map.Entry<ShipAPI, Float>> it = suppliers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ShipAPI, Float> entry = it.next();
            ShipAPI supplier = entry.getKey();
            if (!supplier.isAlive() || supplier.isHulk()) {
                it.remove();
                continue;
            }
            float supply = entry.getValue();

            Object gain = supplier.getCustomData().get(AmmoRules.SUPPLY_GAIN_KEY);
            if (gain instanceof Float && (Float) gain > 0f) {
                Object max = supplier.getCustomData().get(AmmoRules.SUPPLY_MAX_KEY);
                float cap = max instanceof Float ? (Float) max : supply;
                supply = Math.min(cap, supply + (Float) gain);
                entry.setValue(supply);
                supplier.setCustomData(AmmoRules.SUPPLY_KEY, supply);
                supplier.setCustomData(AmmoRules.SUPPLY_GAIN_KEY, 0f);
            }
            if (supply <= 0f) continue;
            if (supplier.getFluxTracker().isOverloaded()) continue;
            if (!direct && !launchNow) continue;

            float before = supply;
            boolean fob = SupplyFob.isFob(supplier);
            float range = rangeOf(supplier);
            Vector2f loc = supplier.getLocation();

            for (ShipAPI ship : engine.getShips()) {
                if (supply <= 0f) break;
                if (ship == supplier || ship.getOwner() != supplier.getOwner()) continue;
                if (!ship.isAlive() || ship.isHulk() || ship.isFighter() || ship.isDrone()) continue;
                if (SupplyFob.isFobPart(ship) || AmmoRules.isShuttle(ship) || ship.isShuttlePod()) continue;
                if (ship.getCustomData().containsKey("tco_captured") || DeathThroes.isDying(ship)) continue;
                float dx = ship.getLocation().x - loc.x;
                float dy = ship.getLocation().y - loc.y;
                float reach = range + ship.getCollisionRadius();
                if (dx * dx + dy * dy > reach * reach) continue;

                if (direct) {
                    if (fob) supply = restock(ship, supply, amount);
                    for (WeaponAPI weapon : ship.getAllWeapons()) {
                        if (supply <= 0f) break;
                        supply = refill(weapon, supply, amount);
                    }
                    continue;
                }

                // One shuttle per client
                if (byClient.containsKey(ship)) continue;
                if (HangarCombatPlugin.isHeld(supplier)) break;
                float need = needOf(ship, fob);
                // Not worth a trip unless a gun is empty
                float minimum = hasEmptyWeapon(ship) ? 0.5f : MIN_LOAD;
                if (need < minimum) continue;
                float load = Math.min(TcoSettings.shuttleCapacity, Math.min(need, supply));
                if (load < minimum && load < supply) continue;
                if (launch(supplier, ship, load)) supply -= load;
                if (shuttlesBroken) break;
            }

            if (supply != before) {
                entry.setValue(supply);
                supplier.setCustomData(AmmoRules.SUPPLY_KEY, supply);
                if (supply <= 0f && supplier.getOwner() == 0 && engine.getCombatUI() != null) {
                    String name = fob ? "The " + SupplyFob.NAME : supplier.getName();
                    Msg.about(engine, supplier, name + " has run out of munitions.");
                }
            }
        }
    }

    private boolean hasEmptyWeapon(ShipAPI ship) {
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            Clip clip = clips.get(weapon);
            if (clip != null) {
                if (clip.reserveMax > 0 && clip.reserve <= 0 && weapon.getAmmo() <= 0) return true;
            } else if (AmmoRules.canResupply(weapon) && weapon.getMaxAmmo() > 0 && weapon.getAmmo() <= 0) {
                return true;
            }
        }
        return false;
    }

    private float needOf(ShipAPI ship, boolean fromFob) {
        float need = 0f;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            Clip clip = clips.get(weapon);
            if (clip != null) {
                if (clip.reserveMax > 0) {
                    need += AmmoRules.fullReloadCost(weapon) * (clip.reserveMax - clip.reserve) / clip.reserveMax;
                }
            } else if (AmmoRules.canResupply(weapon) && weapon.getMaxAmmo() > 0) {
                need += AmmoRules.fullReloadCost(weapon)
                        * (weapon.getMaxAmmo() - weapon.getAmmo()) / weapon.getMaxAmmo();
            }
        }
        if (fromFob && !SupplyFob.isFob(ship)) {
            Float stock = suppliers.get(ship);
            Object max = ship.getCustomData().get(AmmoRules.SUPPLY_MAX_KEY);
            if (stock != null && max instanceof Float) need += Math.max(0f, (Float) max - stock);
        }
        return need;
    }

    private boolean launch(ShipAPI source, ShipAPI client, float load) {
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(source.getOwner());
            boolean quiet = fleet.isSuppressDeploymentMessages();
            fleet.setSuppressDeploymentMessages(true);
            ShipAPI ship;
            try {
                Vector2f from = source.getLocation();
                Vector2f to = client.getLocation();
                float facing = (float) Math.toDegrees(Math.atan2(to.y - from.y, to.x - from.x));
                try {
                    ship = fleet.spawnShipOrWing(AmmoRules.SUPPLY_SHUTTLE_VARIANT, new Vector2f(from), facing);
                } catch (RuntimeException e) {
                    ship = fleet.spawnShipOrWing(AmmoRules.SHUTTLE_VARIANT, new Vector2f(from), facing);
                }
            } finally {
                fleet.setSuppressDeploymentMessages(quiet);
            }
            // Not a deployed fleet member
            fleet.removeDeployed(ship, false);
            ship.setCustomData(AmmoRules.SHUTTLE_KEY, Boolean.TRUE);
            ship.setCustomData(DONE_KEY, Boolean.TRUE);
            // Flies over ships but can still be shot
            ship.setCollisionClass(CollisionClass.FIGHTER);
            ship.setInvalidTransferCommandTarget(true);
            // Slow the stock shuttle to a hauler's pace
            ship.getMutableStats().getMaxSpeed().modifyMult(AmmoRules.SHUTTLE_KEY, TcoSettings.shuttleSpeedMult);
            ship.getMutableStats().getAcceleration().modifyMult(AmmoRules.SHUTTLE_KEY, TcoSettings.shuttleSpeedMult);

            Shuttle shuttle = new Shuttle();
            shuttle.ship = ship;
            shuttle.ai = new ShuttleAI(ship);
            shuttle.source = source;
            shuttle.client = client;
            shuttle.cargo = load;
            ship.setShipAI(shuttle.ai);
            takeOff(ship, source);
            shuttle.ai.flyTo(client.getLocation());
            shuttles.add(shuttle);
            byClient.put(client, shuttle);
            return true;
        } catch (RuntimeException e) {
            Global.getLogger(AmmoCombatPlugin.class).warn(
                    "TCO: could not launch a supply shuttle, falling back to direct resupply", e);
            shuttlesBroken = true;
            return false;
        }
    }

    private void updateShuttles(float amount) {
        Iterator<Shuttle> it = shuttles.iterator();
        while (it.hasNext()) {
            Shuttle shuttle = it.next();
            ShipAPI ship = shuttle.ship;
            ShipAPI client = shuttle.client;
            ShipAPI source = shuttle.source;

            if (!ship.isAlive() || ship.isHulk()) {
                // Shot down: load lost
                if (ship.getOwner() == 0 && shuttle.cargo > 0f && engine.getCombatUI() != null) {
                    Msg.send(engine, Msg.PLAYER, "A supply shuttle was destroyed with "
                            + Math.round(shuttle.cargo) + " munitions aboard.", "shuttle-lost", 6f);
                }
                byClient.remove(client);
                it.remove();
                continue;
            }
            if (!engine.isEntityInPlay(ship)) {
                // Removed after landing: load back to stock
                deposit(shuttle);
                byClient.remove(client);
                it.remove();
                continue;
            }

            boolean clientOk = client.isAlive() && !client.isHulk() && engine.isEntityInPlay(client);
            boolean sourceOk = source.isAlive() && !source.isHulk() && engine.isEntityInPlay(source);
            shuttle.legTime += amount;

            switch (shuttle.leg) {
                case OUTBOUND:
                    if (!clientOk || shuttle.legTime > OUTBOUND_TIMEOUT || HangarCombatPlugin.isHeld(source)) {
                        turnBack(shuttle);
                    } else {
                        shuttle.ai.flyTo(client.getLocation());
                        if (isNear(ship, client)) beginLanding(shuttle, client, Leg.LANDING);
                    }
                    break;

                case LANDING:
                    if (!clientOk) {
                        abortLanding(shuttle);
                        break;
                    }
                    ride(shuttle, client, amount);
                    if (ship.isFinishedLanding() || shuttle.legTime > LANDING_TIMEOUT) {
                        // Docked: hidden and untargetable
                        shuttle.leg = Leg.DOCKED;
                        shuttle.legTime = 0f;
                        ship.setCollisionClass(CollisionClass.NONE);
                        ship.setExtraAlphaMult(0f);
                    }
                    break;

                case DOCKED:
                    if (!clientOk) {
                        abortLanding(shuttle);
                        break;
                    }
                    ride(shuttle, client, amount);
                    boolean fromFob = SupplyFob.isFob(source);
                    for (WeaponAPI weapon : client.getAllWeapons()) {
                        if (shuttle.cargo <= 0f) break;
                        shuttle.cargo = refill(weapon, shuttle.cargo, amount);
                    }
                    if (fromFob && shuttle.cargo > 0f) shuttle.cargo = restock(client, shuttle.cargo, amount);

                    if (shuttle.cargo <= 0.01f || needOf(client, fromFob) < 0.5f
                            || shuttle.legTime > DOCK_TIMEOUT) {
                        ship.setCollisionClass(CollisionClass.FIGHTER);
                        ship.setExtraAlphaMult(1f);
                        takeOff(ship, client);
                        turnBack(shuttle);
                    }
                    break;

                case RETURN:
                    if (!sourceOk) {
                        engine.removeEntity(ship);
                        byClient.remove(client);
                        it.remove();
                        continue;
                    }
                    shuttle.ai.flyTo(source.getLocation());
                    if (isNear(ship, source)) beginLanding(shuttle, source, Leg.LANDING_HOME);
                    break;

                default:
                    if (sourceOk) ride(shuttle, source, amount);
                    if (!sourceOk || ship.isFinishedLanding() || shuttle.legTime > LANDING_TIMEOUT) {
                        if (sourceOk) deposit(shuttle);
                        engine.removeEntity(ship);
                        byClient.remove(client);
                        it.remove();
                        continue;
                    }
                    break;
            }
        }
    }

    private boolean isNear(ShipAPI shuttle, ShipAPI host) {
        float dx = shuttle.getLocation().x - host.getLocation().x;
        float dy = shuttle.getLocation().y - host.getLocation().y;
        float reach = host.getCollisionRadius() * 0.6f + 40f;
        return dx * dx + dy * dy < reach * reach;
    }

    private void beginLanding(Shuttle shuttle, ShipAPI host, Leg leg) {
        float dx = shuttle.ship.getLocation().x - host.getLocation().x;
        float dy = shuttle.ship.getLocation().y - host.getLocation().y;
        double f = Math.toRadians(host.getFacing());
        float cos = (float) Math.cos(f);
        float sin = (float) Math.sin(f);
        shuttle.dockLocal.set(dx * cos + dy * sin, -dx * sin + dy * cos);
        float side = shuttle.dockLocal.y >= 0f ? 1f : -1f;
        shuttle.berth.set(0f, side * host.getCollisionRadius() * 0.06f);

        shuttle.leg = leg;
        shuttle.legTime = 0f;
        shuttle.ai.hold();
        shuttle.ship.beginLandingAnimation(host);
    }

    private void takeOff(ShipAPI shuttle, ShipAPI host) {
        shuttle.setAnimatedLaunch();
        shuttle.setLaunchingShip(host);
    }

    private void abortLanding(Shuttle shuttle) {
        if (shuttle.ship.isLanding()) shuttle.ship.abortLanding();
        shuttle.ship.setCollisionClass(CollisionClass.FIGHTER);
        shuttle.ship.setExtraAlphaMult(1f);
        turnBack(shuttle);
    }

    private void ride(Shuttle shuttle, ShipAPI host, float amount) {
        ShipAPI ship = shuttle.ship;
        float blend = Math.min(1f, amount * 2.5f);
        shuttle.dockLocal.x += (shuttle.berth.x - shuttle.dockLocal.x) * blend;
        shuttle.dockLocal.y += (shuttle.berth.y - shuttle.dockLocal.y) * blend;
        double f = Math.toRadians(host.getFacing());
        float cos = (float) Math.cos(f);
        float sin = (float) Math.sin(f);
        ship.getLocation().set(
                host.getLocation().x + shuttle.dockLocal.x * cos - shuttle.dockLocal.y * sin,
                host.getLocation().y + shuttle.dockLocal.x * sin + shuttle.dockLocal.y * cos);
        ship.getVelocity().set(host.getVelocity());
        ship.setAngularVelocity(0f);
        float turn = (host.getFacing() - ship.getFacing()) % 360f;
        if (turn > 180f) turn -= 360f;
        else if (turn <= -180f) turn += 360f;
        ship.setFacing(ship.getFacing() + turn * Math.min(1f, amount * 3f));
    }

    private void deposit(Shuttle shuttle) {
        ShipAPI source = shuttle.source;
        Float stock = suppliers.get(source);
        if (stock == null || shuttle.cargo <= 0f) return;
        Object max = source.getCustomData().get(AmmoRules.SUPPLY_MAX_KEY);
        float cap = max instanceof Float ? (Float) max : stock + shuttle.cargo;
        float total = Math.min(cap, stock + shuttle.cargo);
        suppliers.put(source, total);
        source.setCustomData(AmmoRules.SUPPLY_KEY, total);
        shuttle.cargo = 0f;
    }

    private void turnBack(Shuttle shuttle) {
        shuttle.leg = Leg.RETURN;
        shuttle.legTime = 0f;
        shuttle.ai.flyTo(shuttle.source.getLocation());
    }

    private float rangeOf(ShipAPI supplier) {
        HullSize size = SupplyFob.isFob(supplier) ? HullSize.CAPITAL_SHIP : supplier.getHullSize();
        return AmmoRules.resupplyRange(size) + supplier.getCollisionRadius();
    }

    private float restock(ShipAPI ship, float supply, float amount) {
        Float current = suppliers.get(ship);
        Object max = ship.getCustomData().get(AmmoRules.SUPPLY_MAX_KEY);
        if (current == null || !(max instanceof Float) || SupplyFob.isFob(ship)) return supply;

        float moved = Math.min(TcoSettings.fobRestockPerSecond * amount, (Float) max - current);
        moved = Math.min(moved, supply);
        if (moved <= 0f) return supply;
        // Safe while iterating: existing key
        suppliers.put(ship, current + moved);
        ship.setCustomData(AmmoRules.SUPPLY_KEY, current + moved);
        return supply - moved;
    }

    private float refill(WeaponAPI weapon, float supply, float amount) {
        Clip clip = clips.get(weapon);
        if (clip != null) return refillReserve(weapon, clip, supply, amount);
        if (!AmmoRules.canResupply(weapon)) return supply;
        int max = weapon.getMaxAmmo();
        int ammo = weapon.getAmmo();
        if (max <= 0 || ammo >= max) {
            progress.remove(weapon);
            return supply;
        }

        Float owed = progress.get(weapon);
        float rounds = (owed == null ? 0f : owed) + max * amount / AmmoRules.fullReloadSeconds(weapon);
        int whole = Math.min((int) rounds, max - ammo);
        if (whole >= 1) {
            float costPerRound = AmmoRules.fullReloadCost(weapon) / max;
            if (costPerRound * whole > supply) whole = (int) (supply / costPerRound);
            if (whole < 1) return 0f;
            weapon.setAmmo(ammo + whole);
            supply -= costPerRound * whole;
            rounds -= whole;
            if (supply < 0.001f) supply = 0f;
        }
        progress.put(weapon, rounds);
        return supply;
    }

    private float refillReserve(WeaponAPI weapon, Clip clip, float supply, float amount) {
        if (clip.reserve >= clip.reserveMax) {
            progress.remove(weapon);
            return supply;
        }

        Float owed = progress.get(weapon);
        float rounds = (owed == null ? 0f : owed) + clip.reserveMax * amount / AmmoRules.fullReloadSeconds(weapon);
        int whole = Math.min((int) rounds, clip.reserveMax - clip.reserve);
        if (whole >= 1) {
            float costPerRound = AmmoRules.fullReloadCost(weapon) / clip.reserveMax;
            if (costPerRound * whole > supply) whole = (int) (supply / costPerRound);
            if (whole < 1) return 0f;
            clip.reserve += whole;
            supply -= costPerRound * whole;
            rounds -= whole;
            if (supply < 0.001f) supply = 0f;
            if (clip.dry) {
                clip.dry = false;
                if (weapon.getAmmoTracker() != null) weapon.getAmmoTracker().setAmmoPerSecond(clip.baseRegen);
            }
        }
        progress.put(weapon, rounds);
        return supply;
    }

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (engine == null || suppliers.isEmpty()) return;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(1.5f);

        for (Map.Entry<ShipAPI, Float> entry : suppliers.entrySet()) {
            ShipAPI ship = entry.getKey();
            if (!ship.isAlive() || entry.getValue() <= 0f || FogOfWar.isHidden(ship)) continue;
            float range = rangeOf(ship);
            Vector2f loc = ship.getLocation();
            if (!viewport.isNearViewport(loc, range)) continue;

            float capacity = SupplyFob.isFob(ship)
                    ? AmmoRules.fobCapacity() : AmmoRules.supplyCapacity(ship.getHullSize());
            float left = capacity > 0f ? Math.min(1f, entry.getValue() / capacity) : 0f;
            if (ship.getOwner() == 0) GL11.glColor4f(0.35f, 0.85f, 1f, 0.15f + 0.3f * left);
            else GL11.glColor4f(1f, 0.5f, 0.35f, 0.15f + 0.3f * left);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < 72; i++) {
                double a = Math.PI * 2.0 * i / 72.0;
                GL11.glVertex2f(loc.x + (float) Math.cos(a) * range, loc.y + (float) Math.sin(a) * range);
            }
            GL11.glEnd();
        }

        // Shuttle markers
        for (Shuttle shuttle : shuttles) {
            if (shuttle.leg == Leg.DOCKED || FogOfWar.isHidden(shuttle.ship)) continue;
            Vector2f loc = shuttle.ship.getLocation();
            if (!viewport.isNearViewport(loc, 100f)) continue;
            float alpha = shuttle.cargo > 0.01f ? 0.9f : 0.4f;
            if (shuttle.ship.getOwner() == 0) GL11.glColor4f(0.35f, 0.85f, 1f, alpha);
            else GL11.glColor4f(1f, 0.5f, 0.35f, alpha);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < 16; i++) {
                double a = Math.PI * 2.0 * i / 16.0;
                GL11.glVertex2f(loc.x + (float) Math.cos(a) * 35f, loc.y + (float) Math.sin(a) * 35f);
            }
            GL11.glEnd();
        }
        GL11.glPopAttrib();
    }
}
