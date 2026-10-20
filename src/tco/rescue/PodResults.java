package tco.rescue;

import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CargoAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.impl.campaign.FleetEncounterContext;
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl;
import com.fs.starfarer.api.impl.campaign.ids.Commodities;
import com.fs.starfarer.api.util.Misc;

import tco.BattleReport;
import tco.TcoSettings;

/**
 * Settles the player's escape pods after battle: rescued crew are added back after the game's own crew
 * sums, and unreached pods go into the game's recoverable crew (returned only on a win).
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
            int adriftCrew = 0, adriftMarines = 0;
            for (float[] pod : drifting) {
                adriftCrew += Math.round(pod[0]);
                adriftMarines += Math.round(pod[1]);
            }
            if (crew + marines + lostCrew + lostMarines + adriftCrew + adriftMarines > 0) {
                StringBuilder text = new StringBuilder("Escape pods: ");
                List<String> parts = new ArrayList<String>();
                if (crew + marines > 0) parts.add(BattleReport.people(crew, marines) + " rescued during the battle");
                if (lostCrew + lostMarines > 0) parts.add(BattleReport.people(lostCrew, lostMarines)
                        + " killed when their pods or rescue shuttles were destroyed");
                if (adriftCrew + adriftMarines > 0) parts.add(BattleReport.people(adriftCrew, adriftMarines)
                        + " still adrift when it ended (any found are in the recovery report)");
                for (int i = 0; i < parts.size(); i++) text.append(i == 0 ? "" : "; ").append(parts.get(i));
                BattleReport.add(text.append(".").toString());
            }
            try {
                if (crew + marines >= 1) Global.getSector().addTransientScript(new ReturnCrew(crew, marines));
                float foundCrew = 0f;
                float foundMarines = 0f;
                for (float[] pod : drifting) {
                    if (Math.random() >= TcoSettings.podLeftoverChance) continue;
                    foundCrew += pod[0];
                    foundMarines += pod[1];
                }
                if (foundCrew + foundMarines >= 1f) addRecoverable(Math.round(foundCrew), Math.round(foundMarines));
            } catch (RuntimeException e) {
                Global.getLogger(PodResults.class).warn("TCO: could not settle escape pods", e);
            }
        }
    }

    private static void addRecoverable(int crew, int marines) {
        InteractionDialogAPI dialog = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
        if (dialog == null || !(dialog.getPlugin() instanceof FleetInteractionDialogPluginImpl)) return;
        Object context = ((FleetInteractionDialogPluginImpl) dialog.getPlugin()).getContext();
        if (!(context instanceof FleetEncounterContext)) return;
        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        FleetEncounterContext.DataForEncounterSide data = ((FleetEncounterContext) context).getDataFor(player);
        if (data == null) return;
        data.getRecoverableCrewLosses().addCrew(crew);
        data.getRecoverableCrewLosses().addMarines(marines);
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
            String text = "Rescued from escape pods during the battle: " + crewBack + " crew"
                    + (marines > 0 ? " and " + marinesBack + " marines" : "") + " are back aboard.";
            int lost = (crew - crewBack) + (marines - marinesBack);
            if (lost > 0) text += " " + lost + " more had no room in the fleet and were lost.";
            Global.getSector().getCampaignUI().addMessage(text, Misc.getPositiveHighlightColor());
        }
    }
}
