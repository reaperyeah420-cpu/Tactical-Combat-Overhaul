package tco.battle;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleCreationPlugin;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;

import tco.TcoSettings;
import tco.dock.RepairDock;
import tco.fob.SupplyFob;
import tco.patches.Patches;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs another battle creation plugin through a coordinate-scaling loader, then fixes its distances and adds
 * the FOB.
 */
public class ScaledBattleCreationPlugin implements BattleCreationPlugin {

    private static final float FOB_CLEARANCE = 2500f;

    private final BattleCreationPlugin delegate;
    private final boolean abyss;

    public ScaledBattleCreationPlugin(BattleCreationPlugin delegate) {
        this(delegate, false);
    }

    public ScaledBattleCreationPlugin(BattleCreationPlugin delegate, boolean abyss) {
        this.delegate = delegate;
        this.abyss = abyss;
    }

    private boolean beaconRoll() {
        float chance = abyss ? TcoSettings.beaconChanceAbyss : TcoSettings.beaconChance;
        return Math.random() < chance;
    }

    @Override
    public void initBattle(BattleCreationContext context, MissionDefinitionAPI loader) {
        TcoSettings.load();
        // Escape battles get a smaller map
        boolean escape = context.getPlayerGoal() == FleetGoal.ESCAPE || context.getOtherGoal() == FleetGoal.ESCAPE;
        float mult = escape ? TcoSettings.escapeMapSizeMult : TcoSettings.mapSizeMult;

        ScalingLoader scaled = new ScalingLoader(loader, mult);
        delegate.initBattle(context, scaled);

        if (scaled.getHeight() > 0f) {
            if (mult != 1f) context.setFlankDeploymentDistance(context.getFlankDeploymentDistance() * mult);
            // Initial deployment distance (vanilla's is fixed regardless of map size)
            if (!escape) {
                float standoff = scaled.getHeight() * TcoSettings.deployStandoffFraction;
                if (context.getStandoffRange() < standoff) context.setStandoffRange(standoff);
                // Each side starts standoff/2 from the centre: keep it clear of the retreat zone at its edge
                float zone = Patches.retreatEdge() ? TcoSettings.retreatEdgeDistance : 2000f;
                float widest = scaled.getHeight() - 2f * (zone + Math.max(0f, TcoSettings.deployEdgeClearance));
                if (widest > 0f && context.getStandoffRange() > widest) context.setStandoffRange(widest);
            }
        }

        List<Vector2f> taken = new ArrayList<Vector2f>();
        if (!escape && TcoSettings.fobEnabled && context.objectivesAllowed && scaled.getWidth() > 0f && beaconRoll()) {
            try {
                addObjective(loader, scaled, taken, SupplyFob.OBJECTIVE_ID, false);
            } catch (RuntimeException e) {
                Global.getLogger(ScaledBattleCreationPlugin.class).warn("TCO: could not add the Supply FOB", e);
            }
        }
        if (!escape && TcoSettings.dockEnabled && context.objectivesAllowed && scaled.getWidth() > 0f && beaconRoll()) {
            try {
                addObjective(loader, scaled, taken, RepairDock.OBJECTIVE_ID, true);
            } catch (RuntimeException e) {
                Global.getLogger(ScaledBattleCreationPlugin.class).warn("TCO: could not add the Repair Dock", e);
            }
        }
    }

    private void addObjective(MissionDefinitionAPI loader, ScalingLoader scaled, List<Vector2f> taken,
            String type, boolean sidesFirst) {
        float w = scaled.getWidth();
        float h = scaled.getHeight();
        float[][] candidates = sidesFirst
                ? new float[][] {{w * 0.22f, 0f}, {-w * 0.22f, 0f}, {w * 0.35f, 0f}, {-w * 0.35f, 0f}, {0f, 0f}}
                : new float[][] {{0f, 0f}, {w * 0.22f, 0f}, {-w * 0.22f, 0f}, {0f, h * 0.2f}, {0f, -h * 0.2f}};
        float[] pick = candidates[0];
        List<Vector2f> others = new ArrayList<Vector2f>(scaled.getObjectives());
        others.addAll(taken);
        for (float[] candidate : candidates) {
            boolean clear = true;
            for (Vector2f objective : others) {
                float dx = objective.x - candidate[0];
                float dy = objective.y - candidate[1];
                if (dx * dx + dy * dy < FOB_CLEARANCE * FOB_CLEARANCE) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                pick = candidate;
                break;
            }
        }
        loader.addObjective(pick[0], pick[1], type);
        taken.add(new Vector2f(pick[0], pick[1]));
    }

    @Override
    public void afterDefinitionLoad(CombatEngineAPI engine) {
        delegate.afterDefinitionLoad(engine);
    }
}
