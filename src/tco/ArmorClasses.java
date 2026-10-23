package tco;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipHullSpecAPI;
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints;

/**
 * The armor classes on weapon cards ("reliably pierces ... armor (destroyers)"), worked out once from every loaded
 * hull, mods included, so they fit the modlist being played. Each class is a statistic of its hull size's armor
 * ratings; with vanilla hulls they come out about 250 / 480 / 900 / 1080 / 1540 / 1750 (the old fixed ones were\n * 200 / 450 / 800 / 1100 / 1500 / 2000).
 */
public final class ArmorClasses {

    private static final String[] NAMES = {"only the lightest frigates", "frigates", "destroyers", "light cruisers",
            "cruisers", "heavy cruisers and capitals", "almost anything"};
    // The old fixed thresholds, used for a hull size with too few hulls to go by
    private static final float[] FALLBACK = {200f, 450f, 800f, 1100f, 1500f, 2000f};
    private static final int MIN_HULLS = 3;
    // Below this it reliably pierces almost nothing (fixed, user's choice)
    private static final float ALMOST_NOTHING = 100f;
    // A hull with more than this times its size's median armor is a freak, left out of the top class
    private static final float OUTLIER = 2f;

    private static float[] thresholds;

    private ArmorClasses() {
    }

    /** The class an armor value reliably pierces. */
    public static String of(float armor) {
        // Fixed, below every calculated class
        if (armor < ALMOST_NOTHING) return "almost nothing";
        float[] t = thresholds();
        for (int i = t.length - 1; i >= 0; i--) {
            if (armor >= t[i]) return NAMES[i + 1];
        }
        return NAMES[0];
    }

    private static float[] thresholds() {
        if (thresholds != null) return thresholds;
        float[] t = FALLBACK.clone();
        try {
            List<Float> frigates = new ArrayList<Float>(), destroyers = new ArrayList<Float>();
            List<Float> cruisers = new ArrayList<Float>(), capitals = new ArrayList<Float>(), all = new ArrayList<Float>();
            Set<String> seen = new HashSet<String>();
            for (ShipHullSpecAPI hull : Global.getSettings().getAllShipHullSpecs()) {
                if (hull == null || hull.getHints() == null) continue;
                if (hull.getHints().contains(ShipTypeHints.STATION) || hull.getHints().contains(ShipTypeHints.MODULE)
                        || hull.getHints().contains(ShipTypeHints.HIDE_IN_CODEX)) continue;
                if (hull.isDHull()) continue;
                // Skins share their base hull's armor: count each base hull once
                String base = hull.getBaseHullId() != null ? hull.getBaseHullId() : hull.getHullId();
                if (!seen.add(base)) continue;
                float armor = hull.getArmorRating();
                if (armor <= 0f) continue;
                HullSize size = hull.getHullSize();
                if (size == HullSize.FRIGATE) frigates.add(armor);
                else if (size == HullSize.DESTROYER) destroyers.add(armor);
                else if (size == HullSize.CRUISER) cruisers.add(armor);
                else if (size == HullSize.CAPITAL_SHIP) capitals.add(armor);
                else continue;
                all.add(armor);
            }
            if (frigates.size() >= MIN_HULLS) t[0] = percentile(frigates, 0.5f);
            if (destroyers.size() >= MIN_HULLS) t[1] = (percentile(destroyers, 0.5f) + percentile(destroyers, 0.75f)) / 2f;
            if (cruisers.size() >= MIN_HULLS) t[2] = percentile(cruisers, 0.5f);
            if (cruisers.size() >= MIN_HULLS) t[3] = (percentile(cruisers, 0.5f) + percentile(cruisers, 0.75f)) / 2f;
            if (capitals.size() >= MIN_HULLS) t[4] = (percentile(capitals, 0.5f) + percentile(capitals, 0.75f)) / 2f;
            // The heaviest hull that isn't a freak (no more than twice its own size's median armor), so it really
            // pierces every ordinary ship
            if (all.size() >= MIN_HULLS * 4) {
                float heaviest = 0f;
                for (List<Float> size : java.util.Arrays.asList(frigates, destroyers, cruisers, capitals)) {
                    if (size.size() < MIN_HULLS) continue;
                    float limit = percentile(size, 0.5f) * OUTLIER;
                    for (float a : size) if (a <= limit) heaviest = Math.max(heaviest, a);
                }
                if (heaviest > 0f) t[5] = heaviest;
            }
            // Always increasing, at least 50 apart, starting above the fixed "almost nothing" class
            t[0] = Math.max(t[0], ALMOST_NOTHING + 50f);
            for (int i = 1; i < t.length; i++) t[i] = Math.max(t[i], t[i - 1] + 50f);
            for (int i = 0; i < t.length; i++) t[i] = Math.round(t[i] / 10f) * 10f;
            Global.getLogger(ArmorClasses.class).info("TCO: armor classes from " + all.size() + " hulls (" + frigates.size()
                    + " frigates, " + destroyers.size() + " destroyers, " + cruisers.size() + " cruisers, " + capitals.size()
                    + " capitals): " + java.util.Arrays.toString(t));
        } catch (RuntimeException e) {
            Global.getLogger(ArmorClasses.class).warn("TCO: could not work out armor classes from the hulls; using the fixed ones", e);
            t = FALLBACK.clone();
        }
        thresholds = t;
        return t;
    }

    // Linear interpolation between the closest ranks
    private static float percentile(List<Float> values, float p) {
        List<Float> v = new ArrayList<Float>(values);
        Collections.sort(v);
        float rank = p * (v.size() - 1);
        int lo = (int) Math.floor(rank), hi = (int) Math.ceil(rank);
        return v.get(lo) + (v.get(hi) - v.get(lo)) * (rank - lo);
    }
}
