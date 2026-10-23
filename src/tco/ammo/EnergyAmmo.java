package tco.ammo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.ProjectileWeaponSpecAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import tco.TcoSettings;

/**
 * Energy projectile weapons that use TCO's ballistic ammo rules: listed in ammoEnergyWeapons (an id, or a prefix
 * ending in *) or tagged tco_ammo in their weapon_data.csv. They get magazines, resupply and the ballistic power
 * share, and their flux per shot is cut to energyAmmoFluxMult (on the spec, so the engine and the AI see it).
 * Damage, range and armour stay energy.
 */
public final class EnergyAmmo {

    public static final String TAG = "tco_ammo";

    private static final Set<String> marked = new HashSet<String>();
    // Spec flux per shot before the cut, for weapons we changed
    private static final Map<String, Float> baseFlux = new HashMap<String, Float>();

    private EnergyAmmo() {
    }

    public static boolean isMarked(WeaponSpecAPI spec) {
        return spec != null && marked.contains(spec.getWeaponId());
    }

    public static boolean isMarked(WeaponAPI weapon) {
        return weapon != null && isMarked(weapon.getSpec());
    }

    /** Flux per shot as the spreadsheet has it, before TCO's cut. */
    public static float baseFluxPerShot(ProjectileWeaponSpecAPI spec) {
        Float base = baseFlux.get(spec.getWeaponId());
        return base != null ? base : spec.getEnergyPerShot();
    }

    /** Re-reads the list and sets each weapon's flux. Safe to call again after the settings change. */
    public static void apply() {
        marked.clear();
        try {
            for (WeaponSpecAPI spec : Global.getSettings().getAllWeaponSpecs()) {
                if (!(spec instanceof ProjectileWeaponSpecAPI)) continue;
                ProjectileWeaponSpecAPI p = (ProjectileWeaponSpecAPI) spec;
                String id = spec.getWeaponId();
                boolean want = TcoSettings.ammoEnabled && qualifies(spec);
                if (want) marked.add(id);
                Float base = baseFlux.get(id);
                if (want) {
                    if (base == null) {
                        base = p.getEnergyPerShot();
                        baseFlux.put(id, base);
                    }
                    p.setEnergyPerShot(base * TcoSettings.energyAmmoFluxMult);
                } else if (base != null) {
                    p.setEnergyPerShot(base);
                    baseFlux.remove(id);
                }
            }
        } catch (RuntimeException e) {
            Global.getLogger(EnergyAmmo.class).warn("TCO: could not set up ammo for energy weapons", e);
        }
        if (!marked.isEmpty()) Global.getLogger(EnergyAmmo.class).info("TCO: energy weapons on ammo: " + marked);
    }

    private static boolean qualifies(WeaponSpecAPI spec) {
        if (spec.getType() != WeaponType.ENERGY || spec.isBeam()) return false;
        if (spec.getTags() != null && spec.getTags().contains(TAG)) return true;
        String id = spec.getWeaponId().toLowerCase(Locale.ROOT);
        for (String entry : TcoSettings.ammoEnergyWeapons) {
            String e = entry.trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) continue;
            if (e.endsWith("*") ? id.startsWith(e.substring(0, e.length() - 1)) : id.equals(e)) return true;
        }
        return false;
    }
}
