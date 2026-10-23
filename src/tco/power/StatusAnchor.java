package tco.power;

import java.util.List;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.combat.CombatState;

/**
 * Where the flagship HUD's status list (the stack of status lines above the hull and flux bars) currently ends, so
 * the power bar can sit right above it however many statuses there are.
 * <p>
 * The combat UI is `CombatState`; its ship info panel (`getShipInfo()`) holds the status list (`getStatusList()`,
 * a 500-wide widget). The list adds one entry widget per status and places entry i at (0, i x 28) from its
 * bottom-left, removing entries once faded out, so the top of the visible statuses is the highest top edge among
 * its children. Game classes, so any failure (a game update renaming them) turns the anchor off for the battle and
 * the bar goes back to its fixed spot.
 */
final class StatusAnchor {

    private static boolean broken;
    private static boolean logged;

    private StatusAnchor() {
    }

    /** {left x, top y} of the status list's visible entries (its bottom when empty), or null when unknown. */
    static float[] top(CombatEngineAPI engine) {
        if (broken || engine == null) return null;
        try {
            Object ui = engine.getCombatUI();
            if (!(ui instanceof CombatState)) return null;
            UIComponentAPI list = ((CombatState) ui).getShipInfo().getStatusList();
            if (list == null) return null;
            PositionAPI at = list.getPosition();
            float top = at.getY();
            List<?> entries = ((CombatState) ui).getShipInfo().getStatusList().getChildrenNonCopy();
            for (Object entry : entries) {
                if (!(entry instanceof UIComponentAPI)) continue;
                PositionAPI p = ((UIComponentAPI) entry).getPosition();
                top = Math.max(top, p.getY() + p.getHeight());
            }
            if (!logged) {
                logged = true;
                Global.getLogger(StatusAnchor.class).info("TCO: power bar anchored to the status list at x " + at.getX()
                        + ", y " + at.getY());
            }
            return new float[] {at.getX(), top};
        } catch (Throwable e) {
            broken = true;
            Global.getLogger(StatusAnchor.class).warn("TCO: can't find the flagship status list; power bar stays in its fixed spot", e);
            return null;
        }
    }
}
