package tco.rescue;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.util.Misc;

import tco.BattleReport;
import tco.TcoSettings;

/**
 * Settles the player's escape pods after battle: rescued crew are added back after the game's own crew
 * sums. Each pod still drifting at the end is picked up with podRescueChanceWin / podRescueChanceLoss.
 */
public final class PodResults {

    private PodResults() {
    }

    public static class Listener extends BaseCampaignEventListener {

        public Listener() {
            super(false);
        }

        @Override
        public void reportPlayerEngagement(EngagementResultAPI result) {
            if (!PodLedger.isActive()) return;
            int crew = Math.round(PodLedger.savedCrew());
            int marines = Math.round(PodLedger.savedMarines());
            int lostCrew = Math.round(PodLedger.lostCrew());
            int lostMarines = Math.round(PodLedger.lostMarines());
            List<float[]> drifting = PodLedger.drifting();
            PodLedger.clear();
            // Pods still drifting at the end: each is found with a chance, better if you hold the field
            boolean won = result != null && result.didPlayerWin();
            float chance = won ? TcoSettings.podRescueChanceWin : TcoSettings.podRescueChanceLoss;
            float foundCrewF = 0f, foundMarinesF = 0f, missedCrewF = 0f, missedMarinesF = 0f;
            for (float[] pod : drifting) {
                if (Math.random() < chance) {
                    foundCrewF += pod[0];
                    foundMarinesF += pod[1];
                } else {
                    missedCrewF += pod[0];
                    missedMarinesF += pod[1];
                }
            }
            int foundCrew = Math.round(foundCrewF), foundMarines = Math.round(foundMarinesF);
            int missedCrew = Math.round(missedCrewF), missedMarines = Math.round(missedMarinesF);
            if (crew + marines + lostCrew + lostMarines + foundCrew + foundMarines + missedCrew + missedMarines > 0) {
                StringBuilder text = new StringBuilder("Escape pods: ");
                List<String> parts = new ArrayList<String>();
                if (crew + marines > 0) parts.add(BattleReport.people(crew, marines) + " rescued during the battle");
                if (lostCrew + lostMarines > 0) parts.add(BattleReport.people(lostCrew, lostMarines)
                        + " killed when their pods or rescue shuttles were destroyed");
                if (foundCrew + foundMarines > 0) parts.add(BattleReport.people(foundCrew, foundMarines)
                        + " picked up after the battle");
                if (missedCrew + missedMarines > 0) parts.add(BattleReport.people(missedCrew, missedMarines)
                        + (won ? " never found" : " lost with the battlefield"));
                for (int i = 0; i < parts.size(); i++) text.append(i == 0 ? "" : "; ").append(parts.get(i));
                BattleReport.add(text.append(".").toString());
            }
            try {
                int backCrew = crew + foundCrew;
                int backMarines = marines + foundMarines;
                if (backCrew + backMarines >= 1) Global.getSector().addTransientScript(new ReturnCrew(backCrew, backMarines));
            } catch (RuntimeException e) {
                Global.getLogger(PodResults.class).warn("TCO: could not settle escape pods", e);
            }
        }
    }

    /** Returns rescued crew after the game's crew sums. */
    private static final class ReturnCrew implements EveryFrameScript {
        private final int crew;
        private final int marines;
        private boolean done;

        ReturnCrew(int crew, int marines) {
            this.crew = crew;
            this.marines = marines;
        }

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
            done = true;
            CampaignFleetAPI player = Global.getSector().getPlayerFleet();
            if (player == null) return;
            CargoAPI cargo = player.getCargo();
            int room = (int) Math.max(0f, cargo.getMaxPersonnel() - cargo.getTotalPersonnel());
            int crewBack = Math.min(crew, room);
            int marinesBack = Math.min(marines, room - crewBack);
            if (crewBack > 0) cargo.addCommodity(Commodities.CREW, crewBack);
            if (marinesBack > 0) cargo.addMarines(marinesBack);
            String text = "Rescued from escape pods: " + crewBack + " crew"
                    + (marines > 0 ? " and " + marinesBack + " marines" : "") + " are back aboard.";
            int lost = (crew - crewBack) + (marines - marinesBack);
            if (lost > 0) text += " " + lost + " more had no room in the fleet and were lost.";
            Global.getSector().getCampaignUI().addMessage(text, Misc.getPositiveHighlightColor());
        }
    }
}
