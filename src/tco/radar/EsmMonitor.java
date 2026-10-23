package tco.radar;

import java.awt.Color;
import java.util.List;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;

import tco.TcoSettings;
import tco.rts.FogOfWar;

/**
 * The Passive Sensor Suite's own display, in place of the radar scope when the flown ship listens without a radar
 * (none fitted, or switched off): a square amber monitor. Each heard emitter is a faint bearing strobe out to its ESM
 * circle, marked by what it is (diamond radar, cross jammer, square ECM); a radar tracking this ship flashes red. The
 * range steps between 5k and 80k to fit the contacts. Contacts are clickable to lock, as on the scope.
 */
final class EsmMonitor {

    private static final float[] SCALES = { 5000f, 10000f, 20000f, 40000f, 80000f };
    private static final Color AMBER = new Color(255, 190, 80);
    private static final Color WARN = new Color(255, 90, 60);

    private final RadarCombatPlugin radar;
    private float scale = 10000f;
    private LabelAPI statusLabel, lockLabel;
    private String statusText, lockText;

    EsmMonitor(RadarCombatPlugin radar) {
        this.radar = radar;
    }

    /** Draws the monitor centred on (cx, cy), side `size`; adds clickable contacts to `blips` (ship, x, y, radius). */
    void draw(CombatEngineAPI engine, ShipAPI ship, float cx, float cy, float size, List<Object[]> blips, ShipAPI locked,
            boolean radarFitted) {
        blips.clear();
        float half = size / 2f;
        float r = half - 14f;
        float now = engine.getTotalElapsedTime(true);
        Vector2f c = ship.getLocation();
        List<RadarCombatPlugin.EsmContact> contacts = radar.esmContacts(ship);
        fitScale(contacts, c);
        boolean spiked = false;
        for (RadarCombatPlugin.EsmContact e : contacts) spiked |= e.tracksMe;
        float flicker = 0.93f + 0.07f * (float) Math.sin(now * 23.0) * (float) Math.sin(now * 3.7);
        boolean blink = (now * 4f) % 1f < 0.55f;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        // Screen: dark phosphor glass, warmer towards the middle
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(0.05f, 0.03f, 0.005f, 0.9f);
        GL11.glVertex2f(cx - half, cy - half);
        GL11.glVertex2f(cx + half, cy - half);
        GL11.glVertex2f(cx + half, cy + half);
        GL11.glVertex2f(cx - half, cy + half);
        GL11.glEnd();
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(0.25f, 0.14f, 0.02f, 0.18f * flicker);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(0.25f, 0.14f, 0.02f, 0f);
        for (int i = 0; i <= 32; i++) {
            double a = Math.PI * 2.0 * i / 32;
            GL11.glVertex2f(cx + (float) Math.cos(a) * half, cy + (float) Math.sin(a) * half);
        }
        GL11.glEnd();
        // Scanlines, drifting slowly
        GL11.glLineWidth(1f);
        GL11.glBegin(GL11.GL_LINES);
        float drift = (now * 6f) % 3f;
        for (float y = cy - half + drift; y < cy + half; y += 3f) {
            GL11.glColor4f(0f, 0f, 0f, 0.22f);
            GL11.glVertex2f(cx - half, y);
            GL11.glVertex2f(cx + half, y);
        }
        GL11.glEnd();

        // Polar grid: two range rings, bearing ticks every 30 degrees, cross-hair
        amber(0.16f * flicker);
        RadarCombatPlugin.circle(cx, cy, r / 3f, 40);
        RadarCombatPlugin.circle(cx, cy, r * 2f / 3f, 56);
        amber(0.3f * flicker);
        RadarCombatPlugin.circle(cx, cy, r, 72);
        GL11.glBegin(GL11.GL_LINES);
        for (int deg = 0; deg < 360; deg += 30) {
            double a = Math.toRadians(deg);
            float in = deg % 90 == 0 ? r - 10f : r - 5f;
            amber((deg % 90 == 0 ? 0.6f : 0.35f) * flicker);
            GL11.glVertex2f(cx + (float) Math.cos(a) * in, cy + (float) Math.sin(a) * in);
            GL11.glVertex2f(cx + (float) Math.cos(a) * (r + 3f), cy + (float) Math.sin(a) * (r + 3f));
        }
        amber(0.1f * flicker);
        GL11.glVertex2f(cx - r, cy);
        GL11.glVertex2f(cx + r, cy);
        GL11.glVertex2f(cx, cy - r);
        GL11.glVertex2f(cx, cy + r);
        GL11.glEnd();
        // Sight range, for scale
        float sight = FogOfWar.sightRange(ship) / scale * r;
        if (sight < r) {
            amber(0.22f * flicker);
            RadarCombatPlugin.dashed(cx, cy, sight);
        }

        // Contacts
        for (RadarCombatPlugin.EsmContact e : contacts) {
            float dx = e.guess.x - c.x, dy = e.guess.y - c.y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            boolean rim = d > scale;
            float k = rim ? (r - 6f) / Math.max(1f, d) : r / scale;
            float px = cx + dx * k, py = cy + dy * k;
            float er = rim ? 5f : Math.max(5f, Math.min(r * 0.35f, e.size / scale * r));
            // Stronger (louder) emitters draw brighter strobes
            float strength = e.reach > 0f ? Math.max(0.15f, Math.min(1f, 1.2f - d / e.reach)) : 0.3f;
            boolean warn = e.tracksMe;
            float a = warn ? (blink ? 1f : 0.35f) : 0.4f + 0.6f * strength;
            float fade = 1f - 0.5f * Math.min(1f, (now - e.time) / Math.max(0.1f, TcoSettings.esmRefreshSeconds * 2f));
            a *= flicker * fade;
            Color col = warn ? WARN : AMBER;
            // Bearing strobe
            GL11.glLineWidth(warn ? 2f : 1f);
            color(col, a * (warn ? 0.8f : 0.35f));
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(cx, cy);
            GL11.glVertex2f(px, py);
            GL11.glEnd();
            // Where it is, roughly
            GL11.glLineWidth(1f);
            color(col, a * 0.6f);
            RadarCombatPlugin.dashed(px, py, er);
            // What it is
            GL11.glLineWidth(1.5f);
            color(col, a);
            float g = 4.5f;
            if (e.radar) {
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(px, py + g);
                GL11.glVertex2f(px + g, py);
                GL11.glVertex2f(px, py - g);
                GL11.glVertex2f(px - g, py);
                GL11.glEnd();
            }
            if (e.jammer) {
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(px - g, py - g);
                GL11.glVertex2f(px + g, py + g);
                GL11.glVertex2f(px - g, py + g);
                GL11.glVertex2f(px + g, py - g);
                GL11.glEnd();
            }
            if (e.ecm) {
                float q = g + 2f;
                GL11.glBegin(GL11.GL_LINE_LOOP);
                GL11.glVertex2f(px - q, py - q);
                GL11.glVertex2f(px + q, py - q);
                GL11.glVertex2f(px + q, py + q);
                GL11.glVertex2f(px - q, py + q);
                GL11.glEnd();
            }
            if (e.ship == locked) {
                color(AMBER, flicker);
                RadarCombatPlugin.brackets(px, py, er + 4f);
            }
            blips.add(new Object[] { e.ship, px, py, er });
        }

        // Own anti-radiation missiles in flight
        GL11.glPointSize(2.5f);
        GL11.glBegin(GL11.GL_POINTS);
        for (MissileAPI m : engine.getMissiles()) {
            if (m.getSource() != ship || m.isFlare() || m.isFizzling() || m.didDamage()) continue;
            float dx = m.getLocation().x - c.x, dy = m.getLocation().y - c.y;
            if (dx * dx + dy * dy > scale * scale) continue;
            GL11.glColor4f(1f, 0.95f, 0.75f, 0.9f * flicker);
            GL11.glVertex2f(cx + dx / scale * r, cy + dy / scale * r);
        }
        GL11.glEnd();
        // Own ship
        double f = Math.toRadians(ship.getFacing());
        GL11.glColor4f(1f, 0.85f, 0.5f, 0.95f);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(cx + (float) Math.cos(f) * 7f, cy + (float) Math.sin(f) * 7f);
        GL11.glVertex2f(cx + (float) Math.cos(f + 2.5) * 5f, cy + (float) Math.sin(f + 2.5) * 5f);
        GL11.glVertex2f(cx + (float) Math.cos(f - 2.5) * 5f, cy + (float) Math.sin(f - 2.5) * 5f);
        GL11.glEnd();

        // Frame: bezel, inner line and corner brackets; it flashes red while a radar tracks this ship
        Color frame = spiked && blink ? WARN : AMBER;
        GL11.glLineWidth(2f);
        color(frame, 0.85f * flicker);
        rect(cx - half, cy - half, cx + half, cy + half);
        GL11.glLineWidth(1f);
        color(frame, 0.3f * flicker);
        rect(cx - half + 4f, cy - half + 4f, cx + half - 4f, cy + half - 4f);
        GL11.glLineWidth(2.5f);
        color(frame, flicker);
        float l = 14f, o = 3f;
        GL11.glBegin(GL11.GL_LINES);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                float x = cx + sx * (half + o), y = cy + sy * (half + o);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x - sx * l, y);
                GL11.glVertex2f(x, y);
                GL11.glVertex2f(x, y - sy * l);
            }
        }
        GL11.glEnd();
        GL11.glPopAttrib();

        // Status and lock lines, under the monitor as on the radar scope
        String status;
        if (spiked) status = "!! RADAR LOCK ON YOU";
        else status = "ESM  RNG " + Math.round(scale / 1000f) + "k  " + contacts.size()
                + (contacts.size() == 1 ? " CONTACT" : " CONTACTS") + (radarFitted ? "   RADAR OFF (N)" : "   PASSIVE");
        String lock = null;
        boolean armReady = false;
        if (locked != null) {
            Vector2f guess = radar.esmGuess(ship.getOwner(), locked);
            armReady = RadarCombatPlugin.isArm(ship) && radar.hears(ship.getLocation(), locked);
            lock = "LOCK  " + locked.getHullSpec().getHullName() + "  "
                    + (guess != null ? "~" + Math.round(Vector2f.sub(guess, c, null).length() / 100f) * 100 : "(lost)")
                    + (armReady ? "   ARM READY" : "");
        }
        try {
            if (statusLabel == null) statusLabel = Global.getSettings().createLabel(status, Fonts.DEFAULT_SMALL);
            if (!status.equals(statusText)) {
                statusLabel.setText(status);
                statusText = status;
            }
            statusLabel.setColor(spiked ? (blink ? WARN : AMBER) : AMBER);
            statusLabel.getPosition().setSize(size + 80f, 16f);
            statusLabel.getPosition().setLocation(cx - half + 2f, cy - half - 26f);
            statusLabel.render(1f);
            if (lock != null) {
                if (lockLabel == null) lockLabel = Global.getSettings().createLabel(lock, Fonts.DEFAULT_SMALL);
                if (!lock.equals(lockText)) {
                    lockLabel.setText(lock);
                    lockText = lock;
                }
                lockLabel.setColor(armReady ? new Color(255, 230, 140) : new Color(210, 160, 90));
                lockLabel.getPosition().setSize(size + 80f, 16f);
                lockLabel.getPosition().setLocation(cx - half + 2f, cy - half - 44f);
                lockLabel.render(1f);
            }
        } catch (RuntimeException e) {
        }
    }

    /** Smallest step that holds every contact; steps down only once they all fit well inside the next one. */
    private void fitScale(List<RadarCombatPlugin.EsmContact> contacts, Vector2f c) {
        float far = 0f;
        for (RadarCombatPlugin.EsmContact e : contacts) {
            far = Math.max(far, Vector2f.sub(e.guess, c, null).length() + e.size * 0.5f);
        }
        float want = SCALES[SCALES.length - 1];
        for (float s : SCALES) {
            if (far * 1.1f <= s) {
                want = s;
                break;
            }
        }
        if (far == 0f) scale = SCALES[1];
        else if (want > scale || far * 1.1f < want * 0.8f) scale = want;
    }

    static void rect(float x0, float y0, float x1, float y1) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x0, y0);
        GL11.glVertex2f(x1, y0);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x0, y1);
        GL11.glEnd();
    }

    private static void amber(float alpha) {
        GL11.glColor4f(1f, 0.75f, 0.3f, Math.max(0f, Math.min(1f, alpha)));
    }

    private static void color(Color c, float alpha) {
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, Math.max(0f, Math.min(1f, alpha)));
    }
}
