package tco.rts;

import java.util.HashMap;
import java.util.Map;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SectorAPI;

import tco.TcoSettings;

/**
 * Idle behaviour (also in formation and on attack-move): Aggressive chases what it sees, Defensive
 * fights near its spot, Hold ground never moves for an enemy, Evasive backs out of enemy reach,
 * Rescue collects its side's escape pods. Saved per ship; battle changes last one battle unless saved.
 */
public enum Stance {
    AGGRESSIVE("Aggressive"),
    DEFENSIVE("Defensive"),
    HOLD("Hold ground"),
    EVASIVE("Evasive"),
    RESCUE("Rescue");

    public final String label;

    Stance(String label) {
        this.label = label;
    }

    public Stance next() {
        return values()[(ordinal() + 1) % values().length];
    }

    private static final String KEY = "tco_stances";

    public static Stance fromName(String name, Stance fallback) {
        if (name == null) return fallback;
        for (Stance stance : values()) {
            if (stance.name().equalsIgnoreCase(name.trim())) return stance;
        }
        return fallback;
    }

    public static Stance byDefault() {
        return fromName(TcoSettings.defaultStance, DEFENSIVE);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> store(boolean create) {
        SectorAPI sector = Global.getSector();
        if (sector == null) return null;
        Object stored = sector.getPersistentData().get(KEY);
        if (stored instanceof Map) return (Map<String, String>) stored;
        if (!create) return null;
        Map<String, String> map = new HashMap<String, String>();
        sector.getPersistentData().put(KEY, map);
        return map;
    }

    /** Saved stance, or the default. */
    public static Stance load(String memberId) {
        try {
            Map<String, String> map = store(false);
            if (map == null || memberId == null) return byDefault();
            return fromName(map.get(memberId), byDefault());
        } catch (RuntimeException e) {
            return byDefault();
        }
    }

    /** False with no campaign. */
    public static boolean save(String memberId, Stance stance) {
        try {
            Map<String, String> map = store(true);
            if (map == null || memberId == null) return false;
            map.put(memberId, stance.name());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
