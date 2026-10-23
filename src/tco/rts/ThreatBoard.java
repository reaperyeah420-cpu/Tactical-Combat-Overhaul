package tco.rts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;

import tco.TcoSettings;
import tco.ai.AiAwareness;
import tco.radar.RadarCombatPlugin;

/**
 * The threat board (RTS mode, right side, while you have a CIC or Auxiliary CIC): the enemy ships your fleet knows now
 * (in sight or on radar), worst first: size, a radar lock on one of your ships, guided missiles of its homing on you,
 * and closeness to your nearest ship. Click a row to look there.
 */
final class ThreatBoard {

    private static final float W = 250f;
    private static final float ROW = 17f;
    private static final float HEADER = 18f;
    private static final float MARGIN = 16f;
    private static final int MAX_ROWS = 8;

    private static final class Entry {
        ShipAPI ship;
        float score, dist;
        boolean lock;
        int missiles;
    }

    private final List<Entry> rows = new ArrayList<Entry>();
    private final Map<String, LabelAPI> labels = new HashMap<String, LabelAPI>();
    private final Map<String, String> labelText = new HashMap<String, String>();
    private float x0, y0, x1, y1;
    private boolean shown;
    private static boolean view;
    private float vx0, vy0, vx1, vy1;

    /** The CIC view overlay is on (CicView). */
    static boolean viewOn() {
        return view;
    }

    boolean contains(float mx, float my) {
        return shown && mx >= x0 && mx <= x1 && my >= y0 && my <= y1;
    }

    static boolean wanted(CombatEngineAPI engine) {
        return TcoSettings.threatBoard && TcoSettings.radarEnabled && AiAwareness.commandLevel(engine, 0) > 0;
    }

    /** Draws the board with its bottom at `bottom` (screen coordinates). */
    void render(CombatEngineAPI engine, float bottom) {
        shown = wanted(engine);
        rows.clear();
        if (!shown) return;
        RadarCombatPlugin radar = RadarCombatPlugin.get(engine);
        Set<ShipAPI> contacts = radar == null ? null : radar.contacts(0);
        Map<ShipAPI, Integer> inbound = new HashMap<ShipAPI, Integer>();
        if (radar != null) {
            for (MissileAPI m : radar.homingOn(0).keySet()) {
                ShipAPI source = m.getSource();
                if (source == null) continue;
                Integer n = inbound.get(source);
                inbound.put(source, n == null ? 1 : n + 1);
            }
        }
        for (ShipAPI enemy : engine.getShips()) {
            if (enemy.getOwner() != 1 || !enemy.isAlive() || enemy.isHulk() || enemy.isFighter() || enemy.isDrone()
                    || enemy.isShuttlePod() || enemy.isStationModule()) {
                continue;
            }
            boolean known = !FogOfWar.isHidden(enemy) || (contacts != null && contacts.contains(enemy));
            if (!known) continue;
            Entry e = new Entry();
            e.ship = enemy;
            e.dist = Float.MAX_VALUE;
            for (ShipAPI ours : engine.getShips()) {
                if (ours.getOwner() != 0 || !ours.isAlive() || ours.isFighter() || ours.isShuttlePod()) continue;
                e.dist = Math.min(e.dist, Vector2f.sub(ours.getLocation(), enemy.getLocation(), null).length());
            }
            if (radar != null) {
                for (ShipAPI held : radar.heldBy(enemy)) {
                    if (held.getOwner() == 0 && !held.isFighter()) {
                        e.lock = true;
                        break;
                    }
                }
            }
            Integer n = inbound.get(enemy);
            e.missiles = n == null ? 0 : n;
            HullSize size = enemy.getHullSize();
            float weight = size == HullSize.CAPITAL_SHIP ? 4f : size == HullSize.CRUISER ? 3f : size == HullSize.DESTROYER ? 2f : 1f;
            float close = e.dist == Float.MAX_VALUE ? 0f : Math.max(0f, 1f - e.dist / 15000f) * 3f;
            e.score = weight + (e.lock ? 3f : 0f) + Math.min(6f, 1.5f * e.missiles) + close;
            rows.add(e);
        }
        rows.sort((a, b) -> Float.compare(b.score, a.score));
        int total = rows.size();
        while (rows.size() > MAX_ROWS) rows.remove(rows.size() - 1);

        float sw = Global.getSettings().getScreenWidth();
        x0 = sw - W - MARGIN;
        x1 = sw - MARGIN;
        y0 = bottom;
        y1 = bottom + HEADER + Math.max(1, rows.size()) * ROW + 6f;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0.02f, 0.04f, 0.06f, 0.82f);
        CicPanel.quad(x0 - 6f, y0, W + 12f, y1 - y0);
        GL11.glLineWidth(1f);
        GL11.glColor4f(1f, 0.45f, 0.3f, 0.55f);
        CicPanel.outline(x0 - 6f, y0, W + 12f, y1 - y0);
        // Threat bars
        for (int i = 0; i < rows.size(); i++) {
            Entry e = rows.get(i);
            float ry = y1 - HEADER - (i + 1) * ROW;
            float frac = Math.min(1f, e.score / 14f);
            GL11.glColor4f(1f, 0.3f + 0.4f * (1f - frac), 0.2f, 0.25f);
            CicPanel.quad(x0, ry + 2f, W * frac, ROW - 3f);
        }
        // CIC VIEW toggle, right of the header
        vx1 = x1;
        vx0 = x1 - 86f;
        vy0 = y1 - HEADER - 1f;
        vy1 = y1 - 3f;
        GL11.glColor4f(view ? 0.3f : 0.04f, view ? 0.2f : 0.06f, view ? 0.05f : 0.08f, 0.9f);
        CicPanel.quad(vx0, vy0, vx1 - vx0, vy1 - vy0);
        GL11.glColor4f(1f, 0.75f, 0.3f, view ? 0.95f : 0.4f);
        CicPanel.outline(vx0, vy0, vx1 - vx0, vy1 - vy0);
        GL11.glPopAttrib();
        text("view", view ? "CIC VIEW ON" : "CIC VIEW OFF", vx0 + 6f, vy0 + 1f, view ? new Color(255, 220, 140) : new Color(170, 150, 120));

        text("head", "THREATS  " + total + (total == 1 ? " contact" : " contacts"), x0, y1 - HEADER + 2f, new Color(255, 150, 110));
        if (rows.isEmpty()) {
            text("row0", "no known enemies", x0, y1 - HEADER - ROW + 1f, new Color(160, 160, 160));
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Entry e = rows.get(i);
            float ry = y1 - HEADER - (i + 1) * ROW + 1f;
            String d = e.dist == Float.MAX_VALUE ? "" : String.format("%.1fk", e.dist / 1000f);
            String flags = (e.lock ? "  LOCK" : "") + (e.missiles > 0 ? "  MSL " + e.missiles : "");
            text("row" + i, e.ship.getHullSpec().getHullName() + "  " + d + flags, x0 + 4f, ry,
                    e.lock || e.missiles > 0 ? new Color(255, 170, 130) : new Color(220, 210, 190));
        }
    }

    /** The contact clicked, or null. */
    ShipAPI click(float mx, float my) {
        if (!contains(mx, my)) return null;
        if (mx >= vx0 && mx <= vx1 && my >= vy0 && my <= vy1) {
            view = !view;
            return null;
        }
        for (int i = 0; i < rows.size(); i++) {
            float ry = y1 - HEADER - (i + 1) * ROW;
            if (my >= ry && my < ry + ROW) return rows.get(i).ship;
        }
        return null;
    }

    private void text(String slot, String s, float x, float y, Color color) {
        try {
            LabelAPI label = labels.get(slot);
            if (label == null) {
                label = Global.getSettings().createLabel(s, Fonts.DEFAULT_SMALL);
                labels.put(slot, label);
                labelText.put(slot, s);
            } else if (!s.equals(labelText.get(slot))) {
                label.setText(s);
                labelText.put(slot, s);
            }
            label.setColor(color);
            label.getPosition().setSize(W, 16f);
            label.getPosition().setLocation(x, y);
            label.render(1f);
        } catch (RuntimeException e) {
        }
    }
}
