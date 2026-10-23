package tco.boarding;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.EngagementResultForFleetAPI;
import com.fs.starfarer.api.campaign.FleetEncounterContextPlugin.DataForEncounterSide;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.ai.CampaignFleetAIAPI.EncounterOption;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.FleetEncounterContext;
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl;
import com.fs.starfarer.api.impl.campaign.ids.Tags;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.util.Misc;

import tco.BattleReport;
import tco.ai.NpcHullmods;

/**
 * Carries boarding results from the battle to the campaign; the listener moves captured ships into
 * the player's fleet before losses and salvage are worked out.
 */
public final class BoardingResults {

    public static final List<FleetMemberAPI> captured = new ArrayList<FleetMemberAPI>();
    public static final List<FleetMemberAPI> lost = new ArrayList<FleetMemberAPI>();
    // The player's or allies' ships boarded back during the battle
    public static final List<FleetMemberAPI> retaken = new ArrayList<FleetMemberAPI>();
    public static final List<String> cores = new ArrayList<String>();
    public static final List<FleetMemberAPI> disabled = new ArrayList<FleetMemberAPI>();
    public static FleetMemberAPI newFlagship;
    public static BoardingCombatPlugin lastPlugin;
    private static final List<FleetMemberAPI> tagged = new ArrayList<FleetMemberAPI>();
    // On a ship lost to enemy boarders: which loadout-guard tags TCO added, so they come off when it's back
    private static final String ADDED_FITTED = "tco_lost_fitted";
    private static final String ADDED_NO_AUTOFIT = "tco_lost_no_autofit";

    /** A ship lost to boarders in this encounter: comes back if the enemy tries to flee after a won battle. */
    private static final class Held {
        FleetMemberAPI member;
        CampaignFleetAPI holder;
        CampaignFleetAPI home;
        boolean wasMothballed;
    }

    private static final List<Held> held = new ArrayList<Held>();
    private static boolean judge;
    private static boolean playerWon;
    private static boolean watching;

    private BoardingResults() {
    }

    public static class Listener extends BaseCampaignEventListener {

        public Listener() {
            super(false);
            // Made on every game load; the Judge script from before the load is gone
            held.clear();
            judge = false;
            watching = false;
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
            List<FleetMemberAPI> back = new ArrayList<FleetMemberAPI>(retaken);
            retaken.clear();
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
            if (result != null) {
                // Boarded back and gone off the map: still the player's, not the enemy's loss
                EngagementResultForFleetAPI theirSide = result.didPlayerWin() ? result.getLoserResult() : result.getWinnerResult();
                for (FleetMemberAPI member : back) forget(theirSide, member);
            }
            if (result != null && !held.isEmpty()) {
                // Ships held since an earlier battle of this encounter get judged again
                playerWon = result.didPlayerWin();
                schedule();
            }
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
                    handOverToEnemy(member, battle, mine, theirs, player, result.didPlayerWin());
                } catch (RuntimeException e) {
                    log.warn("TCO: could not hand over a ship lost to boarders: " + member.getShipName(), e);
                }
            }
            newFlagship = null;
            if (!held.isEmpty()) {
                playerWon = result.didPlayerWin();
                schedule();
            }

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
                EngagementResultForFleetAPI theirs, CampaignFleetAPI player, boolean playerWon) {
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

            keepLoadout(member);
            Held h = new Held();
            h.member = member;
            h.home = source;
            h.wasMothballed = member.isMothballed();
            member.setCaptain(Global.getFactory().createPerson());
            member.setFlagship(false);
            member.setOwner(1);
            member.getRepairTracker().setMothballed(true);
            enemy.getFleetData().addFleetMember(member);
            if (battle.getNonPlayerCombined() != null && battle.getNonPlayerCombined() != enemy) {
                battle.getNonPlayerCombined().getFleetData().addFleetMember(member);
            }
            // A won battle: not a reserve, which would count as a reason for them to stay and fight
            if (theirs != null && !playerWon) theirs.getReserves().add(member);
            h.holder = enemy;
            held.add(h);
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

        /**
         * Neither TCO's NPC fitting nor the enemy faction's autofit may touch the loadout while the enemy holds it
         * (they swapped hullmods, capacitors, vents and weapons).
         */
        private void keepLoadout(FleetMemberAPI member) {
            ShipVariantAPI own = member.getVariant();
            if (own.isStockVariant() || own.getSource() != VariantSource.REFIT) {
                own = own.clone();
                own.setSource(VariantSource.REFIT);
                member.setVariant(own, false, false);
            }
            if (!own.hasTag(NpcHullmods.FITTED_TAG)) {
                own.addTag(NpcHullmods.FITTED_TAG);
                own.addTag(ADDED_FITTED);
            }
            if (!own.hasTag(Tags.TAG_NO_AUTOFIT)) {
                own.addTag(Tags.TAG_NO_AUTOFIT);
                own.addTag(ADDED_NO_AUTOFIT);
            }
        }

        private void schedule() {
            judge = true;
            if (watching) return;
            watching = true;
            Global.getSector().addTransientScript(new Judge());
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

    /**
     * Two frames after a battle with ships held by the enemy (the battle dialog has worked out what each side does
     * next by then): after a win, if the enemy tries to disengage, every ship taken in this encounter is retaken;
     * otherwise they stay held. When the encounter is over, the ones still held are the enemy's.
     */
    private static final class Judge implements EveryFrameScript {
        private boolean done;
        private int frames;

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public boolean runWhilePaused() {
            return true;
        }

        @Override
        public void advance(float amount) {
            if (done) return;
            InteractionDialogAPI dialog = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
            if (judge) {
                if (++frames < 2) return;
                frames = 0;
                judge = false;
                try {
                    judgeNow(dialog);
                } catch (RuntimeException e) {
                    Global.getLogger(BoardingResults.class).warn("TCO: could not settle ships held by the enemy", e);
                }
                if (dialog != null) return;
            }
            if (dialog != null) return;
            // The encounter is over
            for (Held h : held) {
                if (!h.holder.getFleetData().getMembersListCopy().contains(h.member)) continue;
                Global.getSector().getCampaignUI().addMessage(h.member.getShipName() + " (" + h.member.getHullSpec().getHullName()
                        + ") was not retaken and now belongs to " + h.holder.getName() + ".", Misc.getNegativeHighlightColor());
            }
            held.clear();
            watching = false;
            done = true;
        }

        private void judgeNow(InteractionDialogAPI dialog) {
            List<Held> still = new ArrayList<Held>();
            for (Held h : held) {
                if (h.holder.getFleetData().getMembersListCopy().contains(h.member)) still.add(h);
            }
            held.retainAll(still);
            if (still.isEmpty()) return;
            FleetEncounterContext context = null;
            if (dialog != null && dialog.getPlugin() instanceof FleetInteractionDialogPluginImpl) {
                Object c = ((FleetInteractionDialogPluginImpl) dialog.getPlugin()).getContext();
                if (c instanceof FleetEncounterContext) context = (FleetEncounterContext) c;
            }
            BattleAPI battle = context == null ? null : context.getBattle();
            boolean fleeing = playerWon && battle != null && battle.getNonPlayerCombined() != null
                    && wantsToDisengage(context, battle.getNonPlayerCombined(), battle.getPlayerCombined());
            Color red = Misc.getNegativeHighlightColor();
            for (Held h : still) {
                String name = h.member.getShipName() + " (" + h.member.getHullSpec().getHullName() + ")";
                if (fleeing) {
                    giveBack(h, battle);
                    BattleReport.add("The enemy is pulling back. Your marines retake " + name + " as they go.",
                            Misc.getPositiveHighlightColor());
                } else if (playerWon) {
                    BattleReport.add(name + " is still held by enemy boarders, and the enemy is getting ready to fight"
                            + " on. Win again and drive them off to retake it.", red);
                } else {
                    BattleReport.add(name + " was captured by enemy boarders. Win the next battle and drive the enemy"
                            + " off to retake it, or it is theirs.", red);
                }
            }
            if (fleeing) held.clear();
        }
    }

    /** Vanilla's FleetInteractionDialogPluginImpl.fleetWantsToDisengage, with the held ships not counted as reserves. */
    private static boolean wantsToDisengage(FleetEncounterContext context, CampaignFleetAPI fleet, CampaignFleetAPI other) {
        DataForEncounterSide data = context.getDataFor(fleet);
        boolean nonCivReserves = false;
        if (data != null) {
            for (FleetMemberAPI member : data.getInReserveDuringLastEngagement()) {
                if (member.isCivilian() || isHeld(member)) continue;
                nonCivReserves = true;
                break;
            }
            if (context.isEngagedInHostilities() && !data.isWonLastEngagement() && !nonCivReserves) return true;
        }
        if (fleet.getAI() == null || other == null) return false;
        return fleet.getAI().pickEncounterOption(context, other) == EncounterOption.DISENGAGE;
    }

    private static boolean isHeld(FleetMemberAPI member) {
        for (Held h : held) {
            if (h.member == member) return true;
        }
        return false;
    }

    private static void giveBack(Held h, BattleAPI battle) {
        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        CampaignFleetAPI home = h.home != null && h.home.isAlive() ? h.home : player;
        h.holder.getFleetData().removeFleetMember(h.member);
        if (battle != null && battle.getNonPlayerCombined() != null && battle.getNonPlayerCombined() != h.holder) {
            battle.getNonPlayerCombined().getFleetData().removeFleetMember(h.member);
        }
        h.member.setOwner(0);
        h.member.getRepairTracker().setMothballed(h.wasMothballed);
        home.getFleetData().addFleetMember(h.member);
        if (battle != null && battle.getPlayerCombined() != null && battle.getPlayerCombined() != home) {
            battle.getPlayerCombined().getFleetData().addFleetMember(h.member);
        }
        Global.getLogger(BoardingResults.class).info("TCO boarding: " + h.member.getShipName() + " retaken as the enemy pulled back");
    }

    /** Takes the loadout-guard tags back off ships lost to boarders once they are in the player's fleet again. */
    public static class Returned implements EveryFrameScript {
        private float timer;

        @Override
        public boolean isDone() {
            return false;
        }

        // The refit screen pauses the game, and its autofit is off for no_autofit ships
        @Override
        public boolean runWhilePaused() {
            return true;
        }

        @Override
        public void advance(float amount) {
            timer -= Math.max(amount, 0.02f);
            if (timer > 0f) return;
            timer = 0.5f;
            CampaignFleetAPI player = Global.getSector() == null ? null : Global.getSector().getPlayerFleet();
            if (player == null || player.getFleetData() == null) return;
            for (FleetMemberAPI member : player.getFleetData().getMembersListCopy()) {
                ShipVariantAPI v = member.getVariant();
                if (v == null || !(v.hasTag(ADDED_FITTED) || v.hasTag(ADDED_NO_AUTOFIT))) continue;
                if (v.hasTag(ADDED_FITTED)) {
                    v.removeTag(ADDED_FITTED);
                    v.removeTag(NpcHullmods.FITTED_TAG);
                }
                if (v.hasTag(ADDED_NO_AUTOFIT)) {
                    v.removeTag(ADDED_NO_AUTOFIT);
                    v.removeTag(Tags.TAG_NO_AUTOFIT);
                }
                Global.getLogger(BoardingResults.class).info("TCO boarding: " + member.getShipName() + " is back, loadout guard off");
            }
        }
    }
}
