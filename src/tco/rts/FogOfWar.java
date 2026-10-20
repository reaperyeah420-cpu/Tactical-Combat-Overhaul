package tco.rts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;

import tco.TcoSettings;

/**
 * Fog of war for RTS mode: shades what the player's side can't see, hides enemy ships in it and leaves
 * fading last-seen markers. Sight matches the engine's fog, but with exact circles.
 * Hiding uses setExtraAlphaMult2, which the engine never sets itself.
 */
public final class FogOfWar {

    public static final float BASE_SIGHT = 3000f;
    private static final int GRID_COLUMNS = 72;
    private static final float FADE_PER_SECOND = 4f;
    private static final String SIGHT_ID = "tco_sight";

    private static CombatEngineAPI engine;
    private static boolean active;
    private static boolean shading;
    private static final List<float[]> eyes = new ArrayList<float[]>();
    private static final Map<ShipAPI, Float> alpha = new HashMap<ShipAPI, Float>();
    private static final Map<ShipAPI, float[]> lastSeen = new HashMap<ShipAPI, float[]>();
    private static final Set<ShipAPI> seen = new HashSet<ShipAPI>();
    private static final Set<ShipAPI> overlayHidden = new HashSet<ShipAPI>();

    private FogOfWar() {
    }

    /** Hiding enemies now (RTS, and piloting with fogHideWhenPiloting). */
    public static boolean isActive() {
        return active && engine != null;
    }

    public static boolean isShading() {
        return isActive() && shading;
    }

    /** Hidden by the fog (false while off). */
    public static boolean isHidden(ShipAPI ship) {
        if (!isActive() || ship == null || ship.getOwner() == 0 || ship.getOwner() == 100) return false;
        if (ship.isHulk() || !ship.isAlive()) return false;
        return !canSee(ship.getLocation(), ship.getCollisionRadius() * 0.5f);
    }

    /** The player's side can see the point (true while off). */
    public static boolean isVisible(Vector2f point) {
        return !isActive() || canSee(point, 0f);
    }

    public static float sightRange(ShipAPI ship) {
        return ship.getMutableStats().getSightRadiusMod().computeEffective(BASE_SIGHT);
    }

    private static boolean reveals(ShipAPI ship) {
        if (ship.getOwner() != 0 || !ship.isAlive() || ship.isHulk()) return false;
        // Shuttle pods include the RTS command pod
        if (ship.isDrone() || ship.isStationModule() || ship.isShuttlePod()) return false;
        return !ship.isFighter() || ship.isWingLeader();
    }

    private static boolean canSee(Vector2f p, float slack) {
        for (float[] eye : eyes) {
            float dx = p.x - eye[0], dy = p.y - eye[1];
            float r = eye[2] + slack;
            if (dx * dx + dy * dy <= r * r) return true;
        }
        return false;
    }

    /** Called every frame by the RTS plugin. */
    public static void update(CombatEngineAPI combat, boolean on, boolean shade, float amount, ShipAPI exclude) {
        if (engine != combat) {
            engine = combat;
            alpha.clear();
            lastSeen.clear();
            seen.clear();
            overlayHidden.clear();
        }
        // Sight range for every ship (the engine fog reads the same stat)
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getCustomData().containsKey(SIGHT_ID)) continue;
            ship.setCustomData(SIGHT_ID, Boolean.TRUE);
            if (TcoSettings.sightRangeMult != 1f) {
                ship.getMutableStats().getSightRadiusMod().modifyMult(SIGHT_ID, Math.max(0.05f, TcoSettings.sightRangeMult));
            }
        }
        active = on && TcoSettings.fogOfWar;
        shading = shade;
        eyes.clear();
        if (!active) {
            clear();
            return;
        }
        for (ShipAPI ship : engine.getShips()) {
            if (ship != exclude && reveals(ship)) {
                eyes.add(new float[] { ship.getLocation().x, ship.getLocation().y, sightRange(ship) });
            }
        }
        float now = engine.getTotalElapsedTime(false);
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() == 0 || ship.getOwner() == 100) continue;
            boolean hidden = isHidden(ship);
            Float current = alpha.get(ship);
            float a = current == null ? 1f : current;
            boolean marks = ship.isAlive() && !ship.isHulk() && !ship.isFighter() && !ship.isDrone();
            if (hidden && seen.remove(ship) && marks) {
                // Just went into the fog: mark it
                lastSeen.put(ship, new float[] { ship.getLocation().x, ship.getLocation().y, now });
            }
            if (!hidden) {
                lastSeen.remove(ship);
                if (marks) seen.add(ship);
            }
            // The reticle ignores ship alpha: hide it separately
            if (hidden && !overlayHidden.contains(ship) && !ship.isForceHideFFOverlay()) {
                ship.setForceHideFFOverlay(true);
                overlayHidden.add(ship);
            } else if (!hidden && overlayHidden.remove(ship)) {
                ship.setForceHideFFOverlay(false);
            }
            float target = hidden ? 0f : 1f;
            if (a < target) a = Math.min(target, a + FADE_PER_SECOND * amount);
            else if (a > target) a = Math.max(target, a - FADE_PER_SECOND * amount);
            if (current == null && a >= 1f && !hidden) continue;
            ship.setExtraAlphaMult2(a);
            if (a >= 1f && !hidden) alpha.remove(ship);
            else alpha.put(ship, a);
        }
        // Drop departed ships and expired markers
        for (Iterator<ShipAPI> it = alpha.keySet().iterator(); it.hasNext();) {
            ShipAPI ship = it.next();
            if (!engine.isEntityInPlay(ship)) it.remove();
        }
        for (Iterator<ShipAPI> it = seen.iterator(); it.hasNext();) {
            if (!engine.isEntityInPlay(it.next())) it.remove();
        }
        for (Iterator<ShipAPI> it = overlayHidden.iterator(); it.hasNext();) {
            if (!engine.isEntityInPlay(it.next())) it.remove();
        }
        for (Iterator<Map.Entry<ShipAPI, float[]>> it = lastSeen.entrySet().iterator(); it.hasNext();) {
            Map.Entry<ShipAPI, float[]> e = it.next();
            if (!e.getKey().isAlive() || e.getKey().isHulk() || !engine.isEntityInPlay(e.getKey())
                    || now - e.getValue()[2] > TcoSettings.fogLastSeenSeconds) {
                it.remove();
            }
        }
    }

    private static void clear() {
        for (ShipAPI ship : alpha.keySet()) ship.setExtraAlphaMult2(1f);
        alpha.clear();
        for (ShipAPI ship : overlayHidden) ship.setForceHideFFOverlay(false);
        overlayHidden.clear();
        lastSeen.clear();
        seen.clear();
    }

    /** Shading and last-seen markers, in world coordinates. */
    public static void render(ViewportAPI viewport) {
        if (!isShading() || TcoSettings.fogDarkness <= 0f) return;
        float x0 = viewport.getLLX(), y0 = viewport.getLLY();
        float w = viewport.getVisibleWidth(), h = viewport.getVisibleHeight();
        int cols = GRID_COLUMNS;
        int rows = Math.max(2, Math.round(cols * h / Math.max(1f, w)));
        float cw = w / cols, ch = h / rows;
        // Eyes that reach the view
        List<float[]> near = new ArrayList<float[]>();
        float cx = x0 + w / 2f, cy = y0 + h / 2f, half = (float) Math.hypot(w, h) / 2f;
        for (float[] eye : eyes) {
            if (Math.hypot(eye[0] - cx, eye[1] - cy) < eye[2] + half) near.add(eye);
        }
        float dark = Math.min(1f, TcoSettings.fogDarkness);
        float[] prev = new float[cols + 1];
        float[] cur = new float[cols + 1];
        for (int i = 0; i <= cols; i++) prev[i] = shade(near, x0 + i * cw, y0, dark);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glShadeModel(GL11.GL_SMOOTH);
        for (int j = 1; j <= rows; j++) {
            float y = y0 + j * ch;
            for (int i = 0; i <= cols; i++) cur[i] = shade(near, x0 + i * cw, y, dark);
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            for (int i = 0; i <= cols; i++) {
                float x = x0 + i * cw;
                GL11.glColor4f(0.02f, 0.03f, 0.06f, prev[i]);
                GL11.glVertex2f(x, y - ch);
                GL11.glColor4f(0.02f, 0.03f, 0.06f, cur[i]);
                GL11.glVertex2f(x, y);
            }
            GL11.glEnd();
            float[] t = prev;
            prev = cur;
            cur = t;
        }

        // Last-seen markers
        float now = engine.getTotalElapsedTime(false);
        GL11.glLineWidth(1.5f);
        for (Map.Entry<ShipAPI, float[]> e : lastSeen.entrySet()) {
            float[] s = e.getValue();
            float left = 1f - (now - s[2]) / Math.max(0.1f, TcoSettings.fogLastSeenSeconds);
            if (left <= 0f) continue;
            float r = Math.max(40f, e.getKey().getCollisionRadius() * 0.8f);
            GL11.glColor4f(1f, 0.55f, 0.25f, 0.75f * left);
            float[][] corners = { { 0f, r }, { r, 0f }, { 0f, -r }, { -r, 0f } };
            GL11.glBegin(GL11.GL_LINES);
            for (int c = 0; c < 4; c++) {
                float[] p = corners[c], q = corners[(c + 1) % 4];
                for (int k = 0; k < 4; k++) {
                    float t0 = k / 4f, t1 = t0 + 0.14f;
                    GL11.glVertex2f(s[0] + p[0] + (q[0] - p[0]) * t0, s[1] + p[1] + (q[1] - p[1]) * t0);
                    GL11.glVertex2f(s[0] + p[0] + (q[0] - p[0]) * t1, s[1] + p[1] + (q[1] - p[1]) * t1);
                }
            }
            float x = r * 0.25f;
            GL11.glVertex2f(s[0] - x, s[1] - x);
            GL11.glVertex2f(s[0] + x, s[1] + x);
            GL11.glVertex2f(s[0] - x, s[1] + x);
            GL11.glVertex2f(s[0] + x, s[1] - x);
            GL11.glEnd();
        }
        GL11.glPopAttrib();
    }

    private static float shade(List<float[]> near, float x, float y, float dark) {
        float seen = 0f;
        for (float[] eye : near) {
            float d = (float) Math.hypot(x - eye[0], y - eye[1]);
            float edge = eye[2] * 0.15f;
            float v = (eye[2] - d) / edge;
            if (v >= 1f) return 0f;
            if (v > seen) seen = v;
        }
        return dark * (1f - Math.max(0f, seen));
    }
}
