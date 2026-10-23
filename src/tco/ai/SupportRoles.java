package tco.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import tco.TcoSettings;
import tco.ammo.AmmoRules;
import tco.boarding.BoardingCombatPlugin;
import tco.repair.RepairCombatPlugin;

/**
 * Per-battle support roles for AI fleets: a supply and tender quota by fleet size, filled from
 * logistics hulls first, then the cheapest warships. Ships themselves are not changed.
 */
public final class SupportRoles {

    public enum Role { SUPPLY, TENDER, BOARDER }

    private static final String ROLES_KEY = "tco_support_roles";
    private static final String SIDES_KEY = "tco_support_sides";

    private SupportRoles() {
    }

    public static boolean isSupplier(ShipAPI ship) {
        if (ship.getVariant() != null && ship.getVariant().hasHullMod(AmmoRules.HULLMOD_ID)) return true;
        return roleOf(ship) == Role.SUPPLY;
    }

    public static boolean isTender(ShipAPI ship) {
        if (ship.getVariant() != null && ship.getVariant().hasHullMod(RepairCombatPlugin.HULLMOD_ID)) return true;
        return roleOf(ship) == Role.TENDER;
    }

    public static boolean isBoarder(ShipAPI ship) {
        if (ship.getVariant() != null && ship.getVariant().hasHullMod(BoardingCombatPlugin.HULLMOD_ID)) return true;
        return roleOf(ship) == Role.BOARDER;
    }

    public static boolean isCivilian(ShipAPI ship) {
        if (ship.getVariant() != null && ship.getVariant().hasHullMod("civgrade")) return true;
        ShipHullSpecAPI spec = ship.getHullSpec();
        return spec != null && spec.getHints().contains(ShipTypeHints.CIVILIAN);
    }

    public static Role roleOf(ShipAPI ship) {
        CombatEngineAPI engine = Global.getCombatEngine();
        if (engine == null || !TcoSettings.supportRolesEnabled) return null;
        if (ship.isFighter() || ship.isDrone() || ship.isShuttlePod() || ship.isStation() || ship.isStationModule()) return null;
        if (AmmoRules.isShuttle(ship)) return null;
        String id = ship.getFleetMemberId();
        if (id == null) return null;
        int side = ship.getOwner();
        if (side != 0 && side != 1) return null;
        Map<String, Role> roles = roles(engine);
        // New ships seen: top up the side's roles
        Set<String> considered = considered(engine);
        if (!considered.contains(id)) {
            decide(engine, side, roles, considered);
            considered.add(id);
        }
        return roles.get(id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Role> roles(CombatEngineAPI engine) {
        Object stored = engine.getCustomData().get(ROLES_KEY);
        if (stored instanceof Map) return (Map<String, Role>) stored;
        Map<String, Role> roles = new HashMap<String, Role>();
        engine.getCustomData().put(ROLES_KEY, roles);
        return roles;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> considered(CombatEngineAPI engine) {
        Object stored = engine.getCustomData().get(SIDES_KEY);
        if (stored instanceof Set) return (Set<String>) stored;
        Set<String> ids = new HashSet<String>();
        engine.getCustomData().put(SIDES_KEY, ids);
        return ids;
    }

    private static void decide(CombatEngineAPI engine, int side, Map<String, Role> roles, Set<String> considered) {
        CombatFleetManagerAPI fleet = engine.getFleetManager(side);
        if (fleet == null) return;
        List<FleetMemberAPI> members = new ArrayList<FleetMemberAPI>();
        List<FleetMemberAPI> all = new ArrayList<FleetMemberAPI>(fleet.getDeployedCopy());
        all.addAll(fleet.getReservesCopy());
        Set<String> seen = new HashSet<String>();
        for (FleetMemberAPI member : all) {
            if (member == null || member.isFighterWing() || member.isStation() || !seen.add(member.getId())) continue;
            // The player picks their own
            if (side == 0 && !member.isAlly()) continue;
            members.add(member);
        }
        List<FleetMemberAPI> newcomers = new ArrayList<FleetMemberAPI>();
        for (FleetMemberAPI member : members) {
            if (considered.add(member.getId())) newcomers.add(member);
        }
        if (members.size() < TcoSettings.supportMinFleet || newcomers.isEmpty()) return;

        int supplyNeeded = Math.max(1, Math.round(members.size() / Math.max(1f, TcoSettings.supportShipsPer)));
        int tenderNeeded = supplyNeeded;
        int boarderNeeded = TcoSettings.aiBoardingEnabled && TcoSettings.boardingEnabled
                ? Math.max(1, Math.round(members.size() / Math.max(1f, TcoSettings.boarderShipsPer))) : 0;
        // Ships with a bay or role count toward the quota
        List<FleetMemberAPI> free = new ArrayList<FleetMemberAPI>();
        for (FleetMemberAPI member : members) {
            Role given = roles.get(member.getId());
            if (member.getVariant() != null && member.getVariant().hasHullMod(AmmoRules.HULLMOD_ID)) supplyNeeded--;
            else if (member.getVariant() != null && member.getVariant().hasHullMod(RepairCombatPlugin.HULLMOD_ID)) tenderNeeded--;
            else if (member.getVariant() != null && member.getVariant().hasHullMod(BoardingCombatPlugin.HULLMOD_ID)) boarderNeeded--;
            else if (given == Role.SUPPLY) supplyNeeded--;
            else if (given == Role.TENDER) tenderNeeded--;
            else if (given == Role.BOARDER) boarderNeeded--;
            else if (newcomers.contains(member)) free.add(member);
        }

        Random random = new Random();
        List<FleetMemberAPI> logistics = new ArrayList<FleetMemberAPI>();
        List<FleetMemberAPI> warships = new ArrayList<FleetMemberAPI>();
        for (FleetMemberAPI member : free) {
            if (isLogistics(member)) logistics.add(member);
            else warships.add(member);
        }
        Collections.shuffle(logistics, random);
        for (FleetMemberAPI member : logistics) {
            if (supplyNeeded <= 0 && tenderNeeded <= 0) break;
            if (random.nextFloat() >= TcoSettings.supportLogisticsChance) continue;
            if (supplyNeeded >= tenderNeeded) {
                roles.put(member.getId(), Role.SUPPLY);
                supplyNeeded--;
            } else {
                roles.put(member.getId(), Role.TENDER);
                tenderNeeded--;
            }
        }

        // Still short: cheapest warships, frigates, capitals and the flagship last
        Collections.sort(warships, new Comparator<FleetMemberAPI>() {
            @Override
            public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                int pa = penalty(a);
                int pb = penalty(b);
                if (pa != pb) return pa - pb;
                return Float.compare(a.getDeploymentPointsCost(), b.getDeploymentPointsCost());
            }
        });
        for (FleetMemberAPI member : warships) {
            if (supplyNeeded <= 0 && tenderNeeded <= 0) break;
            if (supplyNeeded >= tenderNeeded) {
                roles.put(member.getId(), Role.SUPPLY);
                supplyNeeded--;
            } else {
                roles.put(member.getId(), Role.TENDER);
                tenderNeeded--;
            }
        }
        // Boarders: cheapest warships left, never logistics hulls
        for (FleetMemberAPI member : warships) {
            if (boarderNeeded <= 0) break;
            if (roles.containsKey(member.getId())) continue;
            roles.put(member.getId(), Role.BOARDER);
            boarderNeeded--;
        }
    }

    private static int penalty(FleetMemberAPI member) {
        if (member.isFlagship()) return 3;
        HullSize size = member.getHullSpec() == null ? HullSize.FRIGATE : member.getHullSpec().getHullSize();
        if (size == HullSize.CAPITAL_SHIP) return 2;
        if (size == HullSize.FRIGATE) return 1;
        return 0;
    }

    private static boolean isLogistics(FleetMemberAPI member) {
        if (member.isCivilian()) return true;
        if (member.getVariant() != null && member.getVariant().hasHullMod("civgrade")) return true;
        ShipHullSpecAPI spec = member.getHullSpec();
        if (spec == null) return false;
        Set<ShipTypeHints> hints = spec.getHints();
        return hints.contains(ShipTypeHints.CIVILIAN) || hints.contains(ShipTypeHints.FREIGHTER)
                || hints.contains(ShipTypeHints.TANKER) || hints.contains(ShipTypeHints.LINER)
                || hints.contains(ShipTypeHints.TRANSPORT);
    }
}
