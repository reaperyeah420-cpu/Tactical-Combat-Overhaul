package tco.boarding;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
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

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.PlayerFleetPersonnelTracker;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.util.Misc;

import tco.AiUtil;
import tco.BattleReport;
import tco.Msg;
import tco.TcoSettings;
import tco.ai.AiAwareness;
import tco.ai.SupportRoles;
import tco.ammo.AmmoRules;
import tco.ammo.ShuttleAI;
import tco.armor.Fires;
import tco.battle.DeathThroes;
import tco.fob.InertAI;
import tco.fob.SupplyFob;
import tco.hullmods.BreachingCharges;
import tco.hullmods.MilitiaLockers;
import tco.hullmods.SecurityForge;
import tco.hullmods.ShuttleBayUpgrade;
import tco.power.PowerCombatPlugin;
import tco.rts.FogOfWar;
import tco.rts.RtsCombatPlugin;

/**
 * Boarding: shuttles of marines fight a ship's garrison and crew aboard; a beaten ship changes sides.
 * Captured members go to BoardingResults, which adds them to the fleet afterwards.
 */
public class BoardingCombatPlugin extends BaseEveryFrameCombatPlugin {

    public static final String ENGINE_KEY = "tco_boarding_plugin";
    public static final String HULLMOD_ID = "tco_boarding_bay";
    public static final String CAPTURED_KEY = "tco_captured";
    // Set on a ship's first capture: the side that lost it may board it back once, while it flees
    private static final String RETAKE_BY_KEY = "tco_retake_by";
    private static final String RETAKEN_KEY = "tco_retaken";
    private static final String SHUTTLE_VARIANT = "tco_boarding_shuttle_Hull";
    // Threat fragment craft: a fighter, so point defence shoots at it
    private static final String FRAGMENT_WING = "tco_boarding_fragment_wing";
    private static final String CIVILIAN_HULLMOD = "civgrade";

    private static final float TICK = 1f;
    private static final float LANDING_TIMEOUT = 6f;
    private static final float OUTBOUND_TIMEOUT = 180f;
    private static final float MALFUNCTION_CHANCE = 0.08f;
    private static final float MIN_SQUAD = 4f;

    private enum Leg { OUTBOUND, LANDING, RETURN, LANDING_HOME }

    private static class Craft {
        ShipAPI ship;
        ShuttleAI ai;
        ShipAPI source;
        ShipAPI target;
        int marines;
        boolean playerOwned;
        boolean droids;
        // Threat fragments, not marines (ThreatBoarding)
        boolean fragments;
        boolean playerSide;
        Leg leg = Leg.OUTBOUND;
        float legTime = 0f;
        final Vector2f local = new Vector2f();
        final Vector2f berth = new Vector2f();
    }

    private static class Fight {
        ShipAPI ship;
        float attackers;
        float defenders;
        float crew;
        float crewStart;
        float playerShare;
        boolean playerDefends;
        boolean droidDefenders;
        float droidAttackers;
        float fragmentAttackers;
        float playerSide;
        final Map<ShipAPI, Float> sources = new HashMap<ShipAPI, Float>();
        float tick = 0f;
        float sinceSurrenderCheck = 0f;
        LabelAPI label;
        String labelText;
        List<Deck> decks;
        int taken = 1;
        float defendersStart;
        BoardOrder order = BoardOrder.STANDARD;
        float withdrawIn = -1f;
        float quality = 1f;
        String qualityName = "regular";
        float layoutMult = 1f;
        String layoutName = "standard";
        String captain = "steady";
        float yields = 1f;
        float desperation = 1f;
        float time = 0f;
        float nextEvent = 12f;
        float armoryUntil = -1f;
        float ambushUntil = -1f;
        float retakeFloor = 0f;
        String rigged;
        float riggedIn;
        boolean desperateDone;
        // time, text, +1/-1/0 for the boarders
        final List<Object[]> log = new ArrayList<Object[]>();
        LabelAPI[] logLabels;
        String[] logTexts;
        float secDroids;
        // Militia Lockers: the crew's fighting strength, in marines, counted inside defenders
        float militia;
        float militiaStart;
        // Breaching Charges: the attack bonus runs until this fight time; ships whose first wave has landed
        float breachUntil = -1f;
        final java.util.Set<ShipAPI> breachedBy = new java.util.HashSet<ShipAPI>();
    }

    public enum BoardOrder {
        STANDARD("Standard"), ASSAULT("Assault"), METHODICAL("Methodical"), SABOTAGE("Sabotage"), WITHDRAW("Withdraw");

        public final String label;

        BoardOrder(String label) {
            this.label = label;
        }
    }

    private enum DeckKind { ENTRY, BATTERIES, ENGINEERING, BRIDGE }

    /** A section of a boarded ship; a batteries deck holds its share of the guns. */
    private static class Deck {
        final DeckKind kind;
        final String name;
        final List<WeaponAPI> weapons = new ArrayList<WeaponAPI>();

        Deck(DeckKind kind, String name) {
            this.kind = kind;
            this.name = name;
        }
    }

    /** The player's shuttle after their ship was taken. */
    private static class Ejection {
        ShipAPI pod;
        ShuttleAI ai;
        ShipAPI dest;
        boolean pilot;
        boolean playerShip;
        float retarget = 0f;
    }

    private static class Prize {
        ShipAPI ship;
        ShuttleAI ai;
        float marines;
        float playerMarines;
        float sign;
    }

    private CombatEngineAPI engine;
    private final Random random = new Random();

    private boolean poolReady = false;
    private boolean campaignPool = false;
    private int poolStart = 0;
    private final Map<String, Integer> shipPools = new HashMap<String, Integer>();
    private int unassigned = 0;
    private final Set<String> lostShips = new HashSet<String>();
    private float lossScan = 0f;
    // +1 player deployed in the upper half, -1 lower, 0 unknown yet
    private float homeSign = 0f;
    private float marinesSafe = 0f;
    // Crew of the player's ships killed fighting as militia
    private float militiaLost = 0f;
    // Crew of the player's ships killed by Threat boarders who took them
    private float threatCrewKilled = 0f;
    private boolean settled = false;

    private final Map<ShipAPI, Float> garrisons = new HashMap<ShipAPI, Float>();
    private final Map<String, Integer> aiPools = new HashMap<String, Integer>();
    public final List<FleetMemberAPI> lostThisBattle = new ArrayList<FleetMemberAPI>();
    private float aiTimer = 0f;
    private Ejection ejection;
    private final Map<ShipAPI, Float> warned = new HashMap<ShipAPI, Float>();
    private final Map<ShipAPI, Float> cooldowns = new HashMap<ShipAPI, Float>();
    private final List<Craft> crafts = new ArrayList<Craft>();
    private final Map<ShipAPI, Fight> fights = new LinkedHashMap<ShipAPI, Fight>();
    private final List<Prize> prizes = new ArrayList<Prize>();
    public final List<FleetMemberAPI> capturedThisBattle = new ArrayList<FleetMemberAPI>();
    // Big warning across the screen, shown until bannerUntil (combat time)
    private String bannerText;
    private Color bannerColor;
    private float bannerUntil = -1f;
    private LabelAPI bannerLabel;
    private String bannerShown;
    private final Map<ShipAPI, LabelAPI> prizeLabels = new HashMap<ShipAPI, LabelAPI>();

    public static BoardingCombatPlugin get(CombatEngineAPI engine) {
        if (engine == null) return null;
        Object plugin = engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof BoardingCombatPlugin ? (BoardingCombatPlugin) plugin : null;
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        BoardingResults.captured.clear();
        BoardingResults.lost.clear();
        BoardingResults.retaken.clear();
        BoardingResults.newFlagship = null;
        BoardingResults.cores.clear();
        BoardingResults.disabled.clear();
        // Boarding off: not registered, so get() is null everywhere and nothing is settled after the battle
        if (!TcoSettings.boardingEnabled) {
            BoardingResults.lastPlugin = null;
            return;
        }
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
        BoardingResults.lastPlugin = this;
    }

    // ------------------------------------------------------------------ marines

    private void readyPool() {
        if (poolReady) return;
        poolReady = true;
        try {
            if (engine.isInCampaign() && !engine.isSimulation() && Global.getSector() != null) {
                CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
                if (fleet != null) {
                    poolStart = Math.max(0, fleet.getCargo().getMarines());
                    MarineAllocation.Result spread = MarineAllocation.compute(
                            fleet.getFleetData().getMembersListCopy(), poolStart);
                    shipPools.putAll(spread.assigned);
                    unassigned = spread.unassigned;
                    campaignPool = true;
                }
            }
        } catch (RuntimeException e) {
            Global.getLogger(BoardingCombatPlugin.class).warn("TCO: could not read the fleet's marines", e);
        }
    }

    private String keyOf(ShipAPI ship) {
        String id = ship.getFleetMemberId();
        return id != null ? id : "ship@" + System.identityHashCode(ship);
    }

    /** The player's own (not allied). */
    public static boolean isPlayerShip(ShipAPI ship) {
        return ship.getOriginalOwner() == 0 && !ship.isAlly();
    }

    private void setAboard(ShipAPI ship, int marines) {
        if (isPlayerShip(ship) && !isAutomated(ship)) shipPools.put(keyOf(ship), marines);
        else aiPools.put(keyOf(ship), marines);
    }

    /**
     * Automated by hullmod or tag (how AI Switch mods do it), or crewless; a ship given a crew requirement
     * counts as crewed.
     */
    public static boolean isAutomated(ShipAPI ship) {
        if (ship == null) return false;
        FleetMemberAPI member = ship.getFleetMember();
        if (member != null && member.getMinCrew() > 0f) return false;
        if (Misc.isAutomated(ship)) return true;
        return member != null && member.getMinCrew() <= 0f && member.getMaxCrew() <= 0f;
    }

    /** Droids by hull size; more with an S-modded bay. */
    public static int droidsFor(ShipAPI ship) {
        HullSize size = ship.getHullSize();
        float droids = size == HullSize.CAPITAL_SHIP ? TcoSettings.droidsCapital : size == HullSize.CRUISER ? TcoSettings.droidsCruiser
                : size == HullSize.DESTROYER ? TcoSettings.droidsDestroyer : TcoSettings.droidsFrigate;
        if (ship.getVariant() != null && ship.getVariant().getSMods().contains(HULLMOD_ID)) droids *= 1f + TcoSettings.droidSModBonus;
        return Math.round(droids);
    }

    private static boolean playerHasAutomatedSkill() {
        try {
            return Global.getSector() != null && Global.getSector().getPlayerStats() != null
                    && Global.getSector().getPlayerStats().getSkillLevel("automated_ships") > 0f;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private int rollComplement(ShipAPI ship) {
        int per = Math.max(1, TcoSettings.marinesPerShuttle);
        FleetMemberAPI member = ship.getFleetMember();
        int full = member != null ? MarineAllocation.capacity(member) : shuttlesFor(ship.getHullSize()) * per * 2;
        float roll = random.nextFloat();
        if (roll < 0.65f) return full;
        if (roll < 0.85f) return Math.max(0, full - (1 + random.nextInt(3)) * shuttlesFor(ship.getHullSize()) * per);
        return Math.round(full * (0.2f + random.nextFloat() * 0.3f));
    }

    public int marinesAboard(ShipAPI ship) {
        readyPool();
        String key = keyOf(ship);
        if (isAutomated(ship)) {
            // Droids: fixed complement
            Integer aboard = aiPools.get(key);
            if (aboard == null) {
                aboard = hasBay(ship) ? droidsFor(ship) : 0;
                aiPools.put(key, aboard);
            }
            return aboard;
        }
        if (!isPlayerShip(ship)) {
            Integer aboard = aiPools.get(key);
            if (aboard == null) {
                aboard = hasBay(ship) ? rollComplement(ship) : 0;
                aiPools.put(key, aboard);
            }
            return aboard;
        }
        Integer aboard = shipPools.get(key);
        if (aboard == null) {
            if (campaignPool || !hasBay(ship) || ship.getOwner() != 0) return 0;
            aboard = TcoSettings.boardingTestMarines;
            shipPools.put(key, aboard);
        }
        return aboard;
    }

    private void giveBack(Craft craft) {
        if (craft.marines <= 0) return;
        ShipAPI source = craft.source;
        if (craft.fragments) {
            ThreatBoarding.returnHome(craft.ship, source);
            craft.marines = 0;
            return;
        }
        // Droids only return to automated ships
        if (craft.droids && !isAutomated(source)) {
            craft.marines = 0;
            return;
        }
        Fight defending = fights.get(source);
        if (defending != null && defending.playerDefends && source.isAlive()) {
            // Home is being boarded: join the defence
            defending.defenders += craft.marines;
        } else if (source.isAlive() && !source.isHulk() && engine.isEntityInPlay(source)) {
            setAboard(source, marinesAboard(source) + craft.marines);
        } else if (craft.playerOwned) {
            unassigned += craft.marines;
        }
        craft.marines = 0;
    }

    private void scanLosses(float amount) {
        lossScan -= amount;
        if (lossScan > 0f) return;
        lossScan = 1f;
        for (ShipAPI ship : engine.getShips()) {
            if (!ship.isHulk() || ship.getOriginalOwner() != 0 || ship.isFighter()) continue;
            String key = keyOf(ship);
            Integer aboard = shipPools.get(key);
            if (aboard == null || aboard <= 0 || !lostShips.add(key)) continue;
            message(ship, aboard + " marines were lost with " + ship.getName() + ".");
        }
    }

    /**
     * Once per battle: removes lost marines from cargo. Also called from the campaign if the battle ended
     * abruptly.
     */
    public void settle() {
        if (settled) return;
        settled = true;
        try {
            storeSecurityDroids();
        } catch (RuntimeException e) {
            Global.getLogger(BoardingCombatPlugin.class).warn("TCO: could not save security droids", e);
        }
        if (!poolReady || !campaignPool) return;

        float alive = unassigned + marinesSafe;
        for (Map.Entry<String, Integer> entry : shipPools.entrySet()) {
            if (!lostShips.contains(entry.getKey())) alive += entry.getValue();
        }
        for (Prize prize : prizes) alive += prize.playerMarines;
        for (Craft craft : crafts) {
            if (craft.playerOwned) alive += craft.marines;
        }
        // Marines still fighting aboard count as recovered
        for (Fight fight : fights.values()) alive += fight.playerShare;
        int lost = Math.max(0, poolStart - Math.round(alive));
        Global.getLogger(BoardingCombatPlugin.class).info("TCO boarding: " + poolStart + " marines at start, "
                + lost + " lost, " + capturedThisBattle.size() + " ships captured");
        if (lost > 0 || !capturedThisBattle.isEmpty()) {
            BattleReport.add("Boarding: " + lost + (lost == 1 ? " marine" : " marines")
                    + " killed in boarding actions and defending against enemy boarders"
                    + (capturedThisBattle.isEmpty() ? "" : "; " + capturedThisBattle.size()
                            + (capturedThisBattle.size() == 1 ? " ship" : " ships") + " captured") + ".");
        }
        if (lost > 0) {
            try {
                CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
                if (fleet != null) fleet.getCargo().removeMarines(Math.min(lost, fleet.getCargo().getMarines()));
            } catch (RuntimeException e) {
                Global.getLogger(BoardingCombatPlugin.class).warn("TCO: could not apply marine losses", e);
            }
        }
        int scoured = Math.round(threatCrewKilled);
        if (scoured > 0) {
            BattleReport.add("Threat boarders: " + scoured + " crew killed aboard the ships they took.");
            try {
                CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
                if (fleet != null) fleet.getCargo().removeCrew(Math.min(scoured, fleet.getCargo().getCrew()));
            } catch (RuntimeException e) {
                Global.getLogger(BoardingCombatPlugin.class).warn("TCO: could not apply crew killed by the Threat", e);
            }
        }
        int militia = Math.round(militiaLost);
        if (militia > 0) {
            BattleReport.add("Militia: " + militia + " crew killed defending against boarders.");
            try {
                CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
                if (fleet != null) fleet.getCargo().removeCrew(Math.min(militia, fleet.getCargo().getCrew()));
            } catch (RuntimeException e) {
                Global.getLogger(BoardingCombatPlugin.class).warn("TCO: could not apply militia losses", e);
            }
        }
        gainExperience(lost);
    }

    // ------------------------------------------------------------------ rules

    public static boolean hasBay(ShipAPI ship) {
        return ship != null && SupportRoles.isBoarder(ship);
    }

    public static int perShuttle(ShipAPI source) {
        int per = Math.max(1, TcoSettings.marinesPerShuttle);
        if (source != null && source.getVariant() != null && source.getVariant().hasHullMod(ShuttleBayUpgrade.HIGH_CAPACITY)) {
            per = Math.max(per, Math.round(per * TcoSettings.hcShuttleMarineMult));
        }
        return per;
    }

    public static int shuttlesPerWave(ShipAPI source) {
        int count = shuttlesFor(source.getHullSize());
        if (source.getVariant() != null && source.getVariant().hasHullMod(ShuttleBayUpgrade.LARGE_BAY)) {
            count = Math.max(count, Math.round(count * TcoSettings.largeBayShuttleMult)) + TcoSettings.efficientBayExtraShuttles;
        }
        return count;
    }

    public static float waveCooldown(ShipAPI source) {
        float cooldown = TcoSettings.boardingWaveCooldown;
        if (source.getVariant() != null && source.getVariant().hasHullMod(ShuttleBayUpgrade.LARGE_BAY)) {
            cooldown *= TcoSettings.efficientBayCooldownMult;
        }
        return cooldown;
    }

    /** Boarding shuttle hull, armour and shield flux x boardingShuttleToughness. */
    private static void toughen(ShipAPI ship) {
        float m = TcoSettings.boardingShuttleToughness;
        if (m == 1f) return;
        ship.setMaxHitpoints(ship.getMaxHitpoints() * m);
        ship.setHitpoints(ship.getMaxHitpoints());
        ship.getMutableStats().getFluxCapacity().modifyMult(HULLMOD_ID, m);
        ArmorGridAPI grid = ship.getArmorGrid();
        if (grid == null) return;
        float[][] cells = grid.getGrid();
        for (int x = 0; x < cells.length; x++) {
            for (int y = 0; y < cells[x].length; y++) grid.setArmorValue(x, y, cells[x][y] * m);
        }
    }

    public static int shuttlesFor(HullSize size) {
        if (size == HullSize.CAPITAL_SHIP) return 16;
        if (size == HullSize.CRUISER) return 8;
        if (size == HullSize.DESTROYER) return 4;
        return 2;
    }

    /** Live enemy warship or civilian; not fighters, stations, modules, FOBs or shuttles. */
    public boolean isBoardable(ShipAPI target, int attackerOwner) {
        if (target == null || !target.isAlive() || target.isHulk() || !engine.isEntityInPlay(target)) return false;
        if (target.getOwner() == attackerOwner || target.getOwner() == 100) return false;
        if (target.isFighter() || target.isDrone() || target.isShuttlePod() || target.isPiece()) return false;
        if (target.isStation() || target.isStationModule()) return false;
        if (SupplyFob.isFobPart(target) || AmmoRules.isShuttle(target)) return false;
        if (DeathThroes.isDying(target)) return false;
        if (target.getCustomData().containsKey(CAPTURED_KEY)) return canRetake(target, attackerOwner);
        return true;
    }

    /** A captured ship still on its way off the map, boardable once by the side it was taken from. */
    private boolean canRetake(ShipAPI ship, int side) {
        Object by = ship.getCustomData().get(RETAKE_BY_KEY);
        if (!(by instanceof Integer) || (Integer) by != side || ship.getCustomData().containsKey(RETAKEN_KEY)) return false;
        for (Prize prize : prizes) {
            if (prize.ship == ship) return true;
        }
        return false;
    }

    /** Recalls shuttles not yet aboard; returns marines coming back. */
    public int recall(ShipAPI source) {
        int marines = 0;
        for (Craft craft : crafts) {
            if (craft.source != source) continue;
            if (craft.leg != Leg.OUTBOUND && craft.leg != Leg.LANDING) continue;
            if (craft.leg == Leg.LANDING && craft.ship.isLanding()) craft.ship.abortLanding();
            craft.leg = Leg.RETURN;
            craft.legTime = 0f;
            marines += craft.marines;
        }
        return marines;
    }

    public float cooldownOf(ShipAPI source) {
        Float left = cooldowns.get(source);
        return left == null ? 0f : Math.max(0f, left);
    }

    private float garrisonOf(ShipAPI ship) {
        Float known = garrisons.get(ship);
        if (known != null) return known;
        float marines = isAutomated(ship) ? droidsFor(ship) : rollGarrison(ship);
        garrisons.put(ship, marines);
        return marines;
    }

    private float expectedGarrison(ShipAPI ship) {
        Float known = garrisons.get(ship);
        if (known != null) return known;
        HullSize size = ship.getHullSize();
        float mid = size == HullSize.CAPITAL_SHIP ? 160f : size == HullSize.CRUISER ? 100f
                : size == HullSize.DESTROYER ? 47f : 12f;
        if (ship.getVariant() != null && ship.getVariant().hasHullMod(CIVILIAN_HULLMOD)) mid = Math.max(5f, mid / 3f) * 0.5f;
        return mid * (0.4f + 0.6f * Math.max(0f, Math.min(1f, ship.getHullLevel())));
    }

    private float rollGarrison(ShipAPI ship) {

        int min;
        int max;
        HullSize size = ship.getHullSize();
        if (size == HullSize.CAPITAL_SHIP) {
            min = 100;
            max = 220;
        } else if (size == HullSize.CRUISER) {
            min = 80;
            max = 120;
        } else if (size == HullSize.DESTROYER) {
            min = 35;
            max = 60;
        } else {
            min = 5;
            max = 20;
        }
        float marines = min + random.nextInt(max - min + 1);
        if (ship.getVariant() != null && ship.getVariant().hasHullMod(CIVILIAN_HULLMOD)) {
            marines = random.nextBoolean() ? 0f : Math.max(5f, Math.round(marines / 3f));
        }
        return marines;
    }

    private float crewOf(ShipAPI ship) {
        if (isAutomated(ship)) return 0f;
        FleetMemberAPI member = ship.getFleetMember();
        if (member != null && member.getMinCrew() > 0f) return member.getMinCrew();
        HullSize size = ship.getHullSize();
        if (size == HullSize.CAPITAL_SHIP) return 400f;
        if (size == HullSize.CRUISER) return 150f;
        if (size == HullSize.DESTROYER) return 50f;
        return 15f;
    }

    // ------------------------------------------------------------------ launching

    /** Launches a wave and returns the message line. Caller checks range and cooldown. */
    public String launchWave(ShipAPI source, ShipAPI target) {
        readyPool();
        if (ThreatBoarding.isThreat(source)) return launchFragments(source, target);
        if (!hasBay(source)) return source.getName() + " has no Boarding Shuttle Bay.";
        if (!isBoardable(target, source.getOwner())) return "That ship cannot be boarded.";
        Fight underAttack = fights.get(source);
        if (underAttack != null && underAttack.playerDefends) return source.getName() + "'s marines are busy repelling boarders.";

        int per = perShuttle(source);
        int aboard = marinesAboard(source);
        // Full shuttles; the last takes the remainder
        int count = Math.min(shuttlesPerWave(source), (aboard + per - 1) / per);
        String troops = isAutomated(source) ? "droids" : "marines";
        if (count <= 0 || aboard <= 0) return source.getName() + " has no " + troops + " aboard to send.";

        int launched = 0;
        int sent = 0;
        for (int i = 0; i < count && aboard > 0; i++) {
            int load = Math.min(per, aboard);
            if (!launch(source, target, load)) break;
            aboard -= load;
            sent += load;
            launched++;
        }
        setAboard(source, aboard);
        if (launched == 0) return "Boarding shuttles could not be launched.";
        cooldowns.put(source, waveCooldown(source));
        return source.getName() + " launched " + launched + " boarding shuttles (" + sent
                + " " + troops + "). " + aboard + " " + troops + " left aboard.";
    }

    /** A Threat ship's wave: craft carrying fragments of its swarm (ThreatBoarding). */
    private String launchFragments(ShipAPI source, ShipAPI target) {
        if (!isBoardable(target, source.getOwner())) return "That ship cannot be boarded.";
        int per = Math.max(1, TcoSettings.threatFragmentsPerCraft);
        int count = Math.min(shuttlesPerWave(source), ThreatBoarding.spare(source) / per);
        if (count <= 0) return source.getName() + " has too few fragments to send.";
        int launched = 0;
        int sent = 0;
        for (int i = 0; i < count; i++) {
            int carried = launchFragmentCraft(source, target, per);
            if (carried <= 0) break;
            launched++;
            sent += carried;
        }
        if (launched == 0) return "No fragments could be sent.";
        cooldowns.put(source, Math.max(1f, TcoSettings.threatWaveCooldown));
        return source.getName() + " sent " + sent + " fragments to board " + target.getName() + ".";
    }

    /** One invisible craft wearing a swarm of fragments; how many fragments it carries (0: none launched). */
    private int launchFragmentCraft(ShipAPI source, ShipAPI target, int wanted) {
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(source.getOwner());
            boolean quiet = fleet.isSuppressDeploymentMessages();
            fleet.setSuppressDeploymentMessages(true);
            ShipAPI ship;
            try {
                Vector2f from = source.getLocation();
                Vector2f to = target.getLocation();
                float facing = (float) Math.toDegrees(Math.atan2(to.y - from.y, to.x - from.x));
                double spread = random.nextDouble() * Math.PI * 2.0;
                float out = source.getCollisionRadius() * 0.5f;
                Vector2f at = new Vector2f(from.x + (float) Math.cos(spread) * out, from.y + (float) Math.sin(spread) * out);
                ship = fleet.spawnShipOrWing(FRAGMENT_WING, at, facing);
            } finally {
                fleet.setSuppressDeploymentMessages(quiet);
            }
            if (ship.getWing() != null) {
                ship.getWing().setSourceShip(source);
                fleet.removeDeployed(ship.getWing(), false);
            } else {
                fleet.removeDeployed(ship, false);
            }
            // The swarm is what shows
            ship.setDoNotRender(true);
            ship.setExplosionScale(0f);
            ship.setHulkChanceOverride(0f);
            ship.setCustomData(AmmoRules.SHUTTLE_KEY, Boolean.TRUE);
            ship.setCustomData("tco_ammo_done", Boolean.TRUE);
            ship.setCollisionClass(CollisionClass.FIGHTER);
            ship.setInvalidTransferCommandTarget(true);
            ship.setHoldFire(true);
            int carried = ThreatBoarding.fill(ship, source, wanted);
            if (carried <= 0) {
                engine.removeEntity(ship);
                return 0;
            }
            ThreatBoarding.weaken(ship, carried);

            Craft craft = new Craft();
            craft.ship = ship;
            craft.ai = new ShuttleAI(ship);
            craft.source = source;
            craft.target = target;
            craft.marines = carried;
            craft.fragments = true;
            craft.playerSide = isPlayerShip(source);
            ship.setShipAI(craft.ai);
            craft.ai.flyTo(target.getLocation());
            crafts.add(craft);
            return carried;
        } catch (RuntimeException e) {
            Global.getLogger(BoardingCombatPlugin.class).warn("TCO: could not launch Threat fragments", e);
            return 0;
        }
    }

    private boolean launch(ShipAPI source, ShipAPI target, int marines) {
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(source.getOwner());
            boolean quiet = fleet.isSuppressDeploymentMessages();
            fleet.setSuppressDeploymentMessages(true);
            ShipAPI ship;
            try {
                Vector2f from = source.getLocation();
                Vector2f to = target.getLocation();
                float facing = (float) Math.toDegrees(Math.atan2(to.y - from.y, to.x - from.x));
                double spread = random.nextDouble() * Math.PI * 2.0;
                float out = source.getCollisionRadius() * 0.35f;
                Vector2f at = new Vector2f(from.x + (float) Math.cos(spread) * out,
                        from.y + (float) Math.sin(spread) * out);
                ship = fleet.spawnShipOrWing(SHUTTLE_VARIANT, at, facing);
            } finally {
                fleet.setSuppressDeploymentMessages(quiet);
            }
            fleet.removeDeployed(ship, false);
            // Excluded from ammo, resupply and selection
            ship.setCustomData(AmmoRules.SHUTTLE_KEY, Boolean.TRUE);
            ship.setCustomData("tco_ammo_done", Boolean.TRUE);
            ship.setCollisionClass(CollisionClass.FIGHTER);
            ship.setInvalidTransferCommandTarget(true);
            ship.getMutableStats().getMaxSpeed().modifyMult(HULLMOD_ID, TcoSettings.boardingShuttleSpeedMult);
            ship.getMutableStats().getAcceleration().modifyMult(HULLMOD_ID, TcoSettings.boardingShuttleSpeedMult);
            toughen(ship);
            // High-Capacity Shuttles
            if (source.getVariant() != null && source.getVariant().hasHullMod(ShuttleBayUpgrade.HIGH_CAPACITY)
                    && ship.getArmorGrid() != null) {
                ArmorGridAPI grid = ship.getArmorGrid();
                float[][] cells = grid.getGrid();
                for (int x = 0; x < cells.length; x++) {
                    for (int y = 0; y < cells[x].length; y++) {
                        grid.setArmorValue(x, y, cells[x][y] * TcoSettings.hcShuttleArmorMult);
                    }
                }
            }

            Craft craft = new Craft();
            craft.ship = ship;
            craft.ai = new ShuttleAI(ship);
            craft.ai.setRaiseShield(true);
            craft.source = source;
            craft.target = target;
            craft.marines = marines;
            craft.droids = isAutomated(source);
            craft.playerSide = isPlayerShip(source);
            craft.playerOwned = craft.playerSide && !craft.droids;
            ship.setShipAI(craft.ai);
            ship.setAnimatedLaunch();
            ship.setLaunchingShip(source);
            craft.ai.flyTo(target.getLocation());
            crafts.add(craft);
            return true;
        } catch (RuntimeException e) {
            Global.getLogger(BoardingCombatPlugin.class).warn("TCO: could not launch a boarding shuttle", e);
            return false;
        }
    }

    // ------------------------------------------------------------------ advance

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine != null && engine.isPaused()) amount = 0f;
        if (engine == null || !TcoSettings.boardingEnabled || Global.getCurrentState() != GameState.COMBAT) return;
        readyPool();

        if (engine.isCombatOver()) {
            settle();
            return;
        }
        if (amount <= 0f) return;

        for (Map.Entry<ShipAPI, Float> entry : cooldowns.entrySet()) {
            if (entry.getValue() > 0f) entry.setValue(entry.getValue() - amount);
        }
        if (homeSign == 0f) findHome();
        aiTimer -= amount;
        if (aiTimer <= 0f) {
            aiTimer = 1f;
            aiBoarding();
            Iterator<Map.Entry<ShipAPI, Float>> w = warned.entrySet().iterator();
            while (w.hasNext()) {
                Map.Entry<ShipAPI, Float> entry = w.next();
                if (entry.getValue() <= 1f) w.remove();
                else entry.setValue(entry.getValue() - 1f);
            }
        }
        if (!fights.isEmpty()) suppress();
        if (ejection != null) updateEjection(amount);
        updateCrafts(amount);
        updateFights(amount);
        holdDecks();
        updatePrizes(amount);
        scanLosses(amount);
        craftTimer -= amount;
        if (craftTimer <= 0f) {
            craftTimer = 0.5f;
            syncCraft();
        }
    }

    private float craftTimer;

    private final Set<ShipAPI> loyalCraft = Collections.newSetFromMap(new WeakHashMap<ShipAPI, Boolean>());

    private void rememberLoyalCraft(ShipAPI carrier) {
        for (ShipAPI craft : engine.getShips()) {
            if (!(craft.isFighter() || craft.isDrone())) continue;
            ShipAPI source = craft.isDrone() && craft.getDroneSource() != null ? craft.getDroneSource()
                    : craft.getWing() != null ? craft.getWing().getSourceShip() : null;
            if (source == carrier) loyalCraft.add(craft);
        }
    }

    /**
     * Craft launched after a capture join the new side; earlier ones stay; a disabled ship's craft go down.
     */
    private void syncCraft() {
        for (ShipAPI craft : engine.getShips()) {
            if (loyalCraft.contains(craft)) continue;
            if (!craft.isAlive() || craft.isHulk() || !(craft.isFighter() || craft.isDrone())) continue;
            ShipAPI carrier = craft.isDrone() && craft.getDroneSource() != null ? craft.getDroneSource()
                    : craft.getWing() != null ? craft.getWing().getSourceShip() : null;
            if (carrier == null) continue;
            boolean changed = carrier.getCustomData().containsKey(CAPTURED_KEY) || carrier.getCustomData().containsKey("tco_converted");
            if (!changed) continue;
            int owner = carrier.getOwner();
            if (owner == 100) {
                engine.applyDamage(craft, craft.getLocation(), craft.getHitpoints() * 10f + 10000f,
                        DamageType.ENERGY, 0f, true, false, null, false);
                continue;
            }
            if (craft.getOwner() != owner) {
                craft.setOwner(owner);
                craft.setOriginalOwner(owner);
                craft.setShipTarget(null);
            }
        }
    }

    private void findHome() {
        float sum = 0f;
        int count = 0;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != 0 || ship.isFighter() || ship.isHulk() || ship.isShuttlePod()) continue;
            if (AmmoRules.isShuttle(ship) || SupplyFob.isFobPart(ship)) continue;
            sum += ship.getLocation().y;
            count++;
        }
        if (count > 0 && Math.abs(sum / count) > 200f) homeSign = Math.signum(sum);
    }

    private void updateCrafts(float amount) {
        int shotDown = 0;
        Iterator<Craft> it = crafts.iterator();
        while (it.hasNext()) {
            Craft craft = it.next();
            ShipAPI ship = craft.ship;

            if (!ship.isAlive() || ship.isHulk()) {
                if (craft.playerOwned) shotDown += craft.marines;
                craft.marines = 0;
                it.remove();
                continue;
            }
            boolean inPlay = engine.isEntityInPlay(ship);
            boolean targetOk = isBoardable(craft.target, ship.getOwner());
            boolean sourceOk = craft.source.isAlive() && !craft.source.isHulk()
                    && engine.isEntityInPlay(craft.source);
            craft.legTime += amount;

            switch (craft.leg) {
                case OUTBOUND:
                    if (!inPlay) {
                        if (craft.playerOwned) shotDown += craft.marines;
                        it.remove();
                    } else if (!targetOk || craft.legTime > OUTBOUND_TIMEOUT) {
                        craft.leg = Leg.RETURN;
                        craft.legTime = 0f;
                    } else {
                        craft.ai.flyTo(craft.target.getLocation());
                        if (isNear(ship, craft.target)) {
                            if (craft.fragments) {
                                // Fragments pour straight in
                                joinFight(craft.target, craft.marines, craft);
                                craft.marines = 0;
                                engine.removeEntity(ship);
                                it.remove();
                            } else {
                                beginLanding(craft, craft.target, Leg.LANDING);
                            }
                        }
                    }
                    break;

                case LANDING:
                    if (inPlay && !targetOk) {
                        if (ship.isLanding()) ship.abortLanding();
                        craft.leg = Leg.RETURN;
                        craft.legTime = 0f;
                        break;
                    }
                    if (inPlay) ride(craft, craft.target, amount);
                    if (!inPlay || ship.isFinishedLanding() || craft.legTime > LANDING_TIMEOUT) {
                        // Marines aboard; shuttle stays
                        if (targetOk) joinFight(craft.target, craft.marines, craft);
                        else if (craft.playerOwned) shotDown += craft.marines;
                        craft.marines = 0;
                        if (inPlay) engine.removeEntity(ship);
                        it.remove();
                    }
                    break;

                case RETURN:
                    if (!inPlay || !sourceOk) {
                        // No ship to return to: back to the fleet
                        if (craft.playerOwned) unassigned += craft.marines;
                        craft.marines = 0;
                        if (inPlay) engine.removeEntity(ship);
                        it.remove();
                    } else {
                        craft.ai.flyTo(craft.source.getLocation());
                        if (isNear(ship, craft.source)) {
                            if (craft.fragments) {
                                giveBack(craft);
                                engine.removeEntity(ship);
                                it.remove();
                            } else {
                                beginLanding(craft, craft.source, Leg.LANDING_HOME);
                            }
                        }
                    }
                    break;

                default:
                    if (inPlay && sourceOk) ride(craft, craft.source, amount);
                    if (!inPlay || !sourceOk || ship.isFinishedLanding() || craft.legTime > LANDING_TIMEOUT) {
                        giveBack(craft);
                        if (inPlay) engine.removeEntity(ship);
                        it.remove();
                    }
                    break;
            }
        }
        if (shotDown > 0) Msg.send(engine, Msg.PLAYER, shotDown + " marines were lost with their boarding shuttles.");
    }

    private boolean isNear(ShipAPI shuttle, ShipAPI host) {
        float dx = shuttle.getLocation().x - host.getLocation().x;
        float dy = shuttle.getLocation().y - host.getLocation().y;
        float reach = host.getCollisionRadius() * 0.6f + 40f;
        return dx * dx + dy * dy < reach * reach;
    }

    private void beginLanding(Craft craft, ShipAPI host, Leg leg) {
        float dx = craft.ship.getLocation().x - host.getLocation().x;
        float dy = craft.ship.getLocation().y - host.getLocation().y;
        double f = Math.toRadians(host.getFacing());
        float cos = (float) Math.cos(f);
        float sin = (float) Math.sin(f);
        craft.local.set(dx * cos + dy * sin, -dx * sin + dy * cos);
        // Stay near the hull's centreline
        float side = craft.local.y >= 0f ? 1f : -1f;
        float along = (random.nextFloat() - 0.5f) * host.getCollisionRadius() * 0.25f;
        craft.berth.set(along, side * host.getCollisionRadius() * 0.06f);
        craft.leg = leg;
        craft.legTime = 0f;
        craft.ai.hold();
        craft.ship.beginLandingAnimation(host);
    }

    private void ride(Craft craft, ShipAPI host, float amount) {
        ShipAPI ship = craft.ship;
        float blend = Math.min(1f, amount * 2.5f);
        craft.local.x += (craft.berth.x - craft.local.x) * blend;
        craft.local.y += (craft.berth.y - craft.local.y) * blend;
        double f = Math.toRadians(host.getFacing());
        float cos = (float) Math.cos(f);
        float sin = (float) Math.sin(f);
        ship.getLocation().set(host.getLocation().x + craft.local.x * cos - craft.local.y * sin,
                host.getLocation().y + craft.local.x * sin + craft.local.y * cos);
        ship.getVelocity().set(host.getVelocity());
        ship.setAngularVelocity(0f);
    }

    // ------------------------------------------------------------------ fighting aboard

    private void joinFight(ShipAPI target, int marines, Craft craft) {
        boolean playerOwned = craft.playerOwned;
        Fight fight = fights.get(target);
        if (fight == null) {
            fight = new Fight();
            fight.ship = target;
            // Damaged ships have fewer defenders
            float condition = 0.4f + 0.6f * Math.max(0f, Math.min(1f, target.getHullLevel()));
            if (isAutomated(target)) {
                // Automated: droids, no crew
                fight.droidDefenders = true;
                boolean firstTime = !garrisons.containsKey(target);
                fight.defenders = garrisonOf(target) * (firstTime ? condition : 1f);
            } else if (isPlayerShip(target)) {
                // Player ships: defended by their marines only
                fight.playerDefends = true;
                fight.defenders = marinesAboard(target);
            } else {
                boolean firstTime = !garrisons.containsKey(target);
                fight.defenders = garrisonOf(target) * (firstTime ? condition : 1f);
            }
            fight.crewStart = crewOf(target);
            fight.crew = fight.crewStart * condition;
            if (!fight.droidDefenders && MilitiaLockers.has(target) && fight.crew > 0f) {
                // Armed crew fight as militia instead of as bystanders
                fight.militia = fight.crew * TcoSettings.militiaStrength;
                fight.militiaStart = fight.militia;
                fight.defenders += fight.militia;
                fight.crew = 0f;
            }
            if (!fight.droidDefenders) {
                fight.secDroids = securityLeft(target);
                fight.defenders += fight.secDroids;
            }
            fight.defendersStart = Math.max(1f, fight.defenders);
            fight.decks = decksOf(target, fight.droidDefenders);
            rollUnknowns(fight);
            if (fight.playerDefends) playerInvolved += fight.defenders;
            fights.put(target, fight);
            String who = craft.droids ? "droids" : "marines";
            String guard = fight.droidDefenders ? Math.round(fight.defenders) + " defending droids"
                    : fight.militia > 0f ? defenderCount(fight)
                    : Math.round(fight.defenders) + " marines and " + Math.round(fight.crew) + " crew";
            if (target.getOwner() == 0) {
                message(target, "Enemy " + who + " have boarded " + target.getName() + "! " + guard + " defending.");
            } else if (craft.playerSide) {
                message(target, (craft.droids ? "Droids" : "Marines") + " are aboard " + target.getName() + ": " + guard + ".");
            }
        }
        fight.attackers += marines;
        if (playerOwned) {
            fight.playerShare += marines;
            playerInvolved += marines;
        }
        if (craft.droids) fight.droidAttackers += marines;
        if (craft.fragments) fight.fragmentAttackers += marines;
        if (craft.playerSide) fight.playerSide += marines;
        if (fight.log.isEmpty()) {
            tell(fight, defenderCount(fight) + (fight.droidDefenders || fight.militia > 0f ? "" : " and " + Math.round(fight.crew) + " crew")
                    + " defending (" + fight.qualityName + ", " + fight.layoutName + ", captain " + fight.captain + ").", 0);
        }
        tell(fight, marines + " " + (craft.droids ? "droids" : "marines") + " landed.", 1);
        if (BreachingCharges.has(craft.source)) breach(fight, craft);
        Float sent = fight.sources.get(craft.source);
        fight.sources.put(craft.source, (sent == null ? 0f : sent) + marines);
    }

    /**
     * Breaching Charges: the docking shuttle blows its way in, damaging the hull (never below breachHullFloor of it,
     * so the prize survives). A ship's first wave onto this target then attacks harder for breachSeconds.
     */
    private void breach(Fight fight, Craft craft) {
        ShipAPI target = fight.ship;
        Vector2f at = craft.ship != null ? new Vector2f(craft.ship.getLocation()) : new Vector2f(target.getLocation());
        float floor = target.getMaxHitpoints() * TcoSettings.breachHullFloor;
        float hull = target.getHitpoints();
        if (TcoSettings.breachHullDamage > 0f && hull > floor) {
            target.setHitpoints(Math.max(floor, hull - TcoSettings.breachHullDamage));
            CombatEngineAPI engine = Global.getCombatEngine();
            engine.spawnExplosion(at, target.getVelocity(), new Color(255, 160, 80), 45f, 0.5f);
            engine.addHitParticle(at, target.getVelocity(), 70f, 1f, 0.2f, new Color(255, 220, 160));
            Global.getSoundPlayer().playSound("explosion_from_damage", 1.2f, 0.5f, at, target.getVelocity());
        }
        if (craft.source != null && fight.breachedBy.add(craft.source) && TcoSettings.breachSeconds > 0f) {
            fight.breachUntil = Math.max(fight.breachUntil, fight.time + TcoSettings.breachSeconds);
            tell(fight, "Breaching charges blow the hatches: the boarders storm in.", 1);
        }
    }

    private void attackersLose(Fight fight, float losses) {
        float before = fight.attackers;
        fight.attackers -= losses;
        if (before <= 0f) return;
        float kept = Math.max(0f, fight.attackers) / before;
        fight.playerShare = Math.max(0f, fight.playerShare * kept);
        fight.droidAttackers = Math.max(0f, fight.droidAttackers * kept);
        fight.fragmentAttackers = Math.max(0f, fight.fragmentAttackers * kept);
        fight.playerSide = Math.max(0f, fight.playerSide * kept);
        for (Map.Entry<ShipAPI, Float> entry : fight.sources.entrySet()) entry.setValue(entry.getValue() * kept);
    }

    private static String attackerWord(Fight fight) {
        if (fight.fragmentAttackers >= fight.attackers - 0.5f && fight.fragmentAttackers >= 0.5f) return "fragments";
        if (fight.droidAttackers < 0.5f) return "marines";
        if (fight.droidAttackers >= fight.attackers - 0.5f) return "droids";
        return "boarders";
    }

    private static String defenderWord(Fight fight) {
        return fight.droidDefenders ? "droids" : "marines";
    }

    private static String defenderCount(Fight fight) {
        int droids = Math.round(Math.min(fight.secDroids, fight.defenders));
        // Militia are counted in people, half a marine each
        int militia = Math.round(fight.militia / Math.max(0.01f, TcoSettings.militiaStrength));
        int marines = Math.max(0, Math.round(fight.defenders - fight.secDroids - fight.militia));
        if (droids <= 0 && militia <= 0) return Math.round(fight.defenders) + " " + defenderWord(fight);
        List<String> parts = new ArrayList<String>();
        if (marines > 0 || (droids <= 0 && militia <= 0)) parts.add(marines + " marines");
        if (militia > 0) parts.add(militia + " militia");
        if (droids > 0) parts.add(droids + " security droids");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) text.append(i == parts.size() - 1 ? " and " : ", ");
            text.append(parts.get(i));
        }
        return text.toString();
    }

    private void updateFights(float amount) {
        Iterator<Map.Entry<ShipAPI, Fight>> it = fights.entrySet().iterator();
        while (it.hasNext()) {
            Fight fight = it.next().getValue();
            ShipAPI ship = fight.ship;

            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship) || DeathThroes.isDying(ship)) {
                // Ship lost or gone with the party aboard
                if (fight.playerShare >= 1f) {
                    message(ship, Math.round(fight.playerShare) + " marines were lost aboard " + ship.getName() + ".");
                }
                it.remove();
                continue;
            }

            fight.tick += amount;
            if (fight.tick < TICK) continue;
            fight.tick -= TICK;

            float rate = TcoSettings.boardingKillRate;
            float defending = fight.defenders + fight.crew * TcoSettings.crewStrength;
            float attack;
            float defence;
            if (fight.defenders > 0f) {
                // Corridor fighting: only as many attackers engage as there are defenders
                float engaged = Math.min(fight.attackers, defending);
                // Plus a share of the extra attackers, at least a squad
                float extra = Math.max(0f, fight.attackers - engaged) * TcoSettings.boardingFlankShare;
                float pressing = Math.max(Math.min(fight.attackers, engaged + extra), Math.min(fight.attackers, MIN_SQUAD));
                attack = pressing * rate * (0.75f + random.nextFloat() * 0.5f);
                defence = engaged * rate * TcoSettings.defenderBonus * (0.75f + random.nextFloat() * 0.5f);
            } else {
                attack = fight.attackers * rate * (0.75f + random.nextFloat() * 0.5f);
                defence = defending * rate * TcoSettings.defenderBonus * (0.75f + random.nextFloat() * 0.5f);
            }
            // Droids hit harder
            float droidShare = fight.attackers > 0f ? Math.min(1f, fight.droidAttackers / fight.attackers) : 0f;
            attack *= 1f + (TcoSettings.droidStrength - 1f) * droidShare;
            // Threat fragments are weak one by one
            float fragmentShare = fight.attackers > 0f ? Math.min(1f, fight.fragmentAttackers / fight.attackers) : 0f;
            attack *= 1f + (TcoSettings.threatFragmentStrength - 1f) * fragmentShare;
            if (fight.droidDefenders) defence *= TcoSettings.droidStrength;
            // Each section taken beyond the entry adds to the attack
            if (fight.decks != null && fight.decks.size() > 1) {
                float held = (fight.taken - 1f) / (fight.decks.size() - 1f);
                attack *= 1f + TcoSettings.boardingDeckAttackBonus * Math.max(0f, held);
            }
            // Defender quality, layout and event modifiers
            defence *= fight.quality * fight.layoutMult;
            attack /= fight.quality;
            if (fight.time < fight.armoryUntil) attack *= 1.3f;
            if (fight.time < fight.breachUntil) attack *= 1f + TcoSettings.breachAttackBonus;
            if (fight.time < fight.ambushUntil) defence *= 2f;
            // Orders and experience
            attack *= orderAttack(fight.order) * (1f + attackerXp(fight) * TcoSettings.boardingXpAttack);
            defence *= orderLosses(fight.order) * (1f - attackerXp(fight) * TcoSettings.boardingXpLosses);
            float defXp = fight.droidDefenders ? 0f : fight.playerDefends ? playerXpLevel() : TcoSettings.aiMarineXpLevel;
            // Security droids: weaker, no experience; militia: no experience (their strength is in their count)
            float secShare = fight.defenders > 0f ? Math.min(1f, fight.secDroids / fight.defenders) : 0f;
            float militiaShare = fight.defenders > 0f ? Math.min(1f - secShare, fight.militia / fight.defenders) : 0f;
            defXp *= Math.max(0f, 1f - secShare - militiaShare);
            defence *= 1f - secShare + secShare * TcoSettings.securityDroidStrength;
            defence *= 1f + defXp * TcoSettings.boardingXpAttack;
            attack *= 1f - defXp * TcoSettings.boardingXpLosses;

            if (fight.defenders > 0f) {
                float before = fight.defenders;
                float droidsBefore = fight.secDroids;
                fight.defenders -= attack;
                // Security droids take losses first
                fight.secDroids = Math.max(0f, fight.secDroids - attack);
                // Then militia and marines in proportion
                float rest = Math.max(0f, attack - droidsBefore);
                float people = before - droidsBefore;
                if (fight.militia > 0f && people > 0f) {
                    float lost = Math.min(fight.militia, rest * fight.militia / people);
                    fight.militia -= lost;
                    if (fight.playerDefends) militiaLost += lost / Math.max(0.01f, TcoSettings.militiaStrength);
                }
                // Outnumbered remnants may surrender
                if (fight.defenders >= 0.5f && fight.defenders <= fight.defendersStart * 0.25f
                        && fight.attackers >= TcoSettings.boardingRoutRatio * defending
                        && random.nextFloat() < TcoSettings.boardingRoutChance * fight.yields) {
                    fight.defenders = 0f;
                    tell(fight, "The last " + defenderWord(fight) + " have laid down their arms.", 1);
                }
                if (fight.defenders < 0.5f) {
                    fight.defenders = 0f;
                    tell(fight, "The defending " + defenderWord(fight) + " have been beaten: on to the "
                            + (fight.droidDefenders ? "AI core." : "bridge."), 1);
                }
            } else {
                // Only crew left
                fight.crew = Math.max(0f, fight.crew - attack * 4f);
            }
            attackersLose(fight, defence);
            fight.secDroids = Math.max(0f, Math.min(fight.secDroids, fight.defenders));
            fight.militia = Math.max(0f, Math.min(fight.militia, fight.defenders - fight.secDroids));
            if (fight.playerDefends) {
                shipPools.put(keyOf(ship), Math.max(0, Math.round(fight.defenders - fight.secDroids - fight.militia)));
            }
            takeDecks(fight);
            fight.time += TICK;
            if (fight.time >= fight.nextEvent) {
                fight.nextEvent = fight.time + TcoSettings.boardingEventSeconds * (0.7f + random.nextFloat() * 0.6f);
                boardingEvent(fight);
            }
            if (fight.rigged != null && tickRigged(fight)) {
                it.remove();
                continue;
            }
            if (fight.withdrawIn >= 0f) {
                fight.withdrawIn -= TICK;
                if (fight.withdrawIn <= 0f) {
                    it.remove();
                    withdraw(fight);
                    continue;
                }
            }

            if (fight.attackers < 1f) {
                keepGarrison(fight);
                if (fight.playerShare > 0f || ship.getOwner() == 0) message(ship, ship.getOwner() == 0 ? ship.getName() + " has repelled the enemy boarders."
                        : "The boarding of " + ship.getName() + " was repelled.");
                it.remove();
                continue;
            }

            if (random.nextFloat() < MALFUNCTION_CHANCE * orderMalfunctions(fight.order)) malfunction(ship);

            // Automated: droids gone, core reached
            if (fight.droidDefenders && fight.defenders <= 0f) {
                it.remove();
                reachCore(fight);
                continue;
            }

            // Sabotage
            if (fight.defenders <= 0f && fight.order == BoardOrder.SABOTAGE) {
                it.remove();
                sabotage(fight);
                continue;
            }
            if (fight.defenders <= 0f) {
                fight.sinceSurrenderCheck += TICK;
                if (fight.sinceSurrenderCheck >= TcoSettings.surrenderCheckSeconds) {
                    fight.sinceSurrenderCheck = 0f;
                    float casualties = fight.crewStart > 0f ? 1f - fight.crew / fight.crewStart : 1f;
                    float chance = (0.1f + 0.9f * Math.max(0f, Math.min(1f, casualties))) * fight.yields;
                    if (fight.crew <= 0f || random.nextFloat() < chance) {
                        it.remove();
                        capture(fight);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ decks and orders

    private float playerInvolved = 0f;

    private static float playerXpLevel() {
        try {
            if (Global.getSector() == null) return 0f;
            return PlayerFleetPersonnelTracker.getInstance().getMarineData().getXPLevel();
        } catch (RuntimeException e) {
            return 0f;
        }
    }

    private float attackerXp(Fight fight) {
        if (fight.attackers <= 0f) return 0f;
        float marines = Math.max(0f, fight.attackers - fight.droidAttackers - fight.fragmentAttackers);
        if (marines <= 0f) return 0f;
        float player = Math.min(marines, fight.playerShare);
        return (player * playerXpLevel() + (marines - player) * TcoSettings.aiMarineXpLevel) / fight.attackers;
    }

    private static float orderAttack(BoardOrder order) {
        if (order == BoardOrder.ASSAULT) return TcoSettings.assaultAttackMult;
        if (order == BoardOrder.METHODICAL) return TcoSettings.methodicalAttackMult;
        return 1f;
    }

    private static float orderLosses(BoardOrder order) {
        if (order == BoardOrder.ASSAULT) return TcoSettings.assaultLossMult;
        if (order == BoardOrder.METHODICAL) return TcoSettings.methodicalLossMult;
        return 1f;
    }

    private static float orderMalfunctions(BoardOrder order) {
        if (order == BoardOrder.ASSAULT) return 1.5f;
        if (order == BoardOrder.METHODICAL) return 0.4f;
        if (order == BoardOrder.SABOTAGE) return 2f;
        return 1f;
    }

    private List<Deck> decksOf(ShipAPI ship, boolean automated) {
        List<Deck> decks = new ArrayList<Deck>();
        decks.add(new Deck(DeckKind.ENTRY, "entry deck"));
        HullSize size = ship.getHullSize();
        List<WeaponAPI> guns = new ArrayList<WeaponAPI>();
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (!w.isDecorative() && w.getSlot() != null && !w.getSlot().isSystemSlot()) guns.add(w);
        }
        if (size == HullSize.CAPITAL_SHIP) {
            Deck port = new Deck(DeckKind.BATTERIES, "port batteries");
            Deck starboard = new Deck(DeckKind.BATTERIES, "starboard batteries");
            for (WeaponAPI w : guns) (w.getSlot().getLocation().y >= 0f ? port : starboard).weapons.add(w);
            decks.add(port);
            decks.add(starboard);
        } else if (size == HullSize.CRUISER) {
            Deck batteries = new Deck(DeckKind.BATTERIES, "gun decks");
            for (WeaponAPI w : guns) if (random.nextBoolean()) batteries.weapons.add(w);
            decks.add(batteries);
        }
        if (size != HullSize.FRIGATE) decks.add(new Deck(DeckKind.ENGINEERING, "engineering"));
        decks.add(new Deck(DeckKind.BRIDGE, automated ? "AI core" : "bridge"));
        return decks;
    }

    private void takeDecks(Fight fight) {
        if (fight.decks == null) return;
        int middle = fight.decks.size() - 1;
        float lost = 1f - Math.max(0f, fight.defenders) / fight.defendersStart;
        while (fight.taken < middle && lost >= (float) fight.taken / middle && lost >= fight.retakeFloor) {
            Deck deck = fight.decks.get(fight.taken);
            fight.taken++;
            String effect = deck.kind == DeckKind.BATTERIES ? "its guns there are silent"
                    : deck.kind == DeckKind.ENGINEERING ? "engines and shields are out" : "";
            if (fight.order == BoardOrder.SABOTAGE && deck.kind == DeckKind.BATTERIES) {
                for (WeaponAPI w : deck.weapons) w.disable(true);
                effect = "its guns there are wrecked";
            }
            ShipAPI ship = fight.ship;
            String who = ship.getOwner() == 0 ? "Enemy " + attackerWord(fight) : "Your " + attackerWord(fight);
            tell(fight, who + " took the " + deck.name + (effect.isEmpty() ? "" : ": " + effect) + ".", 1);
        }
    }

    // ------------------------------------------------------------------ unknowns and events

    private void rollUnknowns(Fight fight) {
        ShipAPI ship = fight.ship;
        // Garrison quality
        if (fight.playerDefends || fight.droidDefenders) {
            fight.quality = 1f;
            fight.qualityName = fight.droidDefenders ? "droids" : "your marines";
        } else {
            float spread = TcoSettings.garrisonQualitySpread;
            fight.quality = 1f - spread + spread * (random.nextFloat() + random.nextFloat());
            fight.qualityName = fight.quality < 0.85f ? "green" : fight.quality < 1.1f ? "regular" : fight.quality < 1.2f ? "veteran" : "elite";
        }
        // Layout
        Set<ShipHullSpecAPI.ShipTypeHints> hints = ship.getHullSpec() == null
                ? Collections.<ShipHullSpecAPI.ShipTypeHints>emptySet() : ship.getHullSpec().getHints();
        boolean open = SupportRoles.isCivilian(ship)
                || hints.contains(ShipHullSpecAPI.ShipTypeHints.CARRIER)
                || hints.contains(ShipHullSpecAPI.ShipTypeHints.FREIGHTER)
                || hints.contains(ShipHullSpecAPI.ShipTypeHints.TANKER)
                || hints.contains(ShipHullSpecAPI.ShipTypeHints.LINER)
                || hints.contains(ShipHullSpecAPI.ShipTypeHints.TRANSPORT);
        boolean cramped = ship.getHullSpec() != null && ship.getHullSpec().getShieldType() == ShieldAPI.ShieldType.PHASE;
        if (open) {
            fight.layoutMult = 0.85f;
            fight.layoutName = "open decks";
        } else if (cramped) {
            fight.layoutMult = 1.15f;
            fight.layoutName = "cramped";
        }
        // Captain
        PersonAPI captain = ship.getCaptain();
        String personality = "steady";
        if (captain != null && captain.isAICore() || fight.droidDefenders) {
            personality = "AI core";
        } else if (captain != null && captain.getPersonalityAPI() != null) {
            personality = captain.getPersonalityAPI().getId();
        }
        fight.captain = personality;
        if ("AI core".equals(personality)) {
            fight.yields = 0f;
            fight.desperation = 0f;
        } else if ("timid".equals(personality)) {
            fight.yields = 2f;
            fight.desperation = 0.3f;
        } else if ("cautious".equals(personality)) {
            fight.yields = 1.5f;
            fight.desperation = 0.6f;
        } else if ("aggressive".equals(personality)) {
            fight.yields = 0.6f;
            fight.desperation = 1.5f;
        } else if ("reckless".equals(personality)) {
            fight.yields = 0.35f;
            fight.desperation = 2f;
        }
        fight.nextEvent = TcoSettings.boardingEventSeconds * (0.7f + random.nextFloat() * 0.6f);
    }

    private static boolean playerCares(Fight fight) {
        return fight.playerSide > 0f || fight.playerDefends || fight.ship.getOwner() == 0;
    }

    private void tell(Fight fight, String text) {
        tell(fight, text, 0);
    }

    private void tell(Fight fight, String text, int good) {
        if (!playerCares(fight)) return;
        if (!TcoSettings.boardingLogPanel) {
            message(fight.ship, text);
            return;
        }
        String prefix = "Aboard " + fight.ship.getName() + ": ";
        if (text.startsWith(prefix)) {
            text = text.substring(prefix.length());
            if (!text.isEmpty()) text = Character.toUpperCase(text.charAt(0)) + text.substring(1);
        }
        fight.log.add(new Object[] {fight.time, text, good});
        while (fight.log.size() > 30) fight.log.remove(0);
    }

    private void boardingEvent(Fight fight) {
        ShipAPI ship = fight.ship;
        String name = ship.getName();
        float defending = fight.defenders + fight.crew * TcoSettings.crewStrength;
        boolean defendersLeft = fight.defenders > 0f;
        // Rare: losing defenders rig the ship to blow
        if (!fight.desperateDone && defendersLeft && !fight.playerDefends && !fight.droidDefenders
                && fight.defenders <= fight.defendersStart * 0.4f
                && random.nextFloat() < TcoSettings.boardingDesperateChance * fight.desperation) {
            fight.desperateDone = true;
            boolean reactor = random.nextFloat() < 0.3f;
            fight.rigged = reactor ? "reactor" : "magazine";
            fight.riggedIn = reactor ? 45f : 20f;
            String warning = "Aboard " + name + ": the defenders are rigging the " + fight.rigged + " to blow! "
                    + Math.round(fight.riggedIn) + " seconds to take the bridge or get out.";
            tell(fight, warning, -1);
            if (TcoSettings.boardingLogPanel && playerCares(fight)) message(ship, warning);
            return;
        }
        float bulkhead = defendersLeft ? 1f : 0f;
        float ambush = defendersLeft ? 0.8f : 0f;
        float counter = defendersLeft && fight.taken > 1 ? (defending * fight.quality >= fight.attackers * 0.6f ? 1.5f : 0.4f) : 0f;
        float armory = 1f;
        float mutiny = !fight.droidDefenders && fight.crew > 1f && !fight.playerDefends ? 0.5f : 0f;
        float fire = 0.8f;
        float quiet = 1.5f;
        float roll = random.nextFloat() * (bulkhead + ambush + counter + armory + mutiny + fire + quiet);
        String attackers = attackerWord(fight);
        if ((roll -= bulkhead) < 0f) {
            float share = 0.1f + random.nextFloat() * 0.1f;
            attackersLose(fight, fight.attackers * share);
            tell(fight, "Aboard " + name + ": the defenders blew a bulkhead. " + Math.round(share * 100f) + "% of the "
                    + attackers + " are lost.", -1);
        } else if ((roll -= ambush) < 0f) {
            fight.ambushUntil = fight.time + 10f;
            tell(fight, "Aboard " + name + ": the " + attackers + " walked into an ambush.", -1);
        } else if ((roll -= counter) < 0f) {
            Deck lost = fight.decks.get(fight.taken - 1);
            fight.taken--;
            attackersLose(fight, fight.attackers * 0.08f);
            fight.retakeFloor = 1f - Math.max(0f, fight.defenders) / fight.defendersStart + 0.12f;
            tell(fight, "Aboard " + name + ": a counterattack has retaken the " + lost.name + ".", -1);
        } else if ((roll -= armory) < 0f) {
            fight.armoryUntil = fight.time + 20f;
            tell(fight, "Aboard " + name + ": the " + attackers + " seized an armory and are pressing harder.", 1);
        } else if ((roll -= mutiny) < 0f) {
            fight.crew *= 0.85f;
            fight.defenders *= 0.9f;
            tell(fight, "Aboard " + name + ": part of the crew has mutinied and turned on the defenders.", 1);
        } else if ((roll -= fire) < 0f) {
            attackersLose(fight, fight.attackers * 0.05f);
            fight.defenders *= 0.95f;
            fight.crew *= 0.95f;
            Vector2f at = DeathThroes.pointOnHull(ship);
            Fires.ignite(engine, ship, at, true, 15f + random.nextFloat() * 10f);
            tell(fight, "Aboard " + name + ": fighting has started a fire.");
        }
    }

    private boolean tickRigged(Fight fight) {
        ShipAPI ship = fight.ship;
        if (fight.defenders <= 0f) {
            tell(fight, "Aboard " + ship.getName() + ": the charges on the " + fight.rigged + " were found and defused.", 1);
            fight.rigged = null;
            return false;
        }
        fight.riggedIn -= TICK;
        if (fight.riggedIn > 0f) return false;
        String what = fight.rigged;
        fight.rigged = null;
        if ("reactor".equals(what)) {
            tell(fight, ship.getName() + "'s defenders have blown the reactor!", -1);
            if (TcoSettings.boardingLogPanel && playerCares(fight)) message(ship, ship.getName() + "'s defenders have blown the reactor!");
            engine.applyDamage(ship, ship.getLocation(), ship.getHitpoints() * 10f + 100000f,
                    DamageType.ENERGY, 0f, true, false, null, false);
            return false;
        }
        // Magazine: biggest ballistic or missile mount goes up
        WeaponAPI worst = null;
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.isDecorative() || w.isPermanentlyDisabled()) continue;
            boolean stores = w.getType() == WeaponAPI.WeaponType.BALLISTIC || w.getType() == WeaponAPI.WeaponType.MISSILE;
            int score = w.getSize().ordinal() * 2 + (stores ? 1 : 0);
            int best = worst == null ? -1 : worst.getSize().ordinal() * 2
                    + (worst.getType() == WeaponAPI.WeaponType.BALLISTIC || worst.getType() == WeaponAPI.WeaponType.MISSILE ? 1 : 0);
            if (score > best) worst = w;
        }
        Vector2f at = worst != null ? new Vector2f(worst.getLocation()) : DeathThroes.pointOnHull(ship);
        if (worst != null) worst.disable(true);
        float blast = ship.getMaxHitpoints() * 0.15f;
        engine.spawnExplosion(at, ship.getVelocity(), new Color(255, 160, 60), 90f + ship.getCollisionRadius() * 0.3f, 1.2f);
        engine.applyDamage(ship, at, blast, DamageType.HIGH_EXPLOSIVE, 0f, true, false, null, true);
        Fires.ignite(engine, ship, at, true, 25f);
        attackersLose(fight, fight.attackers * 0.15f);
        fight.defenders *= 0.85f;
        tell(fight, ship.getName() + "'s defenders blew a magazine" + (worst != null ? ": the " + worst.getDisplayName() + " is gone" : "") + ".", -1);
        return false;
    }

    private void holdDecks() {
        for (Fight fight : fights.values()) {
            if (fight.decks == null) continue;
            ShipAPI ship = fight.ship;
            if (!ship.isAlive() || ship.isHulk()) continue;
            for (int i = 1; i < fight.taken; i++) {
                Deck deck = fight.decks.get(i);
                if (deck.kind == DeckKind.BATTERIES) {
                    for (WeaponAPI w : deck.weapons) w.setForceNoFireOneFrame(true);
                } else if (deck.kind == DeckKind.ENGINEERING) {
                    if (ship.getEngineController() != null && !ship.getEngineController().isFlamedOut()) {
                        ship.getEngineController().forceFlameout(true);
                    }
                    ship.blockCommandForOneFrame(ShipCommand.TOGGLE_SHIELD_OR_PHASE_CLOAK);
                    if (ship.getShield() != null && ship.getShield().isOn()) ship.getShield().toggleOff();
                }
            }
            // Bridge taken: nobody left to fire
            if (fight.defenders <= 0f) ship.setHoldFireOneFrame(true);
        }
    }

    /** What the RTS boarding card shows. */
    public static final class BoardingInfo {
        public String shipName;
        public String hullName;
        public int attackers;
        public int playerMarines;
        public int droidAttackers;
        public int defenders;
        public int crew;
        public boolean droidDefenders;
        public boolean playerDefends;
        public boolean playerOrders;
        public final List<String> decks = new ArrayList<String>();
        public int taken;
        public final List<String> effects = new ArrayList<String>();
        public BoardOrder order;
        public float withdrawIn;
        public float attackerXp;
        public float defenderXp;
        public int securityDroids;
        public String garrison;
        public String layout;
        public String captain;
        public String rigged;
        public float riggedIn;
        public final List<String> events = new ArrayList<String>();
    }

    /** Security droids aboard, or -1 without a forge. */
    public int securityDroidsOf(ShipAPI ship) {
        if (!SecurityForge.has(ship) || isAutomated(ship)) return -1;
        Fight fight = fights.get(ship);
        if (fight != null) return Math.round(Math.min(fight.secDroids, fight.defenders));
        return Math.round(securityLeft(ship));
    }

    public BoardingInfo info(ShipAPI ship) {
        Fight fight = fights.get(ship);
        if (fight == null) return null;
        BoardingInfo info = new BoardingInfo();
        info.shipName = ship.getName();
        info.hullName = ship.getHullSpec() != null ? ship.getHullSpec().getHullName() : "";
        info.attackers = Math.round(fight.attackers);
        info.playerMarines = Math.round(fight.playerShare);
        info.droidAttackers = Math.round(fight.droidAttackers);
        info.defenders = Math.round(fight.defenders);
        info.securityDroids = Math.round(Math.min(fight.secDroids, fight.defenders));
        info.crew = Math.round(fight.crew);
        info.droidDefenders = fight.droidDefenders;
        info.playerDefends = fight.playerDefends;
        info.playerOrders = fight.playerSide > 0f && ship.getOwner() != 0;
        info.order = fight.order;
        info.withdrawIn = fight.withdrawIn;
        info.attackerXp = attackerXp(fight);
        info.defenderXp = fight.droidDefenders ? 0f : fight.playerDefends ? playerXpLevel() : TcoSettings.aiMarineXpLevel;
        info.garrison = fight.qualityName;
        info.layout = fight.layoutName;
        info.captain = fight.captain;
        info.rigged = fight.rigged;
        info.riggedIn = fight.riggedIn;
        if (fight.time < fight.armoryUntil) info.events.add("armory seized: +30% attack (" + Math.round(fight.armoryUntil - fight.time) + " s)");
        if (fight.time < fight.ambushUntil) info.events.add("ambushed: defenders hit twice as hard (" + Math.round(fight.ambushUntil - fight.time) + " s)");
        if (fight.decks != null) {
            for (Deck deck : fight.decks) info.decks.add(deck.name);
            info.taken = fight.defenders <= 0f ? fight.decks.size() : fight.taken;
            for (int i = 1; i < fight.taken && i < fight.decks.size(); i++) {
                Deck deck = fight.decks.get(i);
                if (deck.kind == DeckKind.BATTERIES) {
                    info.effects.add(deck.name + ": " + deck.weapons.size() + (fight.order == BoardOrder.SABOTAGE ? " guns wrecked" : " guns silent"));
                } else if (deck.kind == DeckKind.ENGINEERING) {
                    info.effects.add("engineering: engines and shields out");
                }
            }
            if (fight.defenders <= 0f) info.effects.add(fight.droidDefenders ? "at the AI core" : "at the bridge: the ship has stopped fighting");
        }
        return info;
    }

    /** Sets the order; picking Withdraw again cancels it. */
    public String setOrder(ShipAPI ship, BoardOrder order) {
        Fight fight = fights.get(ship);
        if (fight == null || fight.playerSide <= 0f || ship.getOwner() == 0) return null;
        if (order == BoardOrder.WITHDRAW) {
            if (fight.withdrawIn >= 0f) {
                fight.withdrawIn = -1f;
                fight.order = BoardOrder.STANDARD;
                return "Boarders aboard " + ship.getName() + ": withdrawal called off.";
            }
            fight.order = BoardOrder.WITHDRAW;
            fight.withdrawIn = TcoSettings.withdrawDelay;
            return "Boarders aboard " + ship.getName() + ": WITHDRAWING in " + Math.round(TcoSettings.withdrawDelay)
                    + " s (Withdraw again to cancel).";
        }
        fight.withdrawIn = -1f;
        fight.order = order;
        return "Boarders aboard " + ship.getName() + ": " + order.label + ". " + orderHelp(order);
    }

    public static String orderTip(BoardOrder order) {
        if (order == BoardOrder.WITHDRAW) return "Pull the boarders out after a few seconds. Click again to cancel.";
        return orderHelp(order);
    }

    /** Cycles to the next order; null if there's no fight. */
    public String cycleOrder(ShipAPI ship) {
        Fight fight = fights.get(ship);
        if (fight == null || fight.playerSide <= 0f || ship.getOwner() == 0) return null;
        BoardOrder next = BoardOrder.values()[(fight.order.ordinal() + 1) % BoardOrder.values().length];
        fight.order = next;
        if (next == BoardOrder.WITHDRAW) {
            fight.withdrawIn = TcoSettings.withdrawDelay;
            return "Boarders aboard " + ship.getName() + ": WITHDRAWING in " + Math.round(TcoSettings.withdrawDelay)
                    + " s (press again to cancel).";
        }
        fight.withdrawIn = -1f;
        return "Boarders aboard " + ship.getName() + ": " + next.label + ". " + orderHelp(next);
    }

    private static String orderHelp(BoardOrder order) {
        switch (order) {
            case ASSAULT: return "Fast, heavy losses.";
            case METHODICAL: return "Slow, few losses, less damage to the prize.";
            case SABOTAGE: return "Wreck its guns and reactor instead of capturing it.";
            default: return "Balanced.";
        }
    }

    private void withdraw(Fight fight) {
        ShipAPI ship = fight.ship;
        sendHome(fight);
        keepGarrison(fight);
        message(ship, "The boarders are pulling out of " + ship.getName() + ".");
    }

    private void sabotage(Fight fight) {
        ShipAPI ship = fight.ship;
        sendHome(fight);
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        if (power != null) power.shutDown(ship);
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (!w.isDecorative()) w.disable(true);
        }
        if (ship.getEngineController() != null) ship.getEngineController().forceFlameout(true);
        if (ship.getShield() != null && ship.getShield().isOn()) ship.getShield().toggleOff();
        garrisons.put(ship, 0f);
        message(ship, "Saboteurs have wrecked " + ship.getName() + "'s reactor and guns. It is out of the fight.");
    }

    private void gainExperience(int lost) {
        if (!campaignPool || Global.getSector() == null) return;
        int survivors = Math.max(0, Math.min(poolStart, Math.round(playerInvolved)) - lost);
        if (survivors <= 0) return;
        try {
            PlayerFleetPersonnelTracker tracker = PlayerFleetPersonnelTracker.getInstance();
            tracker.update();
            float before = tracker.getMarineData().getXPLevel();
            tracker.getMarineData().addXP(survivors * TcoSettings.boardingXpPerSurvivor);
            tracker.update();
            float after = tracker.getMarineData().getXPLevel();
            int gain = Math.round((after - before) * 100f);
            BattleReport.add("Marine experience: " + survivors + " boarding veterans" + (gain > 0 ? ", +" + gain + "%" : "")
                    + " (now " + Math.round(after * 100f) + "%, " + tracker.getMarineData().getRank().name + ").");
        } catch (RuntimeException e) {
            Global.getLogger(BoardingCombatPlugin.class).warn("TCO: could not add marine experience", e);
        }
    }

    private void malfunction(ShipAPI ship) {
        float roll = random.nextFloat();
        if (roll < 0.5f) {
            List<WeaponAPI> working = new ArrayList<WeaponAPI>();
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                if (!weapon.isDecorative() && !weapon.isDisabled()) working.add(weapon);
            }
            if (!working.isEmpty()) working.get(random.nextInt(working.size())).disable();
        } else if (roll < 0.65f) {
            if (ship.getEngineController() != null) ship.getEngineController().forceFlameout();
        } else {
            ship.setCurrentCR(Math.max(0f, ship.getCurrentCR() - 0.01f - random.nextFloat() * 0.03f));
        }
    }

    // ------------------------------------------------------------------ automated ships

    /**
     * Boarders reached an automated ship's core: the player's side takes its AI core, and with the
     * Automated Ships skill the ship too; otherwise it's left disabled. Boarders go home either way.
     */
    private void reachCore(Fight fight) {
        ShipAPI ship = fight.ship;
        boolean byPlayer = ship.getOwner() == 1 && fight.playerSide >= fight.attackers * 0.5f;
        sendHome(fight);
        if (!byPlayer) {
            message(ship, ship.getName() + "'s AI core has been shut down. It's out of the fight.");
            disableAutomated(ship);
            return;
        }
        PersonAPI captain = ship.getCaptain();
        if (captain != null && captain.isAICore() && captain.getAICoreId() != null) {
            String core = captain.getAICoreId();
            BoardingResults.cores.add(core);
            PersonAPI blank = Global.getFactory().createPerson();
            ship.setCaptain(blank);
            if (ship.getFleetMember() != null) ship.getFleetMember().setCaptain(blank);
            String name = Global.getSettings().getCommoditySpec(core) != null ? Global.getSettings().getCommoditySpec(core).getName() : core;
            message(ship, "Your boarders pulled the " + name + " out of " + ship.getName() + ". It will be in your cargo after the battle.");
        }
        if (playerHasAutomatedSkill()) {
            takeControl(ship);
            message(ship, ship.getName() + " is yours now.");
        } else {
            disableAutomated(ship);
            message(ship, ship.getName() + " is disabled. You need the Automated Ships skill to use it.");
        }
    }

    private void sendHome(Fight fight) {
        ShipAPI from = fight.ship;
        int side = from.getOwner() == 0 ? 1 : 0;
        int per = Math.max(1, TcoSettings.marinesPerShuttle);
        for (Map.Entry<ShipAPI, Float> entry : fight.sources.entrySet()) {
            int troops = Math.round(entry.getValue());
            if (troops <= 0) continue;
            ShipAPI home = entry.getKey();
            if (home == null || !home.isAlive() || home.isHulk() || !engine.isEntityInPlay(home)) home = nearestHome(from, side);
            boolean droids = isAutomated(entry.getKey());
            boolean playerSide = isPlayerShip(entry.getKey());
            if (home == null) {
                if (playerSide && !droids) unassigned += troops;
                continue;
            }
            while (troops > 0) {
                int load = Math.min(per, troops);
                if (!launchHome(from, home, load, droids, playerSide)) {
                    if (playerSide && !droids) unassigned += troops;
                    break;
                }
                troops -= load;
            }
        }
    }

    private ShipAPI nearestHome(ShipAPI from, int side) {
        ShipAPI best = null;
        float bestDist = Float.MAX_VALUE;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != side || !ship.isAlive() || ship.isHulk() || ship.isFighter() || ship.isDrone()) continue;
            if (ship.isShuttlePod() || AmmoRules.isShuttle(ship) || ship.isStation() || ship.isStationModule()) continue;
            float dist = distance(from.getLocation(), ship.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = ship;
            }
        }
        return best;
    }

    private boolean launchHome(ShipAPI from, ShipAPI home, int troops, boolean droids, boolean playerSide) {
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(home.getOwner());
            boolean quiet = fleet.isSuppressDeploymentMessages();
            fleet.setSuppressDeploymentMessages(true);
            ShipAPI ship;
            try {
                float facing = (float) Math.toDegrees(Math.atan2(home.getLocation().y - from.getLocation().y,
                        home.getLocation().x - from.getLocation().x));
                ship = fleet.spawnShipOrWing(SHUTTLE_VARIANT, new Vector2f(from.getLocation()), facing);
            } finally {
                fleet.setSuppressDeploymentMessages(quiet);
            }
            fleet.removeDeployed(ship, false);
            ship.setCustomData(AmmoRules.SHUTTLE_KEY, Boolean.TRUE);
            ship.setCustomData("tco_ammo_done", Boolean.TRUE);
            ship.setCollisionClass(CollisionClass.FIGHTER);
            ship.setInvalidTransferCommandTarget(true);
            ship.getMutableStats().getMaxSpeed().modifyMult(HULLMOD_ID, TcoSettings.boardingShuttleSpeedMult);
            ship.getMutableStats().getAcceleration().modifyMult(HULLMOD_ID, TcoSettings.boardingShuttleSpeedMult);
            toughen(ship);
            Craft craft = new Craft();
            craft.ship = ship;
            craft.ai = new ShuttleAI(ship);
            craft.ai.setRaiseShield(true);
            craft.source = home;
            craft.target = from;
            craft.marines = troops;
            craft.droids = droids;
            craft.playerSide = playerSide;
            craft.playerOwned = playerSide && !droids;
            craft.leg = Leg.RETURN;
            ship.setShipAI(craft.ai);
            ship.setAnimatedLaunch();
            ship.setLaunchingShip(from);
            craft.ai.flyTo(home.getLocation());
            crafts.add(craft);
            return true;
        } catch (RuntimeException e) {
            Global.getLogger(BoardingCombatPlugin.class).warn("TCO: could not launch a shuttle home", e);
            return false;
        }
    }

    private void takeControl(ShipAPI ship) {
        try {
            engine.getFleetManager(ship.getOwner()).removeDeployed(ship, false);
        } catch (RuntimeException e) {
            Global.getLogger(BoardingCombatPlugin.class).warn("TCO: could not take the ship off its roster", e);
        }
        ship.setOwner(0);
        ship.setOriginalOwner(0);
        for (ShipAPI module : ship.getChildModulesCopy()) {
            module.setOwner(0);
            module.setOriginalOwner(0);
        }
        ship.setShipTarget(null);
        ship.setHoldFire(false);
        ship.resetDefaultAI();
        ship.setCustomData("tco_converted", Boolean.TRUE);
        rememberLoyalCraft(ship);
        garrisons.remove(ship);
        FleetMemberAPI member = ship.getFleetMember();
        if (member != null) {
            capturedThisBattle.add(member);
            BoardingResults.captured.add(member);
        }
    }

    private void disableAutomated(ShipAPI ship) {
        try {
            engine.getFleetManager(ship.getOwner()).removeDeployed(ship, false);
        } catch (RuntimeException e) {
            Global.getLogger(BoardingCombatPlugin.class).warn("TCO: could not take the ship off its roster", e);
        }
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        if (power != null) power.shutDown(ship);
        if (ship.getEngineController() != null) ship.getEngineController().forceFlameout(true);
        if (ship.getShield() != null && ship.getShield().isOn()) ship.getShield().toggleOff();
        ship.setShipTarget(null);
        ship.setHoldFire(true);
        ship.setShipAI(new InertAI());
        ship.setCustomData(CAPTURED_KEY, Boolean.TRUE);
        rememberLoyalCraft(ship);
        ship.setOwner(100);
        for (ShipAPI module : ship.getChildModulesCopy()) {
            module.setOwner(100);
            module.setHoldFire(true);
        }
        garrisons.remove(ship);
        FleetMemberAPI member = ship.getFleetMember();
        if (member != null) BoardingResults.disabled.add(member);
    }

    // ------------------------------------------------------------------ capture

    /** Taken by Threat fragments: everyone aboard is killed and the ship left drifting, of use to no one. */
    private void scour(Fight fight) {
        ShipAPI ship = fight.ship;
        boolean playerAboard = ship.getOwner() == 0 && ejection == null && RtsCombatPlugin.playerAboard(engine, ship);
        FleetMemberAPI member = ship.getFleetMember();
        if (isPlayerShip(ship) && member != null) {
            // Its marines died with the crew
            lostShips.add(keyOf(ship));
            float crew = member.getCrewComposition() != null ? member.getCrewComposition().getCrew() : member.getMinCrew();
            threatCrewKilled += Math.max(0f, crew);
        }
        message(ship, "Threat fragments have killed everyone aboard " + ship.getName() + " and left it drifting.");
        disableAutomated(ship);
        if (playerAboard) eject(ship);
    }

    private void capture(Fight fight) {
        if (fight.fragmentAttackers >= fight.attackers * 0.5f) {
            scour(fight);
            return;
        }
        ShipAPI ship = fight.ship;
        int newOwner = ship.getOwner() == 0 ? 1 : 0;
        boolean retake = canRetake(ship, newOwner);
        boolean ours = isPlayerShip(ship);
        boolean playerAboard = newOwner == 1 && ejection == null
                && RtsCombatPlugin.playerAboard(engine, ship);
        // The one capture that can still be undone
        if (retake) ship.setCustomData(RETAKEN_KEY, Boolean.TRUE);
        else ship.setCustomData(RETAKE_BY_KEY, ship.getOwner());
        Iterator<Prize> old = prizes.iterator();
        while (old.hasNext()) {
            if (old.next().ship == ship) old.remove();
        }
        try {
            // Off the enemy roster
            engine.getFleetManager(ship.getOwner()).removeDeployed(ship, false);
        } catch (RuntimeException e) {
            Global.getLogger(BoardingCombatPlugin.class).warn("TCO: could not take the ship off its roster", e);
        }

        // Home is where each side deployed
        float playerHome = homeSign != 0f ? homeSign : -1f;
        float sign = newOwner == 0 ? playerHome : -playerHome;

        ship.setCustomData(CAPTURED_KEY, Boolean.TRUE);
        ship.setOwner(newOwner);
        ship.setOriginalOwner(newOwner);
        for (ShipAPI module : ship.getChildModulesCopy()) {
            module.setOwner(newOwner);
            module.setOriginalOwner(newOwner);
        }
        ship.setShipTarget(null);
        ship.setHoldFire(true);
        ship.setInvalidTransferCommandTarget(true);
        if (ship.getShield() != null && ship.getShield().isOn()) ship.getShield().toggleOff();
        // Whoever boards it back fights the boarders left aboard
        garrisons.put(ship, Math.max(0f, fight.attackers));
        rememberLoyalCraft(ship);

        Prize prize = new Prize();
        prize.ship = ship;
        prize.ai = new ShuttleAI(ship);
        prize.marines = fight.attackers;
        prize.playerMarines = fight.playerShare;
        prize.sign = sign;
        ship.setShipAI(prize.ai);
        prizes.add(prize);
        FleetMemberAPI member = ship.getFleetMember();
        if (retake) {
            // Back to the side it was taken from; it leaves the battle
            if (member != null && newOwner == 0) {
                lostThisBattle.remove(member);
                BoardingResults.lost.remove(member);
                if (!BoardingResults.retaken.contains(member)) BoardingResults.retaken.add(member);
            } else if (member != null) {
                capturedThisBattle.remove(member);
                BoardingResults.captured.remove(member);
            }
            if (newOwner == 0) {
                Msg.send(engine, Msg.PLAYER, ship.getName() + " has been retaken and is leaving the battle.");
                if (prizeLabels.containsKey(ship)) banner(ship.getName() + " RETAKEN", new Color(120, 230, 120), false);
            } else {
                message(ship, "Enemy boarders have retaken " + ship.getName() + ". It is leaving the battle.");
            }
        } else if (newOwner == 1) {
            // Ours, taken by the enemy
            if (member != null) {
                lostThisBattle.add(member);
                BoardingResults.lost.add(member);
            }
            message(ship, ship.getName() + " has been captured by enemy boarders! Board it before it escapes to retake it.");
            if (ours) {
                banner(ship.getName() + " CAPTURED BY ENEMY BOARDERS - board it before it escapes", new Color(255, 90, 75), true);
                prizeLabels.put(ship, null);
            }
            if (playerAboard) eject(ship);
        } else {
            // Taken mostly by player marines: joins the fleet. By allies: just leaves.
            if (member != null && fight.playerShare >= fight.attackers * 0.5f) {
                capturedThisBattle.add(member);
                BoardingResults.captured.add(member);
            }
            message(ship, ship.getName() + " has surrendered and is leaving the battle.");
        }
    }

    private void updatePrizes(float amount) {
        Iterator<Prize> it = prizes.iterator();
        while (it.hasNext()) {
            Prize prize = it.next();
            ShipAPI ship = prize.ship;
            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) {
                if (prize.playerMarines >= 1f) {
                    message(ship, ship.getName() + " was destroyed after its capture, with "
                            + Math.round(prize.playerMarines) + " marines aboard.");
                }
                if (ship.getFleetMember() != null) {
                    capturedThisBattle.remove(ship.getFleetMember());
                    BoardingResults.captured.remove(ship.getFleetMember());
                    lostThisBattle.remove(ship.getFleetMember());
                    BoardingResults.lost.remove(ship.getFleetMember());
                }
                it.remove();
                continue;
            }

            float edge = engine.getMapHeight() / 2f;
            Vector2f loc = ship.getLocation();
            prize.ai.flyTo(new Vector2f(loc.x, prize.sign * (edge + 2000f)));
            ship.setHoldFireOneFrame(true);
            if (loc.y * prize.sign >= edge - 150f) {
                marinesSafe += prize.playerMarines;
                prize.marines = 0f;
                engine.removeEntity(ship);
                it.remove();
            }
        }
    }

    private void message(ShipAPI about, String text) {
        Msg.about(engine, about, text);
    }

    private void banner(String text, Color color, boolean alarm) {
        bannerText = text;
        bannerColor = color;
        bannerUntil = engine.getTotalElapsedTime(false) + 8f;
        try {
            Global.getSoundPlayer().playUISound(alarm ? "cr_playership_critical" : "cr_allied_warning", 1f, 1f);
        } catch (RuntimeException e) {
        }
    }

    // ------------------------------------------------------------------ AI boarding

    private float alreadyCommitted(ShipAPI target, int side) {
        float total = 0f;
        Fight fight = fights.get(target);
        if (fight != null && target.getOwner() != side) total += fight.attackers;
        for (Craft craft : crafts) {
            if (craft.target == target && craft.leg == Leg.OUTBOUND && craft.ship.getOwner() == side) total += craft.marines;
        }
        return total;
    }

    private float expectedDefence(ShipAPI target) {
        Fight fight = fights.get(target);
        if (fight != null) return fight.defenders + fight.crew * TcoSettings.crewStrength;
        float condition = 0.4f + 0.6f * Math.max(0f, Math.min(1f, target.getHullLevel()));
        if (isAutomated(target)) return droidsFor(target) * condition * TcoSettings.droidStrength;
        float marines = isPlayerShip(target) ? marinesAboard(target) : expectedGarrison(target);
        return marines + securityLeft(target) * TcoSettings.securityDroidStrength
                + crewOf(target) * condition * TcoSettings.crewStrength;
    }

    private final Map<ShipAPI, Float> securityDroids = new WeakHashMap<ShipAPI, Float>();

    private float securityLeft(ShipAPI ship) {
        if (!SecurityForge.has(ship) || isAutomated(ship)) return 0f;
        Float left = securityDroids.get(ship);
        if (left != null) return left;
        // The player's ships start with what the forge has rebuilt since their last fight
        FleetMemberAPI member = ship.getFleetMember();
        if (isPlayerShip(ship) && member != null && engine.isInCampaign() && !engine.isSimulation()) {
            float now = SecurityDroids.current(member);
            securityDroids.put(ship, now);
            return now;
        }
        return SecurityForge.droidsFor(ship.getHullSize());
    }

    /** Droids left on the player's forge ships go into the save; the forge rebuilds them from there. */
    private void storeSecurityDroids() {
        if (!engine.isInCampaign() || engine.isSimulation()) return;
        // Fights still going count what's left now
        for (Fight fight : fights.values()) {
            if (SecurityForge.has(fight.ship)) securityDroids.put(fight.ship, Math.max(0f, Math.min(fight.secDroids, fight.defenders)));
        }
        for (Map.Entry<ShipAPI, Float> entry : securityDroids.entrySet()) {
            ShipAPI ship = entry.getKey();
            FleetMemberAPI member = ship.getFleetMember();
            if (member == null || !isPlayerShip(ship) || !SecurityForge.has(ship)) continue;
            SecurityDroids.store(member, ship.isAlive() && !ship.isHulk() ? entry.getValue() : 0f);
        }
    }

    private void keepGarrison(Fight fight) {
        fight.secDroids = Math.max(0f, Math.min(fight.secDroids, fight.defenders));
        if (SecurityForge.has(fight.ship)) securityDroids.put(fight.ship, fight.secDroids);
        if (!fight.playerDefends) garrisons.put(fight.ship, Math.max(0f, fight.defenders - fight.secDroids));
    }

    private float weakness(ShipAPI target, float wave, int side) {
        float score = 0f;
        float hull = target.getHullLevel();
        if (hull < 0.6f) score += (0.6f - hull) * 3f;
        if (target.getFluxTracker() != null && target.getFluxTracker().isOverloadedOrVenting()) score += 1f;
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        if (power != null && power.isMeltedDown(target)) score += 2f;
        if (target.getEngineController() != null && target.getEngineController().isFlamedOut()) score += 1f;
        if (expectedDefence(target) < wave * 0.5f) score += 1f;
        if (alreadyCommitted(target, side) > 0f) score += 1.5f;
        return score;
    }

    /**
     * AI Threat ships board on their own, once a second: wave after wave of fragments at the nearest weak enemy in
     * range, without waiting for a winning margin, until about three times its expected defence is on the way or aboard.
     */
    private void threatBoarding() {
        if (!TcoSettings.threatBoarding) return;
        int per = Math.max(1, TcoSettings.threatFragmentsPerCraft);
        for (ShipAPI source : engine.getShips()) {
            if (!ThreatBoarding.isThreat(source) || isPlayerShip(source)) continue;
            if (source.getOwner() != 0 && source.getOwner() != 1) continue;
            if (!source.isAlive() || source.isHulk() || source.isRetreating() || cooldownOf(source) > 0f) continue;
            if (source.getCustomData().containsKey(CAPTURED_KEY) || DeathThroes.isDying(source)) continue;
            int spare = ThreatBoarding.spare(source);
            if (spare < per) continue;
            float wave = Math.min(shuttlesPerWave(source), spare / per) * per * TcoSettings.threatFragmentStrength;
            ShipAPI best = null;
            float bestScore = 0f;
            for (ShipAPI target : engine.getShips()) {
                if (!isBoardable(target, source.getOwner()) || isProtected(target)) continue;
                if (!AiAwareness.knowsShip(engine, source, target)) continue;
                float dist = distance(source.getLocation(), target.getLocation())
                        - source.getCollisionRadius() - target.getCollisionRadius();
                if (dist > TcoSettings.boardingLaunchRange) continue;
                float committed = alreadyCommitted(target, source.getOwner()) * TcoSettings.threatFragmentStrength;
                if (committed > expectedDefence(target) * 3f) continue;
                float score = (1f + weakness(target, wave, source.getOwner())) * 1000f / (Math.max(0f, dist) + 500f);
                if (score > bestScore) {
                    bestScore = score;
                    best = target;
                }
            }
            if (best == null) continue;
            launchWave(source, best);
            if (best.getOwner() == 0 && !warned.containsKey(best)) {
                warned.put(best, 15f);
                message(best, "Threat fragments are swarming towards " + best.getName() + "!");
            }
        }
    }

    private boolean isProtected(ShipAPI target) {
        return false;
    }

    // ------------------------------------------------------------------ the player's shuttle

    /**
     * The player's ship was taken: their shuttle pod flies to the nearest safe ship and command moves there,
     * or it leaves by the player's edge.
     */
    private void eject(ShipAPI from) {
        boolean pilot = RtsCombatPlugin.leaveLostShip(engine);
        boolean vanilla = RtsCombatPlugin.isVanillaMode(engine);
        ShipAPI pod;
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(0);
            boolean quiet = fleet.isSuppressDeploymentMessages();
            fleet.setSuppressDeploymentMessages(true);
            try {
                pod = fleet.spawnShipOrWing("shuttlepod_Hull", new Vector2f(from.getLocation()), from.getFacing());
            } finally {
                fleet.setSuppressDeploymentMessages(quiet);
            }
            fleet.removeDeployed(pod, false);
        } catch (RuntimeException e) {
            Global.getLogger(BoardingCombatPlugin.class).warn("TCO: could not launch the player's shuttle", e);
            ShipAPI next = nearestOwnShip(from.getLocation());
            if (next != null) finishEjection(null, next, pilot);
            return;
        }
        pod.setCustomData(AmmoRules.SHUTTLE_KEY, Boolean.TRUE);
        pod.setCustomData("tco_ammo_done", Boolean.TRUE);
        pod.setInvalidTransferCommandTarget(true);
        pod.setCollisionClass(CollisionClass.FIGHTER);
        pod.setAnimatedLaunch();
        pod.setLaunchingShip(from);

        Ejection e = new Ejection();
        e.pod = pod;
        e.ai = new ShuttleAI(pod);
        e.ai.setRaiseShield(true);
        e.pilot = pilot;
        e.playerShip = vanilla;
        pod.setShipAI(e.ai);
        if (vanilla) engine.setPlayerShipExternal(pod);
        ejection = e;
        e.dest = nearestOwnShip(pod.getLocation());
        Msg.send(engine, Msg.PLAYER, e.dest != null ? "You have abandoned ship. Your shuttle is making for " + e.dest.getName() + "."
                : "You have abandoned ship. With no ship to reach, your shuttle is leaving the battle.");
    }

    private void updateEjection(float amount) {
        Ejection e = ejection;
        ShipAPI pod = e.pod;
        if (!pod.isAlive() || pod.isHulk() || !engine.isEntityInPlay(pod)) {
            // Shot down: still reaches a ship
            ShipAPI next = nearestOwnShip(pod.getLocation());
            if (next != null) finishEjection(e, next, e.pilot);
            else ejection = null;
            return;
        }
        if (e.playerShip && engine.getPlayerShip() == pod) {
            // The engine runs no AI for the player's ship
            e.ai.advance(amount);
            if (engine.getCombatUI() != null) engine.getCombatUI().setDisablePlayerShipControlOneFrame(true);
        }
        e.retarget -= amount;
        if (e.retarget <= 0f || e.dest == null || !canTakeCommand(e.dest)) {
            e.retarget = 1f;
            e.dest = nearestOwnShip(pod.getLocation());
        }
        if (e.dest != null) {
            e.ai.flyTo(e.dest.getLocation());
            if (isNear(pod, e.dest)) {
                engine.removeEntity(pod);
                finishEjection(e, e.dest, e.pilot);
            }
            return;
        }
        float sign = homeSign != 0f ? homeSign : -1f;
        float edge = engine.getMapHeight() / 2f;
        e.ai.flyTo(new Vector2f(pod.getLocation().x, sign * (edge + 2000f)));
        if (pod.getLocation().y * sign >= edge - 150f) {
            engine.removeEntity(pod);
            ejection = null;
            Msg.send(engine, Msg.PLAYER, "Your shuttle has left the battle.");
        }
    }

    private void finishEjection(Ejection e, ShipAPI ship, boolean pilot) {
        ejection = null;
        RtsCombatPlugin.transferFlagship(engine, ship, pilot);
        BoardingResults.newFlagship = ship.getFleetMember();
        Msg.send(engine, Msg.PLAYER, "You have taken command aboard " + ship.getName() + ".");
    }

    private boolean canTakeCommand(ShipAPI ship) {
        if (!isPlayerShip(ship) || !ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) return false;
        if (ship.isFighter() || ship.isDrone() || ship.isShuttlePod() || ship.isStation() || ship.isStationModule()) return false;
        if (AmmoRules.isShuttle(ship) || SupplyFob.isFobPart(ship) || ship.isRetreating()) return false;
        if (ship.getCustomData().containsKey(CAPTURED_KEY)) return false;
        return !fights.containsKey(ship);
    }

    private ShipAPI nearestOwnShip(Vector2f from) {
        ShipAPI best = null;
        float bestDist = Float.MAX_VALUE;
        for (ShipAPI ship : engine.getShips()) {
            if (!canTakeCommand(ship)) continue;
            float dist = distance(from, ship.getLocation());
            if (dist < bestDist) {
                bestDist = dist;
                best = ship;
            }
        }
        return best;
    }

    /**
     * AI boarding, once a second: launch at the weakest enemy in range a full wave can take with a margin.
     * The player's ships only board on orders.
     */
    private void aiBoarding() {
        threatBoarding();
        if (!TcoSettings.aiBoardingEnabled) return;
        int per = Math.max(1, TcoSettings.marinesPerShuttle);
        for (ShipAPI source : engine.getShips()) {
            if (isPlayerShip(source) || (source.getOwner() != 0 && source.getOwner() != 1)) continue;
            if (!source.isAlive() || source.isHulk() || source.isFighter() || source.isRetreating()) continue;
            if (source.getCustomData().containsKey(CAPTURED_KEY) || !hasBay(source) || cooldownOf(source) > 0f) continue;
            Fight underAttack = fights.get(source);
            if (underAttack != null) continue;
            int aboard = marinesAboard(source);
            float wave = Math.min(shuttlesPerWave(source) * perShuttle(source), aboard);
            if (wave < 1f) continue;

            ShipAPI best = null;
            float bestScore = 0f;
            for (ShipAPI target : engine.getShips()) {
                if (!isBoardable(target, source.getOwner()) || isProtected(target)) continue;
                // Only ships its side has found
                if (!AiAwareness.knowsShip(engine, source, target)) continue;
                float dist = distance(source.getLocation(), target.getLocation())
                        - source.getCollisionRadius() - target.getCollisionRadius();
                if (dist > TcoSettings.boardingLaunchRange) continue;
                float total = wave + alreadyCommitted(target, source.getOwner());
                if (total < expectedDefence(target) * TcoSettings.aiBoardingMargin) continue;
                float weak = weakness(target, wave, source.getOwner());
                if (weak <= 0f) continue;
                float score = weak * 1000f / (Math.max(0f, dist) + 500f);
                if (score > bestScore) {
                    bestScore = score;
                    best = target;
                }
            }
            if (best == null) continue;
            launchWave(source, best);
            if (best.getOwner() == 0 && !warned.containsKey(best)) {
                warned.put(best, 15f);
                message(best, "Enemy boarding shuttles are heading for " + best.getName() + "!");
            }
        }
    }

    /**
     * Ships fighting alongside boarders switch to suppressive fire on that ship and stop chasing it.
     * Not the player's ship or units ordered to attack it.
     */
    private void suppress() {
        for (Fight fight : fights.values()) {
            ShipAPI boarded = fight.ship;
            if (fight.attackers < 1f || !boarded.isAlive()) continue;
            int side = boarded.getOwner() == 0 ? 1 : 0;
            for (ShipAPI ship : engine.getShips()) {
                if (ship.getOwner() != side || !ship.isAlive() || ship.isHulk() || AiUtil.playerFlying(engine, ship)) continue;
                if (RtsCombatPlugin.attacking(engine, ship, boarded)) continue;
                for (WeaponGroupAPI group : ship.getWeaponGroupsCopy()) {
                    for (WeaponAPI weapon : group.getWeaponsCopy()) {
                        if (isSuppressive(weapon)) continue;
                        AutofireAIPlugin auto = group.getAutofirePlugin(weapon);
                        ShipAPI aim = auto == null ? null : auto.getTargetShip();
                        if (aim == null && ship.getShipTarget() == boarded && !group.isAutofiring()) aim = boarded;
                        if (aim == boarded || (aim != null && aim.getParentStation() == boarded)) weapon.setForceNoFireOneFrame(true);
                    }
                }
                if (ship.isFighter() || !AiUtil.isGameAi(ship)) continue;
                if (ship.getShipTarget() == boarded) ship.setShipTarget(null);
                if (distance(ship.getLocation(), boarded.getLocation()) < 1200f + ship.getCollisionRadius() + boarded.getCollisionRadius()) {
                    ShipwideAIFlags flags = ship.getAIFlags();
                    if (flags != null) {
                        flags.setFlag(ShipwideAIFlags.AIFlags.DO_NOT_PURSUE, 0.5f);
                        flags.setFlag(ShipwideAIFlags.AIFlags.BACK_OFF, 0.5f);
                    }
                }
            }
        }
    }

    private static boolean isSuppressive(WeaponAPI weapon) {
        if (weapon.hasAIHint(WeaponAPI.AIHints.PD) || weapon.hasAIHint(WeaponAPI.AIHints.PD_ONLY)) return true;
        WeaponAPI.DerivedWeaponStatsAPI stats = weapon.getDerivedStats();
        if (stats != null && stats.getEmpPerShot() > stats.getDamagePerShot()) return true;
        if (weapon.getType() == WeaponAPI.WeaponType.MISSILE) return false;
        return weapon.getSize() == WeaponAPI.WeaponSize.SMALL
                && weapon.getDamageType() != DamageType.HIGH_EXPLOSIVE;
    }

    private static float distance(Vector2f a, Vector2f b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (engine == null || crafts.isEmpty()) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(1.5f);
        GL11.glColor4f(0.85f, 0.45f, 1f, 0.9f);
        for (Craft craft : crafts) {
            Vector2f loc = craft.ship.getLocation();
            if (!viewport.isNearViewport(loc, 100f) || FogOfWar.isHidden(craft.ship)) continue;
            GL11.glBegin(GL11.GL_LINE_LOOP);
            for (int i = 0; i < 4; i++) {
                double a = Math.PI * 2.0 * i / 4.0;
                GL11.glVertex2f(loc.x + (float) Math.cos(a) * 30f, loc.y + (float) Math.sin(a) * 30f);
            }
            GL11.glEnd();
        }
        GL11.glPopAttrib();
    }

    /** Boarding log for the RTS panel: "m:ss text" with +1/-1/0 for the player's side. */
    public List<Object[]> logOf(ShipAPI ship) {
        List<Object[]> out = new ArrayList<Object[]>();
        Fight fight = fights.get(ship);
        if (fight == null || !playerCares(fight)) return out;
        boolean oursAttack = ship.getOwner() != 0;
        for (Object[] entry : fight.log) {
            int seconds = Math.round((Float) entry[0]);
            int good = (Integer) entry[2];
            out.add(new Object[] {String.format("%d:%02d  ", seconds / 60, seconds % 60) + entry[1], oursAttack ? good : -good});
        }
        return out;
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        if (engine == null || Global.getCurrentState() != GameState.COMBAT) return;
        if (engine.getCombatUI() == null || engine.getCombatUI().isShowingCommandUI()) return;
        renderBanner();
        renderPrizeMarkers(viewport);
        if (fights.isEmpty()) return;

        for (Fight fight : fights.values()) {
            ShipAPI ship = fight.ship;
            Vector2f loc = ship.getLocation();
            if (!viewport.isNearViewport(loc, ship.getCollisionRadius() + 200f) || FogOfWar.isHidden(ship)) continue;
            String order = fight.rigged != null ? " (" + fight.rigged.toUpperCase() + " RIGGED " + (int) Math.ceil(fight.riggedIn) + ")"
                    : fight.withdrawIn >= 0f ? " (withdrawing " + (int) Math.ceil(fight.withdrawIn) + ")"
                    : fight.order == BoardOrder.STANDARD ? "" : " (" + fight.order.label + ")";
            String deck = "";
            if (fight.decks != null) {
                int held = fight.defenders <= 0f ? fight.decks.size() : fight.taken;
                deck = "  decks " + held + "/" + fight.decks.size()
                        + (held < fight.decks.size() ? ", next " + fight.decks.get(held).name : ", at the " + fight.decks.get(fight.decks.size() - 1).name);
            }
            String text = "Boarding" + order + ": " + Math.round(fight.attackers) + " " + attackerWord(fight) + " vs "
                    + (fight.defenders > 0f ? Math.round(fight.defenders) + " " + defenderWord(fight)
                            : Math.round(fight.crew) + " crew") + deck;
            try {
                if (fight.label == null) {
                    fight.label = Global.getSettings().createLabel(text, Fonts.DEFAULT_SMALL);
                    fight.labelText = text;
                } else if (!text.equals(fight.labelText)) {
                    fight.label.setText(text);
                    fight.labelText = text;
                }
                float x = viewport.convertWorldXtoScreenX(loc.x);
                float y = viewport.convertWorldYtoScreenY(loc.y + ship.getCollisionRadius() + 40f);
                fight.label.setColor(new Color(225, 150, 255));
                fight.label.getPosition().setSize(460f, 16f);
                fight.label.getPosition().setLocation(x - 180f, y);
                fight.label.render(1f);
            } catch (RuntimeException e) {
                fight.label = null;
            }
        }
    }

    private void renderBanner() {
        if (bannerText == null) return;
        float now = engine.getTotalElapsedTime(false);
        if (now > bannerUntil) {
            bannerText = null;
            return;
        }
        try {
            if (bannerLabel == null) bannerLabel = Global.getSettings().createLabel(bannerText, Fonts.ORBITRON_24AABOLD);
            if (!bannerText.equals(bannerShown)) {
                bannerLabel.setText(bannerText);
                bannerShown = bannerText;
            }
            float w = Global.getSettings().getScreenWidth();
            float h = Global.getSettings().getScreenHeight();
            bannerLabel.setAlignment(Alignment.MID);
            bannerLabel.setColor(bannerColor);
            bannerLabel.getPosition().setSize(w - 100f, 30f);
            bannerLabel.getPosition().setLocation(50f, h * 0.72f);
            // Pulses, then fades over the last second
            float pulse = 0.65f + 0.35f * (float) Math.abs(Math.sin(now * 4f));
            bannerLabel.render(pulse * Math.min(1f, bannerUntil - now));
        } catch (RuntimeException e) {
            bannerLabel = null;
            bannerText = null;
        }
    }

    /** "CAPTURED" over each of the player's ships the enemy is making off with. */
    private void renderPrizeMarkers(ViewportAPI viewport) {
        if (prizeLabels.isEmpty()) return;
        Iterator<Map.Entry<ShipAPI, LabelAPI>> it = prizeLabels.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ShipAPI, LabelAPI> e = it.next();
            ShipAPI ship = e.getKey();
            if (!ship.isAlive() || !engine.isEntityInPlay(ship) || ship.getCustomData().containsKey(RETAKEN_KEY)) {
                it.remove();
                continue;
            }
            Vector2f loc = ship.getLocation();
            if (fights.containsKey(ship) || !viewport.isNearViewport(loc, ship.getCollisionRadius() + 200f)
                    || FogOfWar.isHidden(ship)) continue;
            try {
                LabelAPI label = e.getValue();
                if (label == null) {
                    label = Global.getSettings().createLabel("CAPTURED - board it to retake it", Fonts.DEFAULT_SMALL);
                    e.setValue(label);
                }
                float x = viewport.convertWorldXtoScreenX(loc.x);
                float y = viewport.convertWorldYtoScreenY(loc.y + ship.getCollisionRadius() + 40f);
                label.setColor(new Color(255, 90, 75));
                label.getPosition().setSize(460f, 16f);
                label.getPosition().setLocation(x - 110f, y);
                label.render(1f);
            } catch (RuntimeException ex) {
                it.remove();
            }
        }
    }
}
