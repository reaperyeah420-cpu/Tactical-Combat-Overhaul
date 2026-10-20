package tco.fob;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.loading.DamagingExplosionSpec;

import tco.Msg;
import tco.TcoSettings;
import tco.ammo.AmmoRules;
import tco.rts.FogOfWar;

/**
 * FOB Control Array objective: the captor gets a Supply FOB on its half; holding the array restocks it,
 * and a destroyed FOB is replaced after a delay.
 */
public class FobCombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final float COOKOFF_RADIUS = 1500f;
    private static final float COOKOFF_CORE = 500f;
    private static final float COOKOFF_MAX_DAMAGE = 9000f;
    private static final float COOKOFF_MIN_DAMAGE = 2500f;
    private static final int SECONDARY_BLASTS = 7;

    private static final float MIN_FROM_CENTRE = 0.10f;
    private static final float MAX_FROM_CENTRE = 0.35f;
    private static final float CLEARANCE = 1500f;

    private CombatEngineAPI engine;
    private final Map<Integer, ShipAPI> platforms = new HashMap<Integer, ShipAPI>();
    private final Map<Integer, Vector2f> sites = new HashMap<Integer, Vector2f>();
    private final Map<Integer, Float> respawn = new HashMap<Integer, Float>();
    private final List<Blast> blasts = new ArrayList<Blast>();

    private static class Blast {
        float delay;
        Vector2f loc;
        float power;
        ShipAPI source;
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine != null && engine.isPaused()) amount = 0f;
        if (engine == null) return;

        // Destroyed platforms first
        Iterator<Map.Entry<Integer, ShipAPI>> it = platforms.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, ShipAPI> entry = it.next();
            ShipAPI platform = entry.getValue();
            if (!platform.isAlive() || platform.isHulk()) {
                cookOff(platform, entry.getKey());
                sites.remove(entry.getKey());
                respawn.put(entry.getKey(), TcoSettings.fobRespawnSeconds);
                it.remove();
                continue;
            }
            Vector2f site = sites.get(entry.getKey());
            if (site != null) platform.getLocation().set(site);
            // Position only; the rotation hullmod turns it
            platform.getVelocity().set(0f, 0f);
        }

        if (amount > 0f) {
            for (Map.Entry<Integer, Float> timer : respawn.entrySet()) {
                if (timer.getValue() > 0f) timer.setValue(timer.getValue() - amount);
            }
        }

        for (BattleObjectiveAPI objective : engine.getObjectives()) {
            if (!SupplyFob.OBJECTIVE_ID.equals(objective.getType())) continue;
            int owner = objective.getOwner();
            if (owner != 0 && owner != 1) continue;

            ShipAPI platform = platforms.get(owner);
            if (platform == null) {
                // First one is immediate; later ones need the array held
                Float wait = respawn.get(owner);
                if (wait == null || wait <= 0f) build(owner);
            } else if (amount > 0f) {
                requestRestock(platform, TcoSettings.fobArrayRestockPerSecond * amount);
            }
        }

        if (amount > 0f && !blasts.isEmpty()) {
            Iterator<Blast> blastIt = blasts.iterator();
            while (blastIt.hasNext()) {
                Blast blast = blastIt.next();
                blast.delay -= amount;
                if (blast.delay > 0f) continue;
                blastIt.remove();
                explode(blast.loc, 500f, 200f, 2200f * blast.power, 600f * blast.power, blast.source);
            }
        }
    }

    private void requestRestock(ShipAPI platform, float units) {
        Object pending = platform.getCustomData().get(AmmoRules.SUPPLY_GAIN_KEY);
        float total = units + (pending instanceof Float ? (Float) pending : 0f);
        platform.setCustomData(AmmoRules.SUPPLY_GAIN_KEY, total);
    }

    private float sideSign(int owner) {
        float sum = 0f;
        int count = 0;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getOwner() != owner || ship.isFighter() || ship.isHulk() || !ship.isAlive()) continue;
            if (SupplyFob.isFobPart(ship) || ship.isShuttlePod() || ship.isStationModule()) continue;
            sum += ship.getLocation().y;
            count++;
        }
        if (count > 0 && Math.abs(sum / count) > 200f) return sum > 0f ? 1f : -1f;
        return owner == 0 ? -1f : 1f;
    }

    private Vector2f pickSite(int owner) {
        float w = engine.getMapWidth();
        float h = engine.getMapHeight();
        float sign = sideSign(owner);
        Vector2f best = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            float back = MIN_FROM_CENTRE + (float) Math.random() * (MAX_FROM_CENTRE - MIN_FROM_CENTRE);
            float across = ((float) Math.random() - 0.5f) * 0.6f;
            Vector2f candidate = new Vector2f(w * across, sign * h * back);
            if (best == null) best = candidate;

            boolean clear = true;
            for (BattleObjectiveAPI objective : engine.getObjectives()) {
                if (distance(candidate, objective.getLocation()) < CLEARANCE) clear = false;
            }
            for (ShipAPI ship : engine.getShips()) {
                if (ship.isFighter()) continue;
                if (distance(candidate, ship.getLocation()) < 700f + ship.getCollisionRadius()) clear = false;
            }
            if (clear) return candidate;
        }
        return best;
    }

    private float distance(Vector2f a, Vector2f b) {
        return (float) Math.hypot(a.x - b.x, a.y - b.y);
    }

    private void build(int owner) {
        try {
            Vector2f site = pickSite(owner);
            CombatFleetManagerAPI fleet = engine.getFleetManager(owner);
            boolean quiet = fleet.isSuppressDeploymentMessages();
            fleet.setSuppressDeploymentMessages(true);
            ShipAPI platform;
            try {
                platform = spawn(fleet, site);
            } finally {
                fleet.setSuppressDeploymentMessages(quiet);
            }
            fleet.removeDeployed(platform, false);

            // Normal AI for weapons and shields; the hull can't move
            platform.setInvalidTransferCommandTarget(true);
            for (ShipAPI part : platform.getChildModulesCopy()) {
                part.setInvalidTransferCommandTarget(true);
            }
            platforms.put(owner, platform);
            sites.put(owner, site);
            respawn.remove(owner);

            Msg.send(engine, owner == 0 ? Msg.PLAYER : Msg.ENEMY, owner == 0
                    ? "Control array captured: a " + SupplyFob.NAME + " has been set up behind your lines."
                    : "The enemy has set up a " + SupplyFob.NAME + " behind their lines.");
        } catch (RuntimeException e) {
            Global.getLogger(FobCombatPlugin.class).warn("TCO: could not build the Supply FOB platform", e);
            respawn.put(owner, 30f);
        }
    }

    private ShipAPI spawn(CombatFleetManagerAPI fleet, Vector2f site) {
        List<String> choices = new ArrayList<String>(TcoSettings.fobVariants);
        Collections.shuffle(choices);
        for (String variant : choices) {
            try {
                ShipAPI custom = fleet.spawnShipOrWing(variant, new Vector2f(site), 90f);
                if (custom != null && SupplyFob.isFob(custom)) return custom;
                Global.getLogger(FobCombatPlugin.class).warn("TCO: FOB variant '" + variant
                        + "' is not a Supply FOB hull, trying another");
                if (custom != null) engine.removeEntity(custom);
            } catch (RuntimeException e) {
                Global.getLogger(FobCombatPlugin.class).warn("TCO: FOB variant '" + variant
                        + "' could not be spawned, trying another", e);
            }
        }
        return fleet.spawnShipOrWing(SupplyFob.DEFAULT_VARIANT_ID, new Vector2f(site), 90f);
    }

    private void cookOff(ShipAPI platform, int owner) {
        float fraction = 1f;
        Object left = platform.getCustomData().get(AmmoRules.SUPPLY_KEY);
        Object max = platform.getCustomData().get(AmmoRules.SUPPLY_MAX_KEY);
        if (left instanceof Float && max instanceof Float && (Float) max > 0f) {
            fraction = Math.max(0f, Math.min(1f, (Float) left / (Float) max));
        }
        platform.setCustomData(AmmoRules.SUPPLY_KEY, 0f);
        float power = 0.35f + 0.65f * fraction;

        Vector2f loc = new Vector2f(platform.getLocation());
        explode(loc, COOKOFF_RADIUS * (0.7f + 0.3f * fraction), COOKOFF_CORE,
                COOKOFF_MAX_DAMAGE * power, COOKOFF_MIN_DAMAGE * power, platform);
        engine.spawnExplosion(loc, new Vector2f(), new Color(255, 190, 110), 1400f, 2.5f);
        engine.addHitParticle(loc, new Vector2f(), 2200f, 1f, 1.2f, new Color(255, 230, 180));

        for (int i = 0; i < SECONDARY_BLASTS; i++) {
            Blast blast = new Blast();
            blast.delay = 0.3f + 0.35f * i + (float) Math.random() * 0.2f;
            double angle = Math.random() * Math.PI * 2.0;
            float dist = 150f + (float) Math.random() * 600f;
            blast.loc = new Vector2f(loc.x + (float) Math.cos(angle) * dist, loc.y + (float) Math.sin(angle) * dist);
            blast.power = power;
            blast.source = platform;
            blasts.add(blast);
        }

        if (engine.getCombatUI() != null) {
            Msg.send(engine, owner == 0 ? Msg.PLAYER : Msg.ENEMY, (owner == 0 ? "Your " : "The enemy ") + SupplyFob.NAME
                    + " has been destroyed. Its magazines are cooking off."
                    + (owner == 0 ? " A new one can be set up in " + Math.round(TcoSettings.fobRespawnSeconds)
                            + " seconds if you hold the control array." : ""));
        }
    }

    private void explode(Vector2f loc, float radius, float core, float maxDamage, float minDamage, ShipAPI source) {
        DamagingExplosionSpec spec = new DamagingExplosionSpec(0.3f, radius, core, maxDamage, minDamage,
                CollisionClass.PROJECTILE_FF, CollisionClass.PROJECTILE_FIGHTER,
                6f, 8f, 1.2f, 120, new Color(255, 210, 140), new Color(255, 140, 60));
        spec.setDamageType(DamageType.HIGH_EXPLOSIVE);
        engine.spawnDamagingExplosion(spec, source, loc, false);
    }

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (engine == null || platforms.isEmpty()) return;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        for (ShipAPI platform : platforms.values()) {
            Vector2f loc = platform.getLocation();
            if (!viewport.isNearViewport(loc, 600f) || FogOfWar.isHidden(platform)) continue;
            Object left = platform.getCustomData().get(AmmoRules.SUPPLY_KEY);
            Object max = platform.getCustomData().get(AmmoRules.SUPPLY_MAX_KEY);
            if (!(left instanceof Float) || !(max instanceof Float) || (Float) max <= 0f) continue;
            float fraction = Math.max(0f, Math.min(1f, (Float) left / (Float) max));

            // Stock bar
            float width = 220f;
            float x = loc.x - width / 2f;
            float y = loc.y + platform.getCollisionRadius() + 160f;
            GL11.glColor4f(0.1f, 0.1f, 0.1f, 0.7f);
            quad(x, y, width, 12f);
            if (platform.getOwner() == 0) GL11.glColor4f(0.35f, 0.85f, 1f, 0.9f);
            else GL11.glColor4f(1f, 0.5f, 0.35f, 0.9f);
            quad(x, y, width * fraction, 12f);
        }
        GL11.glPopAttrib();
    }

    private void quad(float x, float y, float width, float height) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + width, y);
        GL11.glVertex2f(x + width, y + height);
        GL11.glVertex2f(x, y + height);
        GL11.glEnd();
    }
}
