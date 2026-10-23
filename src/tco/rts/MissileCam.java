package tco.rts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;

import tco.InputKeys;
import tco.TcoSettings;
import tco.Typing;

/**
 * Missile camera: while keyMissileCam is held, the view follows your newest missile in flight (the flown ship's, or
 * the selected ships' in RTS mode), lingers on where it ended for missileCamLingerSeconds, then moves to the next.
 * The fog clears around it for your eyes only (FogOfWar view eyes): what it shows can't be clicked, targeted or
 * known by any ship or the AI.
 */
public class MissileCam extends BaseEveryFrameCombatPlugin {

    public static final String ENGINE_KEY = "tco_missileCam";

    private CombatEngineAPI engine;
    // When each missile was first seen, to find the newest
    private final Map<MissileAPI, Float> launched = new WeakHashMap<MissileAPI, Float>();
    private MissileAPI followed;
    private final Vector2f at = new Vector2f();
    private boolean holding, wasDown, hasView, viewTaken, told;
    private float lingerUntil = -1f;
    private LabelAPI label;
    private String labelText;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
    }

    private static MissileCam get(CombatEngineAPI engine) {
        Object plugin = engine == null ? null : engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof MissileCam ? (MissileCam) plugin : null;
    }

    /** Where the RTS camera should look, or null when the missile camera is off. */
    public static Vector2f view(CombatEngineAPI engine) {
        MissileCam cam = get(engine);
        return cam != null && cam.holding && cam.hasView ? cam.at : null;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null) return;
        float now = engine.getTotalElapsedTime(false);
        for (MissileAPI m : engine.getMissiles()) {
            if (!launched.containsKey(m)) launched.put(m, now);
        }
        boolean down = InputKeys.isDown(TcoSettings.keyMissileCam) && !Typing.active() && !engine.isCombatOver()
                && !engine.isUIShowingDialog() && engine.getCombatUI() != null && !engine.getCombatUI().isShowingCommandUI();
        if (down && !wasDown) start();
        if (!down && wasDown) stop();
        wasDown = down;
        if (!holding) return;

        if (followed != null && alive(followed)) {
            at.set(followed.getLocation());
            hasView = true;
            lingerUntil = -1f;
        } else {
            // It hit or ran out: look where it ended for a moment, then pick up the next one
            if (followed != null && lingerUntil < 0f) lingerUntil = now + TcoSettings.missileCamLingerSeconds;
            if (followed == null || now >= lingerUntil) {
                MissileAPI next = newest();
                if (next != null) {
                    followed = next;
                    at.set(next.getLocation());
                    hasView = true;
                    lingerUntil = -1f;
                } else if (!hasView && !told) {
                    told = true;
                    engine.getCombatUI().addMessage(0, new Color(255, 200, 120), "Missile camera: no missile of yours in flight");
                }
            }
        }
        if (hasView) {
            FogOfWar.setViewEye(at, TcoSettings.missileCamSight);
            // Outside RTS mode the game's camera follows the ship: take it while held
            if (!RtsCombatPlugin.holdsCamera(engine)) {
                ViewportAPI viewport = engine.getViewport();
                float w = viewport.getVisibleWidth(), h = viewport.getVisibleHeight();
                viewport.setExternalControl(true);
                viewport.set(at.x - w / 2f, at.y - h / 2f, w, h);
                viewTaken = true;
            } else if (viewTaken) {
                viewTaken = false;
            }
        }
    }

    private void start() {
        holding = true;
        told = false;
        hasView = false;
        lingerUntil = -1f;
        followed = newest();
        if (followed != null) {
            at.set(followed.getLocation());
            hasView = true;
        }
    }

    private void stop() {
        holding = false;
        followed = null;
        hasView = false;
        FogOfWar.clearViewEye();
        if (viewTaken) {
            viewTaken = false;
            if (!RtsCombatPlugin.holdsCamera(engine)) engine.getViewport().setExternalControl(false);
        }
    }

    private boolean alive(MissileAPI m) {
        return engine.isEntityInPlay(m) && !m.didDamage() && !m.isFizzling();
    }

    /** The newest live missile from the ships you command. */
    private MissileAPI newest() {
        Collection<ShipAPI> sources = sources();
        MissileAPI best = null;
        float bestTime = -Float.MAX_VALUE;
        for (MissileAPI m : engine.getMissiles()) {
            if (m.isFlare() || !alive(m) || m.isDecoyFlare()) continue;
            ShipAPI source = m.getSource();
            if (source != null && source.isFighter() && source.getWing() != null && source.getWing().getSourceShip() != null) {
                source = source.getWing().getSourceShip();
            }
            if (source == null || !sources.contains(source)) continue;
            Float t = launched.get(m);
            float time = t == null ? 0f : t;
            if (time > bestTime) {
                bestTime = time;
                best = m;
            }
        }
        return best;
    }

    private Collection<ShipAPI> sources() {
        List<ShipAPI> list = new ArrayList<ShipAPI>();
        if (RtsCombatPlugin.isRtsView(engine)) {
            list.addAll(RtsCombatPlugin.selectedShips(engine));
            if (list.isEmpty()) {
                // Nothing selected: any of your ships
                for (ShipAPI ship : engine.getShips()) {
                    if (ship.getOwner() == 0 && !ship.isAlly() && ship.isAlive()) list.add(ship);
                }
            }
        } else if (engine.getPlayerShip() != null) {
            list.add(engine.getPlayerShip());
        }
        return list;
    }

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (!holding || !hasView) return;
        float r = followed != null && alive(followed) ? 40f : 70f;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2f);
        GL11.glColor4f(1f, 0.85f, 0.4f, 0.8f);
        float l = r * 0.4f;
        GL11.glBegin(GL11.GL_LINES);
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                GL11.glVertex2f(at.x + sx * r, at.y + sy * r);
                GL11.glVertex2f(at.x + sx * (r - l), at.y + sy * r);
                GL11.glVertex2f(at.x + sx * r, at.y + sy * r);
                GL11.glVertex2f(at.x + sx * r, at.y + sy * (r - l));
            }
        }
        GL11.glEnd();
        GL11.glPopAttrib();
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        if (!holding) return;
        String text;
        if (!hasView) text = "MISSILE CAM  -  no missile in flight";
        else if (followed != null && alive(followed)) {
            String name = followed.getWeaponSpec() != null ? followed.getWeaponSpec().getWeaponName() : "missile";
            text = "MISSILE CAM  -  " + name;
        } else {
            text = "MISSILE CAM  -  impact";
        }
        try {
            if (label == null) label = Global.getSettings().createLabel(text, Fonts.DEFAULT_SMALL);
            if (!text.equals(labelText)) {
                label.setText(text);
                labelText = text;
            }
            label.setColor(new Color(255, 215, 130));
            label.getPosition().setSize(400f, 16f);
            float w = Global.getSettings().getScreenWidth(), h = Global.getSettings().getScreenHeight();
            label.getPosition().setLocation(w / 2f - label.computeTextWidth(text) / 2f, h - 60f);
            label.render(1f);
        } catch (RuntimeException e) {
        }
    }
}
