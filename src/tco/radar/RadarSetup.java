package tco.radar;

import java.util.HashSet;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.loading.HullModSpecAPI;
import com.fs.starfarer.api.loading.MissileSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import tco.TcoSettings;
import tco.missile.Guidance;

/**
 * At load: radar missiles get radarMissileRangeMult range and flight time, and vanilla's ECM/ECCM
 * descriptions are updated. Needs a restart to change.
 */
public final class RadarSetup {

    private static boolean ranges;
    private static boolean texts;

    private RadarSetup() {
    }

    public static void apply() {
        if (!TcoSettings.radarEnabled) return;
        if (!ranges) {
            ranges = true;
            float mult = TcoSettings.radarMissileRangeMult;
            if (mult > 0f && mult != 1f) {
                Set<Object> stretched = new HashSet<Object>();
                for (WeaponSpecAPI spec : Global.getSettings().getAllWeaponSpecs()) {
                    try {
                        if (!Guidance.firesMissiles(spec) || Guidance.of(spec) != Guidance.Kind.RADAR) continue;
                        Guidance.rememberBaseRange(spec);
                        spec.setMaxRange(spec.getMaxRange() * mult);
                        MissileSpecAPI missile = (MissileSpecAPI) spec.getProjectileSpec();
                        if (stretched.add(missile)) missile.setMaxFlightTime(missile.getMaxFlightTime() * mult);
                    } catch (RuntimeException e) {
                        Global.getLogger(RadarSetup.class).warn("TCO: could not extend radar missile " + spec.getWeaponId(), e);
                    }
                }
            }
        }
        if (!texts) {
            texts = true;
            append("ecm", "TCO: also jams incoming radar missiles, " + TcoSettings.ecmFrigate + "/" + TcoSettings.ecmDestroyer
                    + "/" + TcoSettings.ecmCruiser + "/" + TcoSettings.ecmCapital
                    + " at a time by hull size, drawing reactor power for each. Jammed missiles may be spoofed and lose their"
                    + " target; small ships are easier to protect than big ones.");
            append("eccm", "TCO: radar missiles fired by this ship are " + Math.round((1f - TcoSettings.eccmSpoofMult) * 100f)
                    + " percent less likely to be spoofed by enemy ECM jamming.");
        }
    }

    private static void append(String id, String text) {
        try {
            HullModSpecAPI spec = Global.getSettings().getHullModSpec(id);
            if (spec == null) return;
            String format = spec.getDescriptionFormat();
            if (format != null && format.contains("TCO:")) return;
            spec.setDescriptionFormat((format == null ? "" : format + "\n\n") + text);
        } catch (RuntimeException e) {
            Global.getLogger(RadarSetup.class).warn("TCO: could not add TCO's note to hullmod " + id, e);
        }
    }
}
