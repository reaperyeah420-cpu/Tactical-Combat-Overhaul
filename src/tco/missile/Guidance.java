package tco.missile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import tco.TcoSettings;

/**
 * Sorts missile launchers from any mod into IR, radar or unguided: non-steering missiles are unguided,
 * declared heat-seekers are IR, and the rest go by base range (IR up to irMaxRange, radar beyond).
 * data/config/tco_missile_guidance.csv overrides single launchers.
 */
public final class Guidance {

    public enum Kind {
        NONE("Unguided"), IR("IR"), IR_ENGINE("IR, engine-seeker"), RADAR("Radar");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public static final String OVERRIDES = "data/config/tco_missile_guidance.csv";
    private static final Set<String> UNGUIDED = new HashSet<String>(Arrays.asList("ROCKET", "BOMB", "BOMB_WITH_SLOW",
            "BOMB_WITH_ACCEL", "PHASE_CHARGE", "PHASE_MINE", "NO_AI", "FLARE", "FLARE_SEEKER", "FLARE_JAMMER", "MOTE"));

    private static final Map<String, Kind> cache = new HashMap<String, Kind>();
    private static final Map<String, Float> baseRanges = new HashMap<String, Float>();
    private static Map<String, Kind> overrides;

    private Guidance() {
    }

    public static boolean firesMissiles(WeaponSpecAPI spec) {
        return spec != null && spec.getProjectileSpec() instanceof MissileSpecAPI;
    }

    /** NONE for anything not firing guided missiles. */
    public static Kind of(WeaponSpecAPI spec) {
        if (spec == null) return Kind.NONE;
        Kind kind = cache.get(spec.getWeaponId());
        if (kind == null) {
            kind = classify(spec);
            cache.put(spec.getWeaponId(), kind);
        }
        return kind;
    }

    public static void rememberBaseRange(WeaponSpecAPI spec) {
        if (!baseRanges.containsKey(spec.getWeaponId())) baseRanges.put(spec.getWeaponId(), spec.getMaxRange());
    }

    public static float baseRange(WeaponSpecAPI spec) {
        Float base = baseRanges.get(spec.getWeaponId());
        return base != null ? base : spec.getMaxRange();
    }

    /** Forget the sorting (settings changed). */
    public static void reset() {
        cache.clear();
    }

    private static Kind classify(WeaponSpecAPI spec) {
        Kind forced = overrides().get(spec.getWeaponId());
        if (forced != null) return forced;
        if (!firesMissiles(spec)) return Kind.NONE;
        MissileSpecAPI missile = (MissileSpecAPI) spec.getProjectileSpec();
        String type = missile.getTypeString();
        if (type != null && UNGUIDED.contains(type)) return Kind.NONE;
        ShipHullSpecAPI hull = missile.getHullSpec();
        if (hull != null && hull.getEngineSpec() != null && hull.getEngineSpec().getMaxTurnRate() <= 1f) return Kind.NONE;
        if ("HEATSEEKER".equals(type) || spec.getAIHints() != null && spec.getAIHints().contains(AIHints.HEATSEEKER)) {
            return Kind.IR_ENGINE;
        }
        return baseRange(spec) <= TcoSettings.irMaxRange ? Kind.IR : Kind.RADAR;
    }

    private static Map<String, Kind> overrides() {
        if (overrides != null) return overrides;
        overrides = new HashMap<String, Kind>();
        try {
            JSONArray rows = Global.getSettings().getMergedSpreadsheetData("id", OVERRIDES);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String id = row.optString("id", "").trim();
                String value = row.optString("guidance", "").trim().toUpperCase();
                if (id.isEmpty() || id.startsWith("#")) continue;
                if (value.equals("IR")) overrides.put(id, Kind.IR);
                else if (value.equals("RADAR")) overrides.put(id, Kind.RADAR);
                else if (value.equals("NONE") || value.equals("UNGUIDED")) overrides.put(id, Kind.NONE);
                else if (value.equals("IR_ENGINE")) overrides.put(id, Kind.IR_ENGINE);
                else Global.getLogger(Guidance.class).warn("TCO: unknown guidance '" + value + "' for " + id + " in " + OVERRIDES);
            }
        } catch (Exception e) {
            Global.getLogger(Guidance.class).warn("TCO: could not read " + OVERRIDES, e);
        }
        return overrides;
    }
}
