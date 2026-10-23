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

import com.fs.starfarer.api.combat.BeamAPI;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.EmpArcEntityAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;

import tco.TcoSettings;
import tco.radar.SensorPickets;

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
    // Sight for the player's eyes only (the missile camera): ships there draw, but stay hidden for every rule
    private static final List<float[]> viewEyes = new ArrayList<float[]>();
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

    /** Drawn hidden: hidden, and not in the view-only sight either (what the screen shows, nothing else). */
    public static boolean isDrawnHidden(ShipAPI ship) {
        if (!isHidden(ship)) return false;
        return !canSeeView(ship.getLocation(), ship.getCollisionRadius() * 0.5f);
    }

    /** For drawing only: the point shows on screen (also inside the view-only sight). */
    public static boolean isVisibleView(Vector2f point, float radius) {
        return !isActive() || point == null || canSeeView(point, radius);
    }

    /** View-only sight around a point (the missile camera); replaces the previous one. */
    public static void setViewEye(Vector2f at, float radius) {
        viewEyes.clear();
        if (at != null && radius > 0f) viewEyes.add(new float[] { at.x, at.y, radius });
    }

    public static void clearViewEye() {
        viewEyes.clear();
    }

    /** The player's side can see the point (true while off). */
    public static boolean isVisible(Vector2f point) {
        return !isActive() || canSee(point, 0f);
    }

    /** The player's side can see anything of this radius around the point (true while off). */
    public static boolean isVisible(Vector2f point, float radius) {
        return !isActive() || point == null || canSee(point, radius);
    }

    /**
     * For the render patches: an entity the player's side can't see. Hidden enemy ships once faded out, and
     * enemy shots, beams, EMP arcs and flares out of sight. The player's and allies' own always draw.
     */
    public static boolean hidesRender(Object entity) {
        if (!isActive() || eyes.isEmpty()) return false;
        if (entity instanceof ShipAPI) {
            ShipAPI ship = (ShipAPI) entity;
            if (!isDrawnHidden(ship)) return false;
            Float a = alpha.get(ship);
            return a != null && a <= 0.01f;
        }
        if (entity instanceof BeamAPI) {
            BeamAPI beam = (BeamAPI) entity;
            if (beam.getSource() == null || beam.getSource().getOwner() == 0) return false;
            return !canSeeView(beam.getFrom(), 0f) && !canSeeView(beam.getTo(), 0f);
        }
        if (entity instanceof DamagingProjectileAPI || entity instanceof EmpArcEntityAPI
                || (entity instanceof CombatEntityAPI && entity.getClass().getName().endsWith(".systems.Flare"))) {
            CombatEntityAPI e = (CombatEntityAPI) entity;
            if (e.getOwner() == 0) return false;
            return !canSeeView(e.getLocation(), e.getCollisionRadius());
        }
        return false;
    }

    public static float sightRange(ShipAPI ship) {
        return ship.getMutableStats().getSightRadiusMod().computeEffective(BASE_SIGHT);
    }

    private static boolean reveals(ShipAPI ship) {
        return reveals(ship, 0);
    }

    /** A ship that gives its side sight (same rules as the engine's fog, plus no shuttle pods). */
    public static boolean reveals(ShipAPI ship, int side) {
        if (ship.getOwner() != side || !ship.isAlive() || ship.isHulk()) return false;
        // Sensor pickets are eyes, though drones
        if (SensorPickets.isPicket(ship)) return true;
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

    private static boolean canSeeView(Vector2f p, float slack) {
        if (canSee(p, slack)) return true;
        for (float[] eye : viewEyes) {
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
            viewEyes.clear();
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
            // Drawing follows the view-only sight too; markers and rules don't
            boolean drawnHidden = hidden && isDrawnHidden(ship);
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
            if (drawnHidden && !overlayHidden.contains(ship) && !ship.isForceHideFFOverlay()) {
                ship.setForceHideFFOverlay(true);
                overlayHidden.add(ship);
            } else if (!drawnHidden && overlayHidden.remove(ship)) {
                ship.setForceHideFFOverlay(false);
            }
            float target = drawnHidden ? 0f : 1f;
            if (a < target) a = Math.min(target, a + FADE_PER_SECOND * amount);
            else if (a > target) a = Math.max(target, a - FADE_PER_SECOND * amount);
            if (current == null && a >= 1f && !drawnHidden) continue;
            ship.setExtraAlphaMult2(a);
            if (a >= 1f && !drawnHidden) alpha.remove(ship);
            else alpha.put(ship, a);
        }
        // Drop departed ships and expired markers. A ship that changed sides while faded (captured by
        // boarders, now ours) is skipped by the loop above, so it's shown again here
        for (Iterator<ShipAPI> it = alpha.keySet().iterator(); it.hasNext();) {
            ShipAPI ship = it.next();
            if (!engine.isEntityInPlay(ship)) {
                it.remove();
            } else if (ship.getOwner() == 0 || ship.getOwner() == 100) {
                ship.setExtraAlphaMult2(1f);
                it.remove();
            }
        }
        for (Iterator<ShipAPI> it = seen.iterator(); it.hasNext();) {
            ShipAPI ship = it.next();
            if (!engine.isEntityInPlay(ship) || ship.getOwner() == 0 || ship.getOwner() == 100) it.remove();
        }
        for (Iterator<ShipAPI> it = overlayHidden.iterator(); it.hasNext();) {
            ShipAPI ship = it.next();
            if (!engine.isEntityInPlay(ship)) {
                it.remove();
            } else if (ship.getOwner() == 0 || ship.getOwner() == 100) {
                ship.setForceHideFFOverlay(false);
                it.remove();
            }
        }
        for (Iterator<Map.Entry<ShipAPI, float[]>> it = lastSeen.entrySet().iterator(); it.hasNext();) {
            Map.Entry<ShipAPI, float[]> e = it.next();
            if (!e.getKey().isAlive() || e.getKey().isHulk() || !engine.isEntityInPlay(e.getKey())
                    || e.getKey().getOwner() == 0 || e.getKey().getOwner() == 100
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
        for (float[] eye : viewEyes) {
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
