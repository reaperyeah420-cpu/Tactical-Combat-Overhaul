package tco.rts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;

import tco.TcoSettings;
import tco.ai.AiAwareness;
import tco.hullmods.CombatInformationCenter;
import tco.power.PowerCombatPlugin;
import tco.radar.EwCombatPlugin;
import tco.radar.RadarCombatPlugin;
import tco.radar.SensorPickets;

/**
 * The EW panel in RTS mode (bottom right), shown while one of your ships that can jam (ECM Package, Auxiliary CIC,
 * CIC) is selected. Sections by what the first such ship can do: radar scan, area jamming, missile jamming and fire
 * control (auto / on / off), spot jamming (auto / off), fleet emission control (command ships). Buttons apply to every
 * selected ship that has the function.
 */
final class CicPanel {

    private static final float W = 250f;
    private static final float ROW = 22f;
    private static final float GAP = 4f;
    private static final float MARGIN = 16f;
    private static final float CAP = 14f;

    private static final class Button {
        final String id, text;
        final boolean active;
        float x, y, w, h;

        Button(String id, String text, boolean active) {
            this.id = id;
            this.text = text;
            this.active = active;
        }
    }

    private final List<Button> buttons = new ArrayList<Button>();
    // Section captions: text, y
    private final List<Object[]> captions = new ArrayList<Object[]>();
    private final Map<String, LabelAPI> labels = new HashMap<String, LabelAPI>();
    private float x0, y0, x1, y1;
    private boolean shown;
    // Waiting for a click on the battlefield to aim the sector scan
    private boolean aiming;

    boolean isAiming() {
        return aiming && shown;
    }

    /** Aims the selected command ships' sector scans towards a point, relative to each ship (from the next battlefield click). */
    void aimAt(CombatEngineAPI engine, Collection<ShipAPI> selected, org.lwjgl.util.vector.Vector2f point) {
        aiming = false;
        int n = 0;
        for (ShipAPI ship : ewShips(selected)) {
            if (CombatInformationCenter.level(ship) == 0) continue;
            RadarCombatPlugin.setSectorAim(engine, ship, point);
            n++;
        }
        if (engine.getCombatUI() != null && n > 0) engine.getCombatUI().addMessage(0, new Color(255, 200, 110), "Sector scan aimed (turns with the ship)");
    }

    void cancelAiming() {
        aiming = false;
    }

    boolean contains(float mx, float my) {
        return shown && mx >= x0 && mx <= x1 && my >= y0 && my <= y1;
    }

    /** Top of the panel while shown (the threat board sits above it), else 0. */
    float top() {
        return shown ? y1 : 0f;
    }

    private static boolean capable(ShipAPI ship) {
        return CombatInformationCenter.level(ship) > 0 || RadarCombatPlugin.hasAreaJammer(ship) || RadarCombatPlugin.isEcmShip(ship)
                || EwCombatPlugin.spotCapacity(ship) > 0;
    }

    private static List<ShipAPI> ewShips(Collection<ShipAPI> selected) {
        List<ShipAPI> list = new ArrayList<ShipAPI>();
        for (ShipAPI ship : selected) {
            if (ship.getOwner() == 0 && !ship.isAlly() && ship.isAlive() && capable(ship)) list.add(ship);
        }
        // Command ships first: the panel shows the first ship's sections
        list.sort((a, b) -> CombatInformationCenter.level(b) - CombatInformationCenter.level(a));
        return list;
    }

    void render(CombatEngineAPI engine, Collection<ShipAPI> selected) {
        buttons.clear();
        captions.clear();
        List<ShipAPI> ships = ewShips(selected);
        shown = !ships.isEmpty();
        if (!shown) return;
        ShipAPI ship = ships.get(0);
        boolean command = CombatInformationCenter.level(ship) > 0;
        boolean ew = TcoSettings.ewEnabled;
        RadarCombatPlugin radar = RadarCombatPlugin.get(engine);
        boolean down = AiAwareness.isDecapitated(engine, 0);

        String status;
        if (command && down) status = "FLEET PICTURE DOWN";
        else if (EwCombatPlugin.isSuspended(engine, ship)) status = "EW paused: not enough power";
        else status = "";

        // Rows, bottom up
        float sw = Global.getSettings().getScreenWidth();
        x0 = sw - W - MARGIN;
        x1 = sw - MARGIN;
        float y = MARGIN;
        float statusY = y;
        if (!status.isEmpty()) y += CAP + GAP;
        if (command) {
            boolean emcon = EwCombatPlugin.isEmcon(engine, 0);
            addRow(y, new String[] { "emcon_off", "emcon_on" }, new String[] { "RADARS ON", "ALL RADARS OFF" },
                    new boolean[] { !emcon, emcon });
            y += ROW;
            caption(y, "FLEET EMISSION CONTROL");
            y += CAP + GAP;
        }
        if (command && SensorPickets.capacity(ship) > 0) {
            int out = SensorPickets.out(engine, ship), aboard = SensorPickets.aboard(engine, ship);
            boolean patrol = SensorPickets.isPatrolling(engine, ship);
            addRow(y, new String[] { "picket_station", "picket_patrol" }, new String[] { "HOLD STATION", "PATROL" },
                    new boolean[] { !patrol, patrol });
            y += ROW + GAP;
            addRow(y, new String[] { "picket_launch", "picket_recall" }, new String[] { "LAUNCH", "RECALL" },
                    new boolean[] { out > 0, false });
            y += ROW;
            caption(y, "SENSOR PICKETS  " + out + " out, " + aboard + " in reserve");
            y += CAP + GAP;
        }
        if (ew && EwCombatPlugin.spotCapacity(ship) > 0) {
            boolean on = !EwCombatPlugin.isSpotOff(engine, ship);
            addRow(y, new String[] { "spot_auto", "spot_off" }, new String[] { "AUTO", "OFF" }, new boolean[] { on, !on });
            y += ROW;
            caption(y, "SPOT JAMMING  " + EwCombatPlugin.spotTargetsOf(engine, ship).size() + " / " + EwCombatPlugin.spotCapacity(ship));
            y += CAP + GAP;
        }
        if (ew && command) {
            int m = EwCombatPlugin.fireControlMode(engine, ship);
            triple(y, "fc", m);
            y += ROW;
            caption(y, "FIRE CONTROL" + (EwCombatPlugin.fireControlActive(engine, ship)
                    ? "  -  " + EwCombatPlugin.disruptedBy(engine, ship) + " disrupted" : ""));
            y += CAP + GAP;
        }
        if (RadarCombatPlugin.isEcmShip(ship)) {
            int m = RadarCombatPlugin.missileJamMode(engine, ship);
            triple(y, "mj", m);
            y += ROW;
            int jammed = RadarCombatPlugin.missilesJammedBy(engine, ship);
            caption(y, "MISSILE JAMMING" + (jammed > 0 ? "  -  " + jammed + " jammed" : ""));
            y += CAP + GAP;
        }
        if (RadarCombatPlugin.hasAreaJammer(ship) && TcoSettings.jamEnabled) {
            int m = RadarCombatPlugin.areaJamMode(engine, ship);
            triple(y, "aj", m);
            y += ROW;
            caption(y, (command ? "AREA JAMMING (BARRAGE)" : "AREA JAMMING")
                    + (RadarCombatPlugin.areaJamActive(engine, ship) ? "  -  JAMMING" : ""));
            y += CAP + GAP;
        }
        if (command) {
            boolean sector = radar != null && radar.isSector(ship);
            if (sector) {
                boolean fixed = RadarCombatPlugin.hasSectorAim(engine, ship);
                addRow(y, new String[] { "aim_point", "aim_auto" }, new String[] { aiming ? "CLICK A DIRECTION" : "AIM DIRECTION", "AUTO AIM" },
                        new boolean[] { fixed || aiming, !fixed && !aiming });
                y += ROW + GAP;
            }
            addRow(y, new String[] { "scan_wide", "scan_sector" }, new String[] { "WIDE SCAN", "SECTOR SCAN" },
                    new boolean[] { !sector, sector });
            y += ROW;
            caption(y, "RADAR");
            y += CAP + GAP;
        }
        float titleY = y;
        y += CAP;
        y0 = MARGIN - 6f;
        y1 = y + 6f;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0.02f, 0.04f, 0.06f, 0.82f);
        quad(x0 - 6f, y0, W + 12f, y1 - y0);
        GL11.glLineWidth(1f);
        GL11.glColor4f(1f, 0.75f, 0.3f, 0.55f);
        outline(x0 - 6f, y0, W + 12f, y1 - y0);
        for (Button b : buttons) {
            if (b.active) GL11.glColor4f(0.3f, 0.2f, 0.05f, 0.95f);
            else GL11.glColor4f(0.04f, 0.06f, 0.08f, 0.85f);
            quad(b.x, b.y, b.w, b.h);
            GL11.glColor4f(1f, 0.75f, 0.3f, b.active ? 0.95f : 0.35f);
            outline(b.x, b.y, b.w, b.h);
        }
        GL11.glPopAttrib();

        for (Button b : buttons) {
            text(b.text, b.x + b.w / 2f, b.y + 4f, b.active ? new Color(255, 220, 140) : new Color(170, 150, 120), true);
        }
        for (Object[] c : captions) text((String) c[0], x0, (Float) c[1], new Color(200, 170, 120), false);
        String kind = CombatInformationCenter.isCic(ship) ? "CIC" : CombatInformationCenter.isAux(ship) ? "AUX CIC" : "EW";
        String name = ships.size() > 1 ? ships.size() + " ships" : ship.getName();
        PowerCombatPlugin power = PowerCombatPlugin.get(engine);
        String powerText = power != null && power.capacityOf(ship) > 0f
                ? "   power " + Math.round(100f * power.powerOf(ship) / power.capacityOf(ship)) + "%" : "";
        text(kind + "  -  " + name + powerText, x0, titleY, new Color(255, 200, 110), false);
        if (!status.isEmpty()) text(status, x0, statusY, down ? new Color(255, 110, 80) : new Color(170, 200, 210), false);
    }

    private void caption(float y, String text) {
        captions.add(new Object[] { text, y });
    }

    /** AUTO / ON / OFF for a function: mode 0 auto, 1 on, 2 off. */
    private void triple(float y, String id, int mode) {
        addRow(y, new String[] { id + "_auto", id + "_on", id + "_off" }, new String[] { "AUTO", "ON", "OFF" },
                new boolean[] { mode == 0, mode == 1, mode == 2 });
    }

    private void addRow(float y, String[] ids, String[] texts, boolean[] active) {
        int n = ids.length;
        float w = (W - GAP * (n - 1)) / n;
        for (int i = 0; i < n; i++) {
            Button b = new Button(ids[i], texts[i], active[i]);
            b.x = x0 + i * (w + GAP);
            b.y = y;
            b.w = w;
            b.h = ROW;
            buttons.add(b);
        }
    }

    /** A click on the panel; true if it was on it (handled or not). */
    boolean click(CombatEngineAPI engine, Collection<ShipAPI> selected, float mx, float my) {
        if (!contains(mx, my)) return false;
        Button hit = null;
        for (Button b : buttons) {
            if (mx >= b.x && mx <= b.x + b.w && my >= b.y && my <= b.y + b.h) hit = b;
        }
        if (hit == null) return true;
        List<ShipAPI> ships = ewShips(selected);
        RadarCombatPlugin radar = RadarCombatPlugin.get(engine);
        String id = hit.id;
        int mode = id.endsWith("_auto") ? 0 : id.endsWith("_on") ? 1 : 2;
        String what = null;
        for (ShipAPI ship : ships) {
            if (id.equals("scan_wide") && radar != null && radar.isSector(ship)) RadarCombatPlugin.toggleScan(engine, ship);
            else if (id.equals("scan_sector") && radar != null && !radar.isSector(ship)) RadarCombatPlugin.toggleScan(engine, ship);
            else if (id.startsWith("aj_")) RadarCombatPlugin.setAreaJamMode(engine, ship, mode);
            else if (id.startsWith("mj_")) RadarCombatPlugin.setMissileJamMode(engine, ship, mode);
            else if (id.startsWith("fc_")) EwCombatPlugin.setFireControlMode(engine, ship, mode);
            else if (id.equals("aim_auto")) RadarCombatPlugin.setSectorAim(engine, ship, null);
            else if (id.equals("picket_launch")) SensorPickets.launch(engine, ship);
            else if (id.equals("picket_station")) SensorPickets.setPatrol(engine, ship, false);
            else if (id.equals("picket_patrol")) SensorPickets.setPatrol(engine, ship, true);
            else if (id.equals("picket_recall")) SensorPickets.recall(engine, ship);
            else if (id.equals("spot_auto")) EwCombatPlugin.setSpotJamming(engine, ship, true);
            else if (id.equals("spot_off")) EwCombatPlugin.setSpotJamming(engine, ship, false);
        }
        if (id.equals("aim_point")) {
            aiming = true;
            if (engine.getCombatUI() != null) {
                engine.getCombatUI().addMessage(0, new Color(255, 200, 110), "Click a direction to scan. Right-click to cancel.");
            }
            return true;
        }
        if (id.equals("aim_auto")) aiming = false;
        if (id.startsWith("picket")) what = "sensor pickets";
        if (id.startsWith("aj_")) what = "area jamming";
        else if (id.startsWith("mj_")) what = "missile jamming";
        else if (id.startsWith("fc_")) what = "fire control";
        if (id.equals("emcon_on")) EwCombatPlugin.setEmcon(engine, 0, true);
        else if (id.equals("emcon_off")) EwCombatPlugin.setEmcon(engine, 0, false);
        if (engine.getCombatUI() != null) {
            String text = id.equals("emcon_on") ? "Fleet EMCON: every radar off" : id.equals("emcon_off") ? "Fleet EMCON lifted"
                    : (what != null ? what + ": " + hit.text.toLowerCase() : hit.text.toLowerCase());
            engine.getCombatUI().addMessage(0, new Color(255, 200, 110), text);
        }
        return true;
    }

    private void text(String s, float x, float y, Color color, boolean centred) {
        try {
            LabelAPI label = labels.get(s);
            if (label == null) {
                if (labels.size() > 200) labels.clear();
                label = Global.getSettings().createLabel(s, Fonts.DEFAULT_SMALL);
                labels.put(s, label);
            }
            label.setColor(color);
            float w = label.computeTextWidth(s);
            label.getPosition().setSize(Math.max(w + 4f, 10f), 16f);
            label.getPosition().setLocation(centred ? x - w / 2f : x, y);
            label.render(1f);
        } catch (RuntimeException e) {
        }
    }

    static void quad(float x, float y, float w, float h) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    static void outline(float x, float y, float w, float h) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }
}
