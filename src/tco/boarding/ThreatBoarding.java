package tco.boarding;

import java.awt.Color;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.impl.combat.threat.FragmentSwarmHullmod;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect;
import com.fs.starfarer.api.impl.combat.threat.RoilingSwarmEffect.RoilingSwarmParams;

import tco.TcoSettings;

/**
 * Threat ships board with the fragments of their swarm (the game's Fragment Swarm hullmod) instead of shuttles:
 * a few invisible craft each carry some fragments taken from the swarm, which grows them back. A fragment fights at
 * threatFragmentStrength of a marine, and the craft go down easily (threatFragmentHull per fragment carried).
 */
final class ThreatBoarding {

    static final String SWARM_HULLMOD = "fragment_swarm";
    // Built into every Threat hull
    static final String THREAT_HULLMOD = "threat_hullmod";

    private ThreatBoarding() {
    }

    static boolean isThreat(ShipAPI ship) {
        return TcoSettings.threatBoarding && ship != null && !ship.isFighter() && ship.getVariant() != null
                && (ship.getVariant().hasHullMod(SWARM_HULLMOD) || ship.getVariant().hasHullMod(THREAT_HULLMOD))
                && RoilingSwarmEffect.getSwarmFor(ship) != null;
    }

    /** Fragments the ship can send now: its swarm, less a reserve for its fragment weapons. */
    static int spare(ShipAPI ship) {
        RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(ship);
        if (swarm == null) return 0;
        int keep = Math.round(FragmentSwarmHullmod.getBaseSwarmSize(ship.getHullSize()) * TcoSettings.threatFragmentReserve);
        return Math.max(0, swarm.getNumActiveMembers() - keep);
    }

    /** A swarm of its own for a boarding craft, filled with fragments from the source's swarm; how many came. */
    static int fill(ShipAPI craft, ShipAPI source, int wanted) {
        RoilingSwarmEffect from = RoilingSwarmEffect.getSwarmFor(source);
        if (from == null) return 0;
        RoilingSwarmParams params = new RoilingSwarmParams();
        params.flockingClass = FragmentSwarmHullmod.STANDARD_SWARM_FLOCKING_CLASS;
        // Not shared with nearby swarms, not grown back: it holds what it was given
        params.memberExchangeClass = null;
        params.withInitialMembers = false;
        params.initialMembers = 0;
        params.withRespawn = false;
        params.baseMembersToMaintain = wanted;
        params.removeMembersAboveMaintainLevel = false;
        params.maxSpeed = craft.getMaxSpeedWithoutBoost() + 150f;
        params.maxOffset = 20f;
        params.flashRateMult = 0.25f;
        params.flashCoreRadiusMult = 0f;
        params.flashRadius = 120f;
        params.flashFringeColor = new Color(255, 0, 0, 40);
        params.flashCoreColor = new Color(255, 255, 255, 127);
        RoilingSwarmEffect swarm = new RoilingSwarmEffect(craft, params);
        int before = from.getNumActiveMembers();
        from.transferMembersTo(swarm, Math.min(wanted, before));
        return swarm.getNumActiveMembers();
    }

    /** A craft back home: its fragments rejoin the source's swarm. */
    static void returnHome(ShipAPI craft, ShipAPI source) {
        RoilingSwarmEffect swarm = RoilingSwarmEffect.getSwarmFor(craft);
        RoilingSwarmEffect home = RoilingSwarmEffect.getSwarmFor(source);
        if (swarm == null || home == null) return;
        swarm.transferMembersTo(home, swarm.getNumActiveMembers());
    }

    /** Craft hull: weak, scaled by the fragments carried; no armour. */
    static void weaken(ShipAPI craft, int fragments) {
        float hull = Math.max(10f, TcoSettings.threatFragmentHull * fragments);
        craft.setMaxHitpoints(hull);
        craft.setHitpoints(hull);
        if (craft.getArmorGrid() == null) return;
        float[][] cells = craft.getArmorGrid().getGrid();
        for (int x = 0; x < cells.length; x++) {
            for (int y = 0; y < cells[x].length; y++) craft.getArmorGrid().setArmorValue(x, y, 0f);
        }
    }
}
