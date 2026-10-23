package tco.boarding;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import tco.TcoSettings;
import tco.hullmods.SecurityForge;

/**
 * Security droids left on the player's Security Forge ships between battles. The forge rebuilds lost droids
 * over time: a full complement in securityRebuildDays (by hull size). Saved as a plain string map
 * (member id -> "droids,timestamp") so saves load without TCO.
 */
public final class SecurityDroids {

    private static final String DATA_KEY = "tco_security_droids";

    private SecurityDroids() {
    }

    public static int full(FleetMemberAPI member) {
        return SecurityForge.droidsFor(member.getHullSpec().getHullSize());
    }

    private static float rebuildDays(HullSize size) {
        if (size == HullSize.CAPITAL_SHIP) return TcoSettings.securityRebuildDaysCapital;
        if (size == HullSize.CRUISER) return TcoSettings.securityRebuildDaysCruiser;
        if (size == HullSize.DESTROYER) return TcoSettings.securityRebuildDaysDestroyer;
        return TcoSettings.securityRebuildDaysFrigate;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> saved() {
        Map<String, Object> data = Global.getSector().getPersistentData();
        Object existing = data.get(DATA_KEY);
        if (existing instanceof Map) return (Map<String, String>) existing;
        Map<String, String> created = new HashMap<String, String>();
        data.put(DATA_KEY, created);
        return created;
    }

    /** Droids aboard now, counting the rebuild since the last battle. */
    public static float current(FleetMemberAPI member) {
        int full = full(member);
        try {
            if (Global.getSector() == null) return full;
            String text = saved().get(member.getId());
            if (text == null) return full;
            String[] parts = text.split(",");
            float left = Float.parseFloat(parts[0].trim());
            long since = Long.parseLong(parts[1].trim());
            CampaignClockAPI clock = Global.getSector().getClock();
            float days = Math.max(0f, clock.getElapsedDaysSince(since));
            float perDay = full / Math.max(0.01f, rebuildDays(member.getHullSpec().getHullSize()));
            float now = Math.min(full, left + perDay * days);
            if (now >= full) saved().remove(member.getId());
            return now;
        } catch (RuntimeException e) {
            return full;
        }
    }

    /** Records the droids left after a battle; the rebuild runs from now. */
    public static void store(FleetMemberAPI member, float left) {
        try {
            if (Global.getSector() == null) return;
            if (left >= full(member)) {
                saved().remove(member.getId());
                return;
            }
            saved().put(member.getId(), Math.max(0f, left) + "," + Global.getSector().getClock().getTimestamp());
        } catch (RuntimeException e) {
            Global.getLogger(SecurityDroids.class).warn("TCO: could not save security droids", e);
        }
    }
}
