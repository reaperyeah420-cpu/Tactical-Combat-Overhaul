package tco.boarding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import tco.TcoSettings;
import tco.hullmods.ShuttleBayUpgrade;

/**
 * Spreads the fleet's marines over its ships from per-ship targets: priority ships first when short,
 * leftovers evenly, "no marines" ships last. Saved as a plain string map so saves load without TCO.
 */
public final class MarineAllocation {

    private static final String DATA_KEY = "tco_marine_assignments";

    /** One ship's settings; a negative target means default. */
    public static class Entry {
        public int target = -1;
        public boolean priority = false;
        public boolean excluded = false;

        public Entry copy() {
            Entry e = new Entry();
            e.target = target;
            e.priority = priority;
            e.excluded = excluded;
            return e;
        }
    }

    public static class Result {
        public final Map<String, Integer> assigned = new LinkedHashMap<String, Integer>();
        public int unassigned = 0;
    }

    private MarineAllocation() {
    }

    public static boolean isEligible(FleetMemberAPI member) {
        return member != null && !member.isFighterWing() && !member.isMothballed();
    }

    /** Crew capacity minus skeleton crew (more with High-Capacity Shuttles). */
    public static int capacity(FleetMemberAPI member) {
        float room = member.getMaxCrew() - member.getMinCrew();
        if (member.getVariant() != null && member.getVariant().hasHullMod(ShuttleBayUpgrade.HIGH_CAPACITY)) {
            room *= TcoSettings.hcShuttleCapacityMult;
        }
        return Math.max(0, Math.round(room));
    }

    /** Ships with a boarding bay want a full load, others none. */
    public static int defaultTarget(FleetMemberAPI member) {
        boolean bay = member.getVariant() != null && member.getVariant().hasHullMod(BoardingCombatPlugin.HULLMOD_ID);
        return bay ? capacity(member) : 0;
    }

    public static int targetOf(FleetMemberAPI member, Entry entry) {
        int target = entry == null || entry.target < 0 ? defaultTarget(member) : entry.target;
        return Math.max(0, Math.min(target, capacity(member)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> saved() {
        Map<String, Object> data = Global.getSector().getPersistentData();
        Object existing = data.get(DATA_KEY);
        if (existing instanceof Map) return (Map<String, String>) existing;
        Map<String, String> created = new HashMap<String, String>();
        data.put(DATA_KEY, created);
        return created;
    }

    public static Entry load(FleetMemberAPI member) {
        Entry entry = new Entry();
        try {
            String text = saved().get(member.getId());
            if (text == null) return entry;
            String[] parts = text.split(",");
            entry.target = Integer.parseInt(parts[0].trim());
            entry.priority = parts.length > 1 && "1".equals(parts[1].trim());
            entry.excluded = parts.length > 2 && "1".equals(parts[2].trim());
        } catch (RuntimeException e) {
            return new Entry();
        }
        return entry;
    }

    public static void save(FleetMemberAPI member, Entry entry) {
        saved().put(member.getId(), entry.target + "," + (entry.priority ? "1" : "0") + "," + (entry.excluded ? "1" : "0"));
    }

    public static Map<String, Entry> loadAll(List<FleetMemberAPI> members) {
        Map<String, Entry> entries = new HashMap<String, Entry>();
        for (FleetMemberAPI member : members) {
            if (isEligible(member)) entries.put(member.getId(), load(member));
        }
        return entries;
    }

    public static Result compute(List<FleetMemberAPI> members, int marines) {
        return compute(members, marines, loadAll(members));
    }

    public static Result compute(List<FleetMemberAPI> members, int marines, Map<String, Entry> entries) {
        Result result = new Result();
        List<FleetMemberAPI> normal = new ArrayList<FleetMemberAPI>();
        List<FleetMemberAPI> priority = new ArrayList<FleetMemberAPI>();
        List<FleetMemberAPI> ordinary = new ArrayList<FleetMemberAPI>();
        List<FleetMemberAPI> excluded = new ArrayList<FleetMemberAPI>();
        Map<String, Integer> targets = new HashMap<String, Integer>();

        for (FleetMemberAPI member : members) {
            if (!isEligible(member)) continue;
            Entry entry = entries.get(member.getId());
            result.assigned.put(member.getId(), 0);
            if (entry != null && entry.excluded) {
                excluded.add(member);
                continue;
            }
            normal.add(member);
            targets.put(member.getId(), targetOf(member, entry));
            if (entry != null && entry.priority) priority.add(member);
            else ordinary.add(member);
        }

        int left = Math.max(0, marines);
        left = fillTargets(priority, targets, result.assigned, left);
        left = fillTargets(ordinary, targets, result.assigned, left);
        left = spreadEvenly(normal, result.assigned, left);
        left = spreadEvenly(excluded, result.assigned, left);
        result.unassigned = left;
        return result;
    }

    private static int fillTargets(List<FleetMemberAPI> ships, Map<String, Integer> targets,
            Map<String, Integer> assigned, int left) {
        int wanted = 0;
        for (FleetMemberAPI ship : ships) wanted += targets.get(ship.getId());
        if (wanted <= 0 || left <= 0) return left;

        if (left >= wanted) {
            for (FleetMemberAPI ship : ships) assigned.put(ship.getId(), targets.get(ship.getId()));
            return left - wanted;
        }

        int given = 0;
        for (FleetMemberAPI ship : ships) {
            int share = (int) ((long) left * targets.get(ship.getId()) / wanted);
            assigned.put(ship.getId(), share);
            given += share;
        }
        // Rounding leftovers to ships still short
        int spare = left - given;
        while (spare > 0) {
            boolean any = false;
            for (FleetMemberAPI ship : ships) {
                if (spare <= 0) break;
                int have = assigned.get(ship.getId());
                if (have < targets.get(ship.getId())) {
                    assigned.put(ship.getId(), have + 1);
                    spare--;
                    any = true;
                }
            }
            if (!any) break;
        }
        return spare;
    }

    private static int spreadEvenly(List<FleetMemberAPI> ships, Map<String, Integer> assigned, int left) {
        while (left > 0) {
            List<FleetMemberAPI> open = new ArrayList<FleetMemberAPI>();
            for (FleetMemberAPI ship : ships) {
                if (assigned.get(ship.getId()) < capacity(ship)) open.add(ship);
            }
            if (open.isEmpty()) break;
            int share = Math.max(1, left / open.size());
            for (FleetMemberAPI ship : open) {
                if (left <= 0) break;
                int have = assigned.get(ship.getId());
                int give = Math.min(share, Math.min(capacity(ship) - have, left));
                assigned.put(ship.getId(), have + give);
                left -= give;
            }
        }
        return left;
    }
}
