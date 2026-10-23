package tco.armor;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;
import tco.hullmods.DamageControl;

/**
 * Fires aboard a ship from cook-offs and hull breaches; hull damage over time, never below fireHullFloor.
 * <p>
 * The drawing ({@link #emit}, the colour helpers and the style colours) is adapted from
 * GraphicsLib (c) 2014 by Harrison Snodgrass (DarkRevenant), licensed under CC BY 4.0,
 * https://creativecommons.org/licenses/by/4.0/ . See CREDITS.txt for what was changed.
 */
public final class Fires {

    private static final Color MAIN = new Color(255, 160, 30);
    private static final Color CLEAR = new Color(0, 0, 0, 0);
    private static final Color WHITE = new Color(255, 255, 255, 255);
    private static final Color DEFAULT_EXPLOSION = new Color(255, 145, 75, 255);
    private static final float INTERVAL = 0.1f;

    private static class Fire {
        ShipAPI ship;
        float lx, ly;
        float scale;
        boolean big;
        float left;
        float tick;
    }

    private static final List<Fire> fires = new ArrayList<Fire>();
    private static Map<String, Color> styleColors;
    private static int logged;

    private Fires() {
    }

    public static void reset() {
        fires.clear();
        logged = 0;
    }

    static void tryIgnite(CombatEngineAPI engine, ShipAPI ship, Vector2f at, boolean big, float caliber) {
        if (!TcoSettings.firesEnabled || ship == null || !ship.isAlive() || ship.isHulk()) return;
        float chance = (big ? TcoSettings.fireChanceCookoff : TcoSettings.fireChanceBreach) * caliber;
        if (Math.random() >= chance) return;
        int count = 0;
        for (Fire f : fires) if (f.ship == ship) count++;
        if (count >= TcoSettings.fireMaxPerShip) return;

        Fire fire = new Fire();
        fire.ship = ship;
        float dx = at.x - ship.getLocation().x;
        float dy = at.y - ship.getLocation().y;
        double a = Math.toRadians(-ship.getFacing());
        fire.lx = (float) (dx * Math.cos(a) - dy * Math.sin(a));
        fire.ly = (float) (dx * Math.sin(a) + dy * Math.cos(a));
        fire.big = big;
        // GraphicsLib's flame range is 0.35-1.65
        fire.scale = big ? 1f + (float) Math.random() * 0.65f : 0.35f + (float) Math.random() * 0.45f;
        float min = TcoSettings.fireMinSeconds;
        float max = Math.max(min, TcoSettings.fireMaxSeconds);
        fire.left = (min + (float) Math.random() * (max - min)) * DamageControl.fireDurationMult(ship);
        fires.add(fire);
        ArmorEffects.label(engine, at, big ? "Fire!" : "Small fire", MAIN, ship);
        if (logged++ < 20) {
            Global.getLogger(Fires.class).info("TCO fire: " + (big ? "big" : "small") + " fire on " + ship.getName()
                    + " for " + Math.round(fire.left) + " s");
        }
    }

    /** Starts a fire regardless of the fire chances. */
    public static void ignite(CombatEngineAPI engine, ShipAPI ship, Vector2f at, boolean big, float seconds) {
        if (!TcoSettings.firesEnabled || ship == null || !ship.isAlive() || ship.isHulk()) return;
        int count = 0;
        for (Fire f : fires) if (f.ship == ship) count++;
        if (count >= TcoSettings.fireMaxPerShip + 2) return;
        Fire fire = new Fire();
        fire.ship = ship;
        float dx = at.x - ship.getLocation().x;
        float dy = at.y - ship.getLocation().y;
        double a = Math.toRadians(-ship.getFacing());
        fire.lx = (float) (dx * Math.cos(a) - dy * Math.sin(a));
        fire.ly = (float) (dx * Math.sin(a) + dy * Math.cos(a));
        fire.big = big;
        fire.scale = big ? 1f + (float) Math.random() * 0.65f : 0.35f + (float) Math.random() * 0.45f;
        fire.left = seconds * DamageControl.fireDurationMult(ship);
        fires.add(fire);
    }

    public static void advance(CombatEngineAPI engine, float amount) {
        if (fires.isEmpty() || engine.isPaused() || amount <= 0f) return;
        Iterator<Fire> it = fires.iterator();
        while (it.hasNext()) {
            Fire fire = it.next();
            ShipAPI ship = fire.ship;
            fire.left -= amount;
            if (fire.left <= 0f || !ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship)) {
                it.remove();
                continue;
            }

            // Hull damage as a share of max hull per second, never below the floor
            float dps = ship.getMaxHitpoints() * (fire.big ? TcoSettings.fireHullPctBig : TcoSettings.fireHullPctSmall)
                    * DamageControl.fireDamageMult(ship);
            float hull = ship.getHitpoints();
            float floor = TcoSettings.fireHullFloor;
            if (hull > floor) ship.setHitpoints(Math.max(floor, hull - dps * amount));

            fire.tick += amount;
            if (fire.tick < INTERVAL) continue;
            fire.tick -= INTERVAL;
            Vector2f point = worldPoint(fire);
            if (!engine.getViewport().isNearViewport(point, 500f)) continue;
            emit(engine, fire, point);
        }
    }

    private static void emit(CombatEngineAPI engine, Fire fire, Vector2f point) {
        ShipAPI ship = fire.ship;
        Color explosion = explosionColor(ship);
        Color color = jitter(blend(blend(explosion, MAIN, 0.75f), CLEAR, 0.2f), 50f);
        Color color2 = jitter(blend(blend(explosion, MAIN, 0.75f), CLEAR, 0.75f), 30f);
        Color color3 = blend(explosion, WHITE, 0.5f);

        // Fades over the last 1.5 s
        float scale = fire.scale * Math.min(1f, 0.3f + fire.left / 1.5f * 0.7f);
        float sizeMod = sizeMod(ship.getHullSize());
        float size = (float) Math.random() * 25f * sizeMod * scale;
        float size2 = ((float) Math.random() * 10f + 40f) * sizeMod * scale;
        float size3 = ((float) Math.random() * 40f + 80f) * sizeMod * scale;
        float size4 = ((float) Math.random() * 15f + 5f) * scale;

        Vector2f vel = new Vector2f((float) Math.random() * 40f - 20f, (float) Math.random() * 40f - 20f);
        Vector2f vel2 = new Vector2f((float) Math.random() * 20f - 10f, (float) Math.random() * 20f - 10f);

        // Fewer flames when slow
        if (Math.random() > (ship.getVelocity().length() + 50f) / 75f) return;
        engine.addHitParticle(point, ship.getVelocity(), size4, 0.65f, (float) Math.random() * 0.15f + 0.3f, color3);
        if (Math.random() >= 0.5f) {
            engine.spawnExplosion(point, vel, color, size, (float) Math.random() * 1f + 0.5f);
            engine.addSmokeParticle(point, vel2, size2, (float) Math.random() * 0.025f + 0.1f,
                    (float) Math.random() * 4f + 4f, color2);
        } else {
            engine.addHitParticle(point, vel, size3, (float) Math.random() * 0.05f + 0.05f,
                    (float) Math.random() * 0.5f + 0.25f, color);
        }
    }

    private static float sizeMod(HullSize size) {
        if (size == HullSize.FRIGATE) return 0.75f;
        if (size == HullSize.CRUISER) return 1.25f;
        if (size == HullSize.CAPITAL_SHIP) return 1.5f;
        if (size == HullSize.FIGHTER) return 0.5f;
        return 1f;
    }

    private static Color explosionColor(ShipAPI ship) {
        if (ship.getExplosionFlashColorOverride() != null) return ship.getExplosionFlashColorOverride();
        if (styleColors == null) loadStyleColors();
        Color c = styleColors.get(ship.getHullStyleId());
        return c != null ? c : DEFAULT_EXPLOSION;
    }

    private static void loadStyleColors() {
        styleColors = new HashMap<String, Color>();
        try {
            JSONObject styles = Global.getSettings().getMergedJSON("data/config/hull_styles.json");
            Iterator<?> keys = styles.keys();
            while (keys.hasNext()) {
                String id = (String) keys.next();
                JSONObject style = styles.optJSONObject(id);
                JSONArray rgb = style == null ? null : style.optJSONArray("baseCombatExplosionColor");
                if (rgb == null) continue;
                styleColors.put(id, new Color(clamp(rgb.optInt(0, 255)), clamp(rgb.optInt(1, 255)),
                        clamp(rgb.optInt(2, 255)), clamp(rgb.optInt(3, 255))));
            }
        } catch (Exception e) {
            Global.getLogger(Fires.class).warn("TCO: could not read hull_styles.json for fire colours", e);
        }
    }

    private static Vector2f worldPoint(Fire fire) {
        ShipAPI ship = fire.ship;
        double a = Math.toRadians(ship.getFacing());
        float x = (float) (fire.lx * Math.cos(a) - fire.ly * Math.sin(a));
        float y = (float) (fire.lx * Math.sin(a) + fire.ly * Math.cos(a));
        return new Vector2f(ship.getLocation().x + x, ship.getLocation().y + y);
    }

    private static int clamp(int x) {
        return Math.max(0, Math.min(255, x));
    }

    private static Color blend(Color a, Color b, float amount) {
        float keep = 1f - amount;
        return new Color(clamp((int) (a.getRed() * keep + b.getRed() * amount)),
                clamp((int) (a.getGreen() * keep + b.getGreen() * amount)),
                clamp((int) (a.getBlue() * keep + b.getBlue() * amount)),
                clamp((int) (a.getAlpha() * keep + b.getAlpha() * amount)));
    }

    private static Color jitter(Color c, float amount) {
        return new Color(clamp(c.getRed() + (int) (((float) Math.random() - 0.5f) * amount)),
                clamp(c.getGreen() + (int) (((float) Math.random() - 0.5f) * amount)),
                clamp(c.getBlue() + (int) (((float) Math.random() - 0.5f) * amount)), c.getAlpha());
    }
}
