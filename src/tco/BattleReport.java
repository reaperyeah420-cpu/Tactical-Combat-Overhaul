package tco;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * TCO's lines for the after-battle report (boarding and escape pod losses), printed under the game's own
 * report.
 */
public final class BattleReport {

    private static final List<String> lines = new ArrayList<String>();
    // Per line; null is the report's grey
    private static final List<Color> colors = new ArrayList<Color>();
    private static boolean scheduled;

    private BattleReport() {
    }

    public static void add(String line) {
        add(line, null);
    }

    public static void add(String line, Color color) {
        if (line == null || line.isEmpty()) return;
        lines.add(line);
        colors.add(color);
        if (scheduled) return;
        try {
            if (Global.getSector() == null) return;
            Global.getSector().addTransientScript(new Printer());
            scheduled = true;
        } catch (RuntimeException e) {
            // No campaign (mission or simulator)
            lines.clear();
            colors.clear();
        }
    }

    public static String people(int crew, int marines) {
        String c = crew + " crew";
        String m = marines + (marines == 1 ? " marine" : " marines");
        if (crew > 0 && marines > 0) return c + " and " + m;
        return marines > 0 ? m : c;
    }

    private static final class Printer implements EveryFrameScript {
        private boolean done;
        // printed on the 2nd campaign frame, after the battle dialog's own text
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
            if (done || ++frames < 2) return;
            done = true;
            scheduled = false;
            List<String> out = new ArrayList<String>(lines);
            List<Color> tints = new ArrayList<Color>(colors);
            lines.clear();
            colors.clear();
            if (out.isEmpty()) return;
            InteractionDialogAPI dialog = Global.getSector().getCampaignUI().getCurrentInteractionDialog();
            for (int i = 0; i < out.size(); i++) {
                Color color = tints.get(i) == null ? Misc.getGrayColor() : tints.get(i);
                if (dialog != null && dialog.getTextPanel() != null) dialog.getTextPanel().addPara(out.get(i), color);
                else Global.getSector().getCampaignUI().addMessage(out.get(i), color);
            }
        }
    }
}
