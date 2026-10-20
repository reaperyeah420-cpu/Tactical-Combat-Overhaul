package tco.boarding;

import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.EngagementResultForFleetAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.loading.VariantSource;

/**
 * Carries boarding results from the battle to the campaign; the listener moves captured ships into
 * the player's fleet before losses and salvage are worked out.
 */
public final class BoardingResults {

    public static final List<FleetMemberAPI> captured = new ArrayList<FleetMemberAPI>();
    public static final List<FleetMemberAPI> lost = new ArrayList<FleetMemberAPI>();
    public static final List<String> cores = new ArrayList<String>();
    public static final List<FleetMemberAPI> disabled = new ArrayList<FleetMemberAPI>();
    public static FleetMemberAPI newFlagship;
    public static BoardingCombatPlugin lastPlugin;
    private static final List<FleetMemberAPI> tagged = new ArrayList<FleetMemberAPI>();

    private BoardingResults() {
    }

    public static class Listener extends BaseCampaignEventListener {

        public Listener() {
            super(false);
        }

        @Override
        public void reportPlayerEngagement(EngagementResultAPI result) {
            Logger log = Global.getLogger(BoardingResults.class);
            try {
                if (lastPlugin != null) lastPlugin.settle();
            } catch (RuntimeException e) {
                log.warn("TCO: could not settle marine losses", e);
            }
            lastPlugin = null;

            List<FleetMemberAPI> prizes = new ArrayList<FleetMemberAPI>(captured);
            captured.clear();
            List<FleetMemberAPI> taken = new ArrayList<FleetMemberAPI>(lost);
            lost.clear();
            List<String> pulled = new ArrayList<String>(cores);
            cores.clear();
            List<FleetMemberAPI> drifting = new ArrayList<FleetMemberAPI>(disabled);
            disabled.clear();
            if (!pulled.isEmpty() && Global.getSector().getPlayerFleet() != null) {
                for (String core : pulled) {
                    Global.getSector().getPlayerFleet().getCargo().addCommodity(core, 1f);
                    String name = Global.getSettings().getCommoditySpec(core) != null
                            ? Global.getSettings().getCommoditySpec(core).getName() : core;
                    Global.getSector().getCampaignUI().addMessage("Your boarders recovered an " + name + ".");
                }
            }
            FleetMemberAPI reached = newFlagship;
            if ((prizes.isEmpty() && taken.isEmpty() && drifting.isEmpty()) || result == null) return;

            CampaignFleetAPI player = Global.getSector().getPlayerFleet();
            if (player == null) return;
            BattleAPI battle = result.getBattle();
            EngagementResultForFleetAPI mine = result.didPlayerWin() ? result.getWinnerResult() : result.getLoserResult();
            EngagementResultForFleetAPI theirs = result.didPlayerWin() ? result.getLoserResult() : result.getWinnerResult();

            newFlagship = reached;
            for (FleetMemberAPI member : drifting) {
                try {
                    // Leave it in the snapshot so a wreck is left
                    CampaignFleetAPI source = battle == null ? null : battle.getSourceFleet(member);
                    forget(mine, member);
                    forget(theirs, member);
                    if (source != null) source.getFleetData().removeFleetMember(member);
                    if (battle != null && battle.getNonPlayerCombined() != null && battle.getNonPlayerCombined() != source) {
                        battle.getNonPlayerCombined().getFleetData().removeFleetMember(member);
                    }
                    if (battle != null && battle.getPlayerCombined() != null && battle.getPlayerCombined() != source) {
                        battle.getPlayerCombined().getFleetData().removeFleetMember(member);
                    }
                } catch (RuntimeException e) {
                    log.warn("TCO: could not leave " + member.getShipName() + " drifting", e);
                }
            }
            for (FleetMemberAPI member : taken) {
                try {
                    handOverToEnemy(member, battle, mine, theirs, player);
                } catch (RuntimeException e) {
                    log.warn("TCO: could not hand over a ship lost to boarders: " + member.getShipName(), e);
                }
            }
            newFlagship = null;

            for (FleetMemberAPI member : prizes) {
                try {
                    CampaignFleetAPI source = battle == null ? null : battle.getSourceFleet(member);
                    if (source == player) continue;

                    // Off the enemy's books
                    forget(theirs, member);
                    forget(mine, member);
                    if (source != null) {
                        source.getFleetData().removeFleetMember(member);
                        source.getFleetData().getSnapshot().remove(member);
                    }
                    if (battle != null && battle.getNonPlayerCombined() != null) {
                        battle.getNonPlayerCombined().getFleetData().removeFleetMember(member);
                        battle.getNonPlayerCombined().getFleetData().getSnapshot().remove(member);
                    }
                    // Out of the pre-battle snapshot so no wreck is scattered; the tag covers a copied
                    // snapshot
                    ShipVariantAPI own = member.getVariant().clone();
                    own.setSource(VariantSource.REFIT);
                    own.addTag(Tags.SHIP_RECOVERABLE);
                    member.setVariant(own, false, false);
                    tagged.add(member);

                    // Same steps as vanilla's old boarding code
                    member.setOwner(0);
                    member.setCaptain(Global.getFactory().createPerson());
                    member.getRepairTracker().setMothballed(true);
                    player.getFleetData().addFleetMember(member);
                    if (battle != null && battle.getPlayerCombined() != null
                            && battle.getPlayerCombined() != player) {
                        battle.getPlayerCombined().getFleetData().addFleetMember(member);
                    }
                    if (mine != null) mine.getReserves().add(member);

                    Global.getSector().getCampaignUI().addMessage(member.getShipName() + " ("
                            + member.getHullSpec().getHullName()
                            + ") was captured by your marines and has joined your fleet, mothballed.");
                    log.info("TCO boarding: captured " + member.getShipName() + " moved to the player fleet");
                } catch (RuntimeException e) {
                    log.warn("TCO: could not hand over captured ship " + member.getShipName(), e);
                }
            }
        }

        /**
         * A player or allied ship taken by enemy boarders: moved into the enemy fleet as a mothballed
         * reserve;
         * its officer returns to the player.
         */
        private void handOverToEnemy(FleetMemberAPI member, BattleAPI battle, EngagementResultForFleetAPI mine,
                EngagementResultForFleetAPI theirs, CampaignFleetAPI player) {
            Logger log = Global.getLogger(BoardingResults.class);
            CampaignFleetAPI source = battle == null ? null : battle.getSourceFleet(member);
            CampaignFleetAPI enemy = null;
            if (battle != null) {
                for (CampaignFleetAPI fleet : battle.getNonPlayerSide()) {
                    if (fleet == null || fleet.getFleetData() == null) continue;
                    if (enemy == null || fleet.getFleetData().getNumMembers() > enemy.getFleetData().getNumMembers()) enemy = fleet;
                }
            }
            if (enemy == null) {
                log.warn("TCO: no enemy fleet to hand " + member.getShipName() + " to");
                return;
            }

            // Player was aboard: command moves
            boolean flagship = source == player && (player.getFlagship() == member
                    || member.getCaptain() == Global.getSector().getPlayerPerson());
            if (flagship) {
                FleetMemberAPI next = newFlagship;
                if (next == null || next == member || !player.getFleetData().getMembersListCopy().contains(next)) {
                    next = null;
                    for (FleetMemberAPI other : player.getFleetData().getMembersListCopy()) {
                        if (other == member || other.isFighterWing()) continue;
                        boolean better = next == null
                                || (next.isMothballed() && !other.isMothballed())
                                || (next.isMothballed() == other.isMothballed()
                                        && other.getDeploymentPointsCost() > next.getDeploymentPointsCost());
                        if (better) next = other;
                    }
                }
                if (next == null) {
                    log.warn("TCO: the flagship was taken but the player has no other ship; it stays with the player");
                    return;
                }
                player.getFleetData().setFlagship(next);
                next.setCaptain(Global.getSector().getPlayerPerson());
                Global.getSector().getCampaignUI().addMessage("You now command your fleet from " + next.getShipName() + ".");
            }

            forget(mine, member);
            forget(theirs, member);
            if (source != null) {
                source.getFleetData().removeFleetMember(member);
                // Out of the snapshot, or a wreck is left
                source.getFleetData().getSnapshot().remove(member);
            }
            if (battle.getPlayerCombined() != null && battle.getPlayerCombined() != source) {
                battle.getPlayerCombined().getFleetData().removeFleetMember(member);
                battle.getPlayerCombined().getFleetData().getSnapshot().remove(member);
            }

            member.setCaptain(Global.getFactory().createPerson());
            member.setFlagship(false);
            member.setOwner(1);
            member.getRepairTracker().setMothballed(true);
            enemy.getFleetData().addFleetMember(member);
            if (battle.getNonPlayerCombined() != null && battle.getNonPlayerCombined() != enemy) {
                battle.getNonPlayerCombined().getFleetData().addFleetMember(member);
            }
            if (theirs != null) theirs.getReserves().add(member);

            String whose = source == player ? "" : " (allied)";
            Global.getSector().getCampaignUI().addMessage(member.getShipName() + whose + " ("
                    + member.getHullSpec().getHullName() + ") was captured by enemy boarders and taken into "
                    + enemy.getName() + ". Defeat that fleet to get it back.");
            log.info("TCO boarding: " + member.getShipName() + " lost to boarders, moved to " + enemy.getName());
        }

        @Override
        public void reportBattleFinished(CampaignFleetAPI primaryWinner, BattleAPI battle) {
            for (FleetMemberAPI member : tagged) {
                try {
                    member.getVariant().removeTag(Tags.SHIP_RECOVERABLE);
                } catch (RuntimeException e) {
                    Global.getLogger(BoardingResults.class).warn("TCO: could not clear the recovery tag", e);
                }
            }
            tagged.clear();
        }

        private void forget(EngagementResultForFleetAPI side, FleetMemberAPI member) {
            if (side == null) return;
            side.getDeployed().remove(member);
            side.getRetreated().remove(member);
            side.getDisabled().remove(member);
            side.getDestroyed().remove(member);
            side.getReserves().remove(member);
        }
    }
}
