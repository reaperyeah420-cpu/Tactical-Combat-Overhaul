package tco.rts;

import java.util.Map;
import java.util.Set;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;

import tco.TcoSettings;
import tco.hullmods.CombatInformationCenter;
import tco.radar.CommandNetwork;
import tco.radar.RadarCombatPlugin;

/**
 * The CIC view (stage 4; RTS mode, toggled on the threat board while you have a command ship): over the battle, for
 * the enemy ships your fleet knows (in sight or on radar), their weapon reach and where they'll be in 10 s; guided
 * missiles in flight with lines to their targets (yours cyan, theirs red); active area jammers' reach; and your command
 * ships' guidance, PD-network and fighter-direction reach.
 */
final class CicView {

    private static final float PREDICT_SECONDS = 10f;

    private CicView() {
    }

    static void render(CombatEngineAPI engine, ViewportAPI viewport) {
        if (!ThreatBoard.viewOn() || !ThreatBoard.wanted(engine)) return;
        RadarCombatPlugin radar = RadarCombatPlugin.get(engine);
        if (radar == null) return;
        Set<ShipAPI> contacts = radar.contacts(0);
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(1f);

        for (ShipAPI ship : engine.getShips()) {
            if (!ship.isAlive() || ship.isHulk() || ship.isFighter() || ship.isDrone() || ship.isShuttlePod()) continue;
            Vector2f at = ship.getLocation();
            if (ship.getOwner() == 1) {
                boolean known = !FogOfWar.isHidden(ship) || (contacts != null && contacts.contains(ship));
                if (!known) continue;
                // Weapon reach
                float reach = weaponReach(ship);
                if (reach > 0f && viewport.isNearViewport(at, reach)) {
                    GL11.glColor4f(1f, 0.3f, 0.25f, 0.18f);
                    circle(at.x, at.y, reach, 64);
                }
                // Where it will be
                Vector2f v = ship.getVelocity();
                if (v.lengthSquared() > 25f) {
                    GL11.glColor4f(1f, 0.55f, 0.3f, 0.45f);
                    GL11.glBegin(GL11.GL_LINES);
                    for (int i = 0; i < 10; i += 2) {
                        float t0 = PREDICT_SECONDS * i / 10f, t1 = PREDICT_SECONDS * (i + 1) / 10f;
                        GL11.glVertex2f(at.x + v.x * t0, at.y + v.y * t0);
                        GL11.glVertex2f(at.x + v.x * t1, at.y + v.y * t1);
                    }
                    GL11.glEnd();
                }
            } else if (ship.getOwner() == 0 && CombatInformationCenter.level(ship) > 0 && radar.isEmitting(ship)) {
                // The command ship's reach: missile guidance, fighter direction, PD network
                boolean cic = CombatInformationCenter.level(ship) == 2;
                GL11.glColor4f(0.4f, 0.9f, 1f, 0.12f);
                circle(at.x, at.y, cic ? TcoSettings.cicGuidanceRange : TcoSettings.auxGuidanceRange, 96);
                GL11.glColor4f(0.5f, 1f, 0.6f, 0.14f);
                circle(at.x, at.y, CommandNetwork.fighterRange(ship), 80);
                GL11.glColor4f(1f, 0.85f, 0.4f, 0.16f);
                circle(at.x, at.y, CommandNetwork.pdRange(ship), 72);
            }
            // Area jammers running (enemy ones only when known)
            if (RadarCombatPlugin.areaJamActive(engine, ship)) {
                boolean show = ship.getOwner() == 0 || !FogOfWar.isHidden(ship) || (contacts != null && contacts.contains(ship));
                if (show) {
                    float r = RadarCombatPlugin.areaRadius(ship);
                    GL11.glColor4f(0.85f, 0.4f, 1f, 0.25f);
                    circle(at.x, at.y, r, 72);
                }
            }
        }

        // Guided missiles and their targets
        for (int side = 0; side <= 1; side++) {
            for (Map.Entry<MissileAPI, ShipAPI> e : radar.homingOn(side).entrySet()) {
                MissileAPI m = e.getKey();
                ShipAPI target = e.getValue();
                boolean ours = m.getOwner() == 0;
                // Enemy missiles only where your side can see them or one of your ships is the target
                if (!ours && !FogOfWar.isVisible(m.getLocation()) && target.getOwner() != 0) continue;
                if (ours) GL11.glColor4f(0.4f, 0.9f, 1f, 0.45f);
                else GL11.glColor4f(1f, 0.25f, 0.2f, 0.55f);
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(m.getLocation().x, m.getLocation().y);
                GL11.glVertex2f(target.getLocation().x, target.getLocation().y);
                GL11.glEnd();
            }
        }
        GL11.glPopAttrib();
    }

    private static float weaponReach(ShipAPI ship) {
        float reach = 0f;
        for (WeaponAPI w : ship.getAllWeapons()) {
            if (w.isDecorative() || w.hasAIHint(WeaponAPI.AIHints.PD_ONLY)) continue;
            if (w.getType() == WeaponAPI.WeaponType.MISSILE) continue;
            reach = Math.max(reach, w.getRange());
        }
        return reach > 0f ? reach + ship.getCollisionRadius() : 0f;
    }

    private static void circle(float x, float y, float r, int steps) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < steps; i++) {
            double a = Math.PI * 2.0 * i / steps;
            GL11.glVertex2f(x + (float) Math.cos(a) * r, y + (float) Math.sin(a) * r);
        }
        GL11.glEnd();
    }
}
