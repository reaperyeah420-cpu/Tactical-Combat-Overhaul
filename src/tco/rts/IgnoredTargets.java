package tco.rts;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import org.lwjgl.opengl.GL11;

import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponGroupAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.AiUtil;

/**
 * Enemy ships the player marked "ignore" (a ship being boarded, mostly): the player's ships, fighters included,
 * don't fire at them, don't keep them as a target and don't chase them. Only the ship the player flies can still
 * shoot one, with the weapon group they fire by hand.
 */
public class IgnoredTargets extends BaseEveryFrameCombatPlugin {

    private static final String ENGINE_KEY = "tco_ignoredTargets";
    private static final int SIDE = 0;

    private CombatEngineAPI engine;
    private final Set<ShipAPI> ignored = Collections.newSetFromMap(new WeakHashMap<ShipAPI, Boolean>());

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
    }

    private static IgnoredTargets get(CombatEngineAPI engine) {
        Object plugin = engine == null ? null : engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof IgnoredTargets ? (IgnoredTargets) plugin : null;
    }

    /** Ignore an enemy ship, or stop ignoring it; true if it is now ignored. */
    public static boolean toggle(CombatEngineAPI engine, ShipAPI ship) {
        IgnoredTargets plugin = get(engine);
        if (plugin == null || ship == null) return false;
        if (plugin.ignored.remove(ship)) return false;
        plugin.ignored.add(ship);
        return true;
    }

    public static boolean isIgnored(CombatEngineAPI engine, ShipAPI ship) {
        IgnoredTargets plugin = get(engine);
        return plugin != null && plugin.covers(ship);
    }

    private boolean covers(ShipAPI ship) {
        if (ship == null || ignored.isEmpty()) return false;
        return ignored.contains(ship) || (ship.getParentStation() != null && ignored.contains(ship.getParentStation()));
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || ignored.isEmpty()) return;
        // Gone, or ours now (captured)
        for (Iterator<ShipAPI> it = ignored.iterator(); it.hasNext();) {
            ShipAPI ship = it.next();
            if (!ship.isAlive() || ship.isHulk() || !engine.isEntityInPlay(ship) || ship.getOwner() == SIDE) it.remove();
        }
        if (ignored.isEmpty()) return;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != SIDE || ship.isAlly() || !ship.isAlive() || ship.isHulk()) continue;
            boolean flown = AiUtil.playerFlying(engine, ship);
            for (WeaponGroupAPI group : ship.getWeaponGroupsCopy()) {
                // The group the player fires by hand stays theirs
                if (flown && !group.isAutofiring() && group == ship.getSelectedGroupAPI()) continue;
                for (WeaponAPI weapon : group.getWeaponsCopy()) {
                    AutofireAIPlugin auto = group.getAutofirePlugin(weapon);
                    ShipAPI aim = auto == null ? null : auto.getTargetShip();
                    if (aim == null && !group.isAutofiring()) aim = ship.getShipTarget();
                    if (covers(aim)) weapon.setForceNoFireOneFrame(true);
                }
            }
            if (flown) continue;
            if (covers(ship.getShipTarget())) ship.setShipTarget(null);
            ShipwideAIFlags flags = ship.getAIFlags();
            if (flags == null) continue;
            Object maneuver = flags.getCustom(AIFlags.MANEUVER_TARGET);
            if (maneuver instanceof ShipAPI && covers((ShipAPI) maneuver)) {
                flags.unsetFlag(AIFlags.MANEUVER_TARGET);
                flags.setFlag(AIFlags.DO_NOT_PURSUE, 0.5f);
            }
            // A carrier's strike target is what its fighters go for
            Object strike = flags.getCustom(AIFlags.CARRIER_FIGHTER_TARGET);
            if (strike instanceof ShipAPI && covers((ShipAPI) strike)) flags.unsetFlag(AIFlags.CARRIER_FIGHTER_TARGET);
        }
    }

    /** A dashed pale ring round each ignored ship. */
    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (engine == null || ignored.isEmpty()) return;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2f);
        GL11.glColor4f(0.6f, 0.85f, 1f, 0.75f);
        for (ShipAPI ship : ignored) {
            if (FogOfWar.isHidden(ship)) continue;
            float r = ship.getCollisionRadius() + 60f;
            float cx = ship.getLocation().x, cy = ship.getLocation().y;
            int dashes = 24;
            GL11.glBegin(GL11.GL_LINES);
            for (int i = 0; i < dashes; i++) {
                double a0 = Math.PI * 2.0 * i / dashes;
                double a1 = Math.PI * 2.0 * (i + 0.5) / dashes;
                GL11.glVertex2f(cx + (float) Math.cos(a0) * r, cy + (float) Math.sin(a0) * r);
                GL11.glVertex2f(cx + (float) Math.cos(a1) * r, cy + (float) Math.sin(a1) * r);
            }
            GL11.glEnd();
        }
        GL11.glPopAttrib();
    }
}
