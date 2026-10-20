package tco.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.LocationAPI;
import com.fs.starfarer.api.characters.MutableCharacterStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.DModManager;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import tco.TcoSettings;
import tco.hullmods.SecurityForge;
import tco.missile.Guidance;

/**
 * Fits TCO's hullmods onto NPC ships (never the player's), once a fleet is inflated.
 * Fleets of two or more get a supply ship and a tender; military fleets may get boarders and a tug;
 * combat hullmods are rolled by size and design type. Rolls are seeded by member id, and OP comes
 * from opBonus first, then capacitors and vents.
 */
public final class NpcHullmods {

    public static final String FITTED_TAG = "tco_npc_fitted";
    private static final String RESUPPLY = "tco_resupply_bay";
    private static final String REPAIR = "tco_repair_bay";
    private static final String BOARDING = "tco_boarding_bay";
    private static final String TUG = "tco_tug_rig";
    private static final String DATALINK = "tco_datalink";

    private enum Tech { LOW, MID, HIGH }

    private NpcHullmods() {
    }

    /** Fits the fleets around the player, twice a second. */
    public static class Scanner implements EveryFrameScript {
        private float timer;

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public boolean runWhilePaused() {
            return true;
        }

        @Override
        public void advance(float amount) {
            timer -= Math.max(amount, 0.02f);
            if (timer > 0f) return;
            timer = 0.5f;
            if (Global.getSector() == null) return;
            opBonus(Global.getSector().getPlayerStats());
            if (!TcoSettings.npcHullmodsEnabled) return;
            LocationAPI here = Global.getSector().getCurrentLocation();
            if (here == null) return;
            for (CampaignFleetAPI fleet : here.getFleets()) apply(fleet);
        }
    }

    /** Fits a battle's fleets before it starts. */
    public static void applyToBattle(CampaignFleetAPI opponent) {
        if (!TcoSettings.npcHullmodsEnabled || opponent == null) return;
        apply(opponent);
        BattleAPI battle = opponent.getBattle();
        if (battle == null) return;
        try {
            for (CampaignFleetAPI fleet : battle.getSideOne()) apply(fleet);
            for (CampaignFleetAPI fleet : battle.getSideTwo()) apply(fleet);
        } catch (RuntimeException e) {
        }
    }

    private static final String OP_BONUS_ID = "tco_op_bonus";

    /** Bonus ordnance points, opBonusPercent of each hull's own. */
    public static void opBonus(MutableCharacterStatsAPI stats) {
        if (stats == null || stats.getShipOrdnancePointBonus() == null) return;
        if (TcoSettings.opBonusPercent > 0f) {
            stats.getShipOrdnancePointBonus().modifyPercent(OP_BONUS_ID, TcoSettings.opBonusPercent);
        } else {
            stats.getShipOrdnancePointBonus().unmodify(OP_BONUS_ID);
        }
    }

    public static void apply(CampaignFleetAPI fleet) {
        if (fleet == null || fleet.isPlayerFleet()) return;
        opBonus(fleet.getCommanderStats());
        if (!TcoSettings.npcHullmodsEnabled || !fleet.isInflated()) return;
        if (fleet.getFleetData() == null) return;
        List<FleetMemberAPI> members = new ArrayList<FleetMemberAPI>();
        List<FleetMemberAPI> fresh = new ArrayList<FleetMemberAPI>();
        for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
            if (member.isFighterWing() || member.isStation() || member.getVariant() == null) continue;
            if (member.getHullSpec() == null || member.getHullSpec().getHints().contains(ShipTypeHints.STATION)) continue;
            members.add(member);
            if (!member.getVariant().hasTag(FITTED_TAG)) fresh.add(member);
        }
        if (fresh.isEmpty()) return;
        try {
            fit(fleet, members, fresh);
        } catch (RuntimeException e) {
            Global.getLogger(NpcHullmods.class).warn("TCO: could not fit hullmods to fleet " + fleet.getName(), e);
        }
        for (FleetMemberAPI member : fresh) {
            own(member).addTag(FITTED_TAG);
            member.updateStats();
        }
    }

    private static void fit(CampaignFleetAPI fleet, List<FleetMemberAPI> members, List<FleetMemberAPI> fresh) {
        MutableCharacterStatsAPI stats = fleet.getCommanderStats();
        if (stats == null) stats = Global.getSector().getPlayerStats();
        String type = fleet.getMemoryWithoutUpdate().getString(MemFlags.MEMORY_KEY_FLEET_TYPE);
        boolean civilian = type != null && (type.startsWith("trade") || type.toLowerCase().contains("liner")
                || type.toLowerCase().contains("relief"));
        float mult = Math.max(0f, TcoSettings.npcHullmodChanceMult);
        Random fleetRandom = new Random(fleet.getId().hashCode() * 7919L + members.size());

        if (members.size() >= Math.max(2, TcoSettings.npcSupportMinFleet)) {
            int per = Math.max(1, Math.round(members.size() / Math.max(1f, TcoSettings.supportShipsPer)));
            int supply = per - count(members, RESUPPLY);
            int tender = per - count(members, REPAIR);
            List<FleetMemberAPI> candidates = supportCandidates(fresh);
            for (int pass = 0; pass < 2 && (supply > 0 || tender > 0); pass++) {
                for (FleetMemberAPI member : candidates) {
                    if (supply <= 0 && tender <= 0) break;
                    if (hasBay(member)) continue;
                    String bay = supply >= tender ? RESUPPLY : REPAIR;
                    // Second pass forces it onto the best candidate
                    if (install(member, bay, stats, pass == 1)) {
                        if (bay.equals(RESUPPLY)) supply--;
                        else tender--;
                    }
                }
            }
        }
        if (civilian) mult *= 0.25f;
        if (mult <= 0f) return;

        List<FleetMemberAPI> warships = new ArrayList<FleetMemberAPI>();
        for (FleetMemberAPI member : fresh) {
            if (!isLogistics(member) && !member.isFlagship()) warships.add(member);
        }
        sortCheapest(warships);
        if (!civilian) {
            int boarders = Math.round(members.size() / Math.max(1f, TcoSettings.boarderShipsPer)) - count(members, BOARDING);
            for (FleetMemberAPI member : warships) {
                if (boarders <= 0) break;
                if (hasBay(member) || member.getHullSpec().getHullSize() == HullSize.CAPITAL_SHIP) continue;
                if (fleetRandom.nextFloat() >= 0.6f * Math.min(1f, mult)) {
                    boarders--;
                    continue;
                }
                if (install(member, BOARDING, stats, false)) boarders--;
            }
            if (warships.size() >= 6 && count(members, TUG) == 0 && fleetRandom.nextFloat() < 0.5f * Math.min(1f, mult)) {
                for (FleetMemberAPI member : warships) {
                    HullSize size = member.getHullSpec().getHullSize();
                    if (size != HullSize.FRIGATE && size != HullSize.DESTROYER) continue;
                    if (install(member, TUG, stats, false)) break;
                }
            }
        }

        int radarShips = 0;
        for (FleetMemberAPI member : members) {
            if (carries(member.getVariant(), Guidance.Kind.RADAR)) radarShips++;
        }
        boolean linked = !civilian && radarShips >= 2
                && fleetRandom.nextFloat() < Math.min(0.85f, (0.3f + 0.1f * radarShips) * mult);

        for (FleetMemberAPI member : fresh) {
            ShipVariantAPI v = member.getVariant();
            Random r = new Random(member.getId().hashCode() * 31L + 17L);
            HullSize size = member.getHullSpec().getHullSize();
            Tech tech = techOf(member.getHullSpec());
            boolean logistics = isLogistics(member);
            float q = mult * Math.max(0.3f, 1f - 0.15f * DModManager.getNumDMods(v));
            boolean big = size == HullSize.CRUISER || size == HullSize.CAPITAL_SHIP;
            boolean small = size == HullSize.FRIGATE || size == HullSize.DESTROYER;
            boolean radar = carries(v, Guidance.Kind.RADAR);
            boolean ir = carries(v, Guidance.Kind.IR) || carries(v, Guidance.Kind.IR_ENGINE);
            int added = 0;

            if (linked && !logistics && r.nextFloat() < (radar ? 0.75f : 0.45f)) {
                if (install(member, DATALINK, stats, false)) added++;
            }
            if (logistics) {
                // Civilian hulls
                if (r.nextFloat() < 0.1f * q) install(member, "tco_ecd", stats, false);
                continue;
            }
            if (radar && roll(r, pick(tech, 0.15f, 0.25f, 0.45f) * (big ? 1.3f : small ? 0.7f : 1f) * q)) {
                if (install(member, "tco_aesa", stats, false)) added++;
            }
            if (ir && roll(r, 0.3f * q) && install(member, "tco_irccm", stats, false)) added++;
            if (small && added < 3 && roll(r, pick(tech, 0.04f, 0.1f, 0.2f) * q)) {
                if (install(member, "tco_ram", stats, false)) added++;
            }
            if (added < 3 && roll(r, (small ? 0.2f : 0.1f) * (pdCount(v) == 0 ? 1.5f : 1f) * q)) {
                if (install(member, "tco_ecd", stats, false)) added++;
            }
            // At most one heavy armor package
            if (added < 3) {
                if (roll(r, pick(tech, 0.2f, 0.08f, 0.02f) * (big ? 1.2f : 1f) * q)) {
                    if (install(member, "tco_era", stats, false)) added++;
                } else if (roll(r, pick(tech, 0.08f, 0.15f, 0.05f) * q)) {
                    if (install(member, "tco_composite", stats, false)) added++;
                }
            }
            if (added < 3 && size != HullSize.FRIGATE && roll(r, pick(tech, 0.15f, 0.12f, 0.06f) * q)) {
                if (install(member, "tco_spall", stats, false)) added++;
            }
            if (added < 3 && big && !member.getVariant().hasHullMod("automated") && roll(r, 0.12f * q)) {
                install(member, SecurityForge.ID, stats, false);
            }
        }
    }

    private static boolean roll(Random r, float chance) {
        return r.nextFloat() < chance;
    }

    private static float pick(Tech tech, float low, float mid, float high) {
        return tech == Tech.LOW ? low : tech == Tech.HIGH ? high : mid;
    }

    private static Tech techOf(ShipHullSpecAPI spec) {
        String m = spec.getManufacturer();
        if (m == null) return Tech.MID;
        m = m.toLowerCase();
        if (m.contains("high")) return Tech.HIGH;
        if (m.contains("low")) return Tech.LOW;
        return Tech.MID;
    }

    private static boolean install(FleetMemberAPI member, String id, MutableCharacterStatsAPI stats, boolean force) {
        ShipVariantAPI v = member.getVariant();
        if (v.hasHullMod(id)) return false;
        HullModSpecAPI spec;
        try {
            spec = Global.getSettings().getHullModSpec(id);
        } catch (RuntimeException e) {
            return false;
        }
        if (spec == null) return false;
        if (isBay(id) && hasBay(member)) return false;
        v = own(member);
        int cost = spec.getCostFor(member.getHullSpec().getHullSize());
        int caps = v.getNumFluxCapacitors(), vents = v.getNumFluxVents();
        int need = cost - v.getUnusedOP(stats);
        while (need > 0 && (v.getNumFluxCapacitors() > 0 || v.getNumFluxVents() > 0)) {
            if (v.getNumFluxCapacitors() >= v.getNumFluxVents() && v.getNumFluxCapacitors() > 0) {
                v.setNumFluxCapacitors(v.getNumFluxCapacitors() - 1);
            } else {
                v.setNumFluxVents(v.getNumFluxVents() - 1);
            }
            need--;
        }
        if (need > 0 && !force) {
            v.setNumFluxCapacitors(caps);
            v.setNumFluxVents(vents);
            return false;
        }
        v.addMod(id);
        return true;
    }

    private static ShipVariantAPI own(FleetMemberAPI member) {
        ShipVariantAPI v = member.getVariant();
        if (v.isStockVariant() || v.getSource() != VariantSource.REFIT) {
            v = v.clone();
            v.setSource(VariantSource.REFIT);
            member.setVariant(v, false, false);
        }
        return v;
    }

    private static boolean isBay(String id) {
        return RESUPPLY.equals(id) || REPAIR.equals(id) || BOARDING.equals(id);
    }

    private static boolean hasBay(FleetMemberAPI member) {
        ShipVariantAPI v = member.getVariant();
        return v.hasHullMod(RESUPPLY) || v.hasHullMod(REPAIR) || v.hasHullMod(BOARDING);
    }

    private static int count(List<FleetMemberAPI> members, String id) {
        int n = 0;
        for (FleetMemberAPI member : members) {
            if (member.getVariant().hasHullMod(id)) n++;
        }
        return n;
    }

    private static List<FleetMemberAPI> supportCandidates(List<FleetMemberAPI> fresh) {
        List<FleetMemberAPI> logistics = new ArrayList<FleetMemberAPI>();
        List<FleetMemberAPI> warships = new ArrayList<FleetMemberAPI>();
        for (FleetMemberAPI member : fresh) {
            if (isLogistics(member)) logistics.add(member);
            else warships.add(member);
        }
        sortCheapest(logistics);
        sortCheapest(warships);
        logistics.addAll(warships);
        return logistics;
    }

    private static void sortCheapest(List<FleetMemberAPI> list) {
        Collections.sort(list, new Comparator<FleetMemberAPI>() {
            @Override
            public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                int pa = penalty(a), pb = penalty(b);
                if (pa != pb) return pa - pb;
                return Float.compare(a.getDeploymentPointsCost(), b.getDeploymentPointsCost());
            }
        });
    }

    private static int penalty(FleetMemberAPI member) {
        if (member.isFlagship()) return 3;
        HullSize size = member.getHullSpec().getHullSize();
        if (size == HullSize.CAPITAL_SHIP) return 2;
        if (size == HullSize.FRIGATE) return 1;
        return 0;
    }

    private static boolean isLogistics(FleetMemberAPI member) {
        if (member.isCivilian()) return true;
        if (member.getVariant().hasHullMod("civgrade")) return true;
        ShipHullSpecAPI spec = member.getHullSpec();
        Set<ShipTypeHints> hints = spec.getHints();
        return hints.contains(ShipTypeHints.CIVILIAN) || hints.contains(ShipTypeHints.FREIGHTER)
                || hints.contains(ShipTypeHints.TANKER) || hints.contains(ShipTypeHints.LINER)
                || hints.contains(ShipTypeHints.TRANSPORT);
    }

    private static boolean carries(ShipVariantAPI v, Guidance.Kind kind) {
        for (String slot : v.getFittedWeaponSlots()) {
            WeaponSpecAPI w = v.getWeaponSpec(slot);
            if (w != null && Guidance.firesMissiles(w) && Guidance.of(w) == kind) return true;
        }
        return false;
    }

    private static int pdCount(ShipVariantAPI v) {
        int n = 0;
        for (String slot : v.getFittedWeaponSlots()) {
            WeaponSpecAPI w = v.getWeaponSpec(slot);
            if (w != null && w.getAIHints() != null && w.getAIHints().contains(AIHints.PD)) n++;
        }
        return n;
    }
}
