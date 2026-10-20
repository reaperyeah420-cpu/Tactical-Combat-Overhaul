package tco;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.DamageType;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.WeaponAPI.DerivedWeaponStatsAPI;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponSize;
import com.fs.starfarer.api.combat.WeaponAPI.WeaponType;
import com.fs.starfarer.api.loading.WeaponSpecAPI;

import tco.ammo.AmmoRules;
import tco.armor.ArmorListener;
import tco.missile.Guidance;

/**
 * Writes TCO's numbers (penetration, magazine, guidance, range, power) into every weapon's stat card.
 * Base values by default; apply(MutableShipStatsAPI) writes them for one ship.
 */
public final class WeaponCards {

    private static final Map<String, String[]> originals = new HashMap<String, String[]>();
    private static Map<String, Float> speeds;
    private static boolean warned;

    /** TCO's numbers for one weapon. Negative: not applicable. */
    public static final class Numbers {
        public float damage;
        public boolean kinetic;
        public float penetration = -1f;
        public int rounds = -1;
        public boolean reserve;
        public float seconds = -1f;
        public float range = -1f;
        public Guidance.Kind guidance;
        public float powerShot = -1f;
        public float powerPerSecond = -1f;
        public boolean beam;
        public float shipPower = -1f;
        public float shipRegen = -1f;

        public float reliable() {
            // Certain at 1.2x armor, even odds at 1x
            return penetration / 1.2f;
        }
    }

    private WeaponCards() {
    }

    public static void apply() {
        apply(null);
    }

    /** Every card for this ship's stats (null: base values). */
    public static void apply(MutableShipStatsAPI ship) {
        Guidance.reset();
        for (WeaponSpecAPI spec : Global.getSettings().getAllWeaponSpecs()) {
            try {
                applyTo(spec, ship);
            } catch (Throwable e) {
                if (!warned) {
                    warned = true;
                    Global.getLogger(WeaponCards.class).warn("TCO: could not add TCO stats to weapon " + spec.getWeaponId(), e);
                }
            }
        }
    }

    private static boolean isPd(WeaponSpecAPI spec) {
        return spec.getAIHints() != null && spec.getAIHints().contains(AIHints.PD);
    }

    public static Numbers numbers(WeaponSpecAPI spec, MutableShipStatsAPI ship) {
        Numbers n = new Numbers();
        WeaponType type = spec.getType();
        DerivedWeaponStatsAPI stats = spec.getDerivedStats();
        float baseDamage = stats == null ? 0f : stats.getDamagePerShot();
        n.damage = baseDamage;
        if (ship != null) {
            if (type == WeaponType.BALLISTIC) n.damage *= ship.getBallisticWeaponDamageMult().getModifiedValue();
            else if (type == WeaponType.ENERGY) n.damage *= ship.getEnergyWeaponDamageMult().getModifiedValue();
            else if (type == WeaponType.MISSILE) n.damage *= ship.getMissileWeaponDamageMult().getModifiedValue();
        }
        boolean ballisticShell = type == WeaponType.BALLISTIC && !spec.isBeam();

        // Penetration
        DamageType damageType = spec.getDamageType();
        n.kinetic = damageType == DamageType.KINETIC;
        Float speed = speeds().get(spec.getWeaponId());
        if (TcoSettings.armorEnabled && ballisticShell && (n.kinetic || damageType == DamageType.HIGH_EXPLOSIVE)
                && speed != null && n.damage > 0f) {
            float shellSpeed = speed;
            if (ship != null) {
                shellSpeed *= ship.getProjectileSpeedMult().getModifiedValue() * ship.getBallisticProjectileSpeedMult().getModifiedValue();
            }
            n.penetration = ArmorListener.penetrationPower(n.damage, shellSpeed, n.kinetic);
        }

        // Ammunition
        if (TcoSettings.ammoEnabled && ballisticShell && stats != null && baseDamage > 0f) {
            float pd = AmmoRules.stockMult(isPd(spec), spec.getSize());
            float rof = ship == null ? 1f : ship.getBallisticRoFMult().getModifiedValue();
            if (spec.usesAmmo() && spec.getAmmoPerSecond() > 0f) {
                float perSecond = stats.getSustainedDps() > 0f ? stats.getSustainedDps() / baseDamage : spec.getAmmoPerSecond();
                float shots = perSecond * TcoSettings.ammoSeconds * pd;
                if (ship != null) shots = ship.getBallisticAmmoBonus().computeEffective(shots);
                n.rounds = Math.max(Math.max(TcoSettings.minAmmo, spec.getMaxAmmo() * 2), Math.round(shots));
                n.reserve = true;
                if (perSecond > 0f) n.seconds = n.rounds / (perSecond * rof);
            } else if (!spec.usesAmmo() && stats.getDps() > 0f) {
                float perSecond = stats.getDps() / baseDamage;
                float shots = perSecond * TcoSettings.ammoSeconds * pd;
                if (ship != null) shots = ship.getBallisticAmmoBonus().computeEffective(shots);
                n.rounds = Math.max(TcoSettings.minAmmo, Math.round(shots));
                n.seconds = n.rounds / (perSecond * rof);
            }
        }

        // Range
        if (TcoSettings.weaponRangeMult != 1f && !isPd(spec) && !spec.hasTag("fixed_range")
                && (type == WeaponType.BALLISTIC || type == WeaponType.ENERGY || type == WeaponType.MISSILE)
                && spec.getMaxRange() > 0f) {
            float range = spec.getMaxRange();
            if (ship != null) {
                if (spec.isBeam()) range = ship.getBeamWeaponRangeBonus().computeEffective(range);
                if (type == WeaponType.BALLISTIC) range = ship.getBallisticWeaponRangeBonus().computeEffective(range);
                else if (type == WeaponType.ENERGY) range = ship.getEnergyWeaponRangeBonus().computeEffective(range);
                else if (type == WeaponType.MISSILE) range = ship.getMissileWeaponRangeBonus().computeEffective(range);
            }
            n.range = range * TcoSettings.weaponRangeMult;
        }
        if (Guidance.firesMissiles(spec)) n.guidance = Guidance.of(spec);
        if (TcoSettings.powerEnabled) power(n, spec, ship);
        return n;
    }

    private static void power(Numbers n, WeaponSpecAPI spec, MutableShipStatsAPI ship) {
        WeaponType type = spec.getType();
        DerivedWeaponStatsAPI stats = spec.getDerivedStats();
        if (stats == null || (type != WeaponType.BALLISTIC && type != WeaponType.ENERGY && type != WeaponType.MISSILE)) return;
        if (ship != null) {
            n.shipPower = Math.max(1f, ship.getFluxCapacity().getModifiedValue() * TcoSettings.powerCapacityMult);
            n.shipRegen = ship.getFluxDissipation().getModifiedValue() * TcoSettings.powerRegenMult;
        }
        if (spec.isBeam()) {
            float perSecond = stats.getFluxPerSecond();
            if (ship != null) perSecond *= ship.getBeamWeaponFluxCostMult().getModifiedValue();
            perSecond *= type == WeaponType.ENERGY ? TcoSettings.powerEnergyMult : TcoSettings.powerKineticBeamMult;
            if (perSecond <= 0f) return;
            n.beam = true;
            n.powerShot = perSecond;
            n.powerPerSecond = perSecond;
            return;
        }
        float flux = stats.getFluxPerDam() * stats.getDamagePerShot();
        float perSecondFlux = stats.getSustainedFluxPerSecond();
        float mult = 0f;
        if (type == WeaponType.ENERGY) {
            mult = TcoSettings.powerEnergyMult;
            if (ship != null) {
                flux = ship.getEnergyWeaponFluxCostMod().computeEffective(flux);
                perSecondFlux = ship.getEnergyWeaponFluxCostMod().computeEffective(perSecondFlux);
            }
        } else if (type == WeaponType.BALLISTIC && flux > 0f) {
            // Ballistic flux is cut when ammo is on; power is a share of the rest
            mult = TcoSettings.powerBallisticMult * (TcoSettings.ammoEnabled ? TcoSettings.ballisticFluxMult : 1f);
            if (ship != null) {
                flux = ship.getBallisticWeaponFluxCostMod().computeEffective(flux);
                perSecondFlux = ship.getBallisticWeaponFluxCostMod().computeEffective(perSecondFlux);
            }
        }
        if (mult > 0f) {
            n.powerShot = flux * mult;
            n.powerPerSecond = perSecondFlux * mult;
        } else {
            // Missiles and fluxless ballistics: flat cost by mount size
            WeaponSize size = spec.getSize();
            n.powerShot = size == WeaponSize.LARGE ? TcoSettings.powerShotLarge
                    : size == WeaponSize.MEDIUM ? TcoSettings.powerShotMedium : TcoSettings.powerShotSmall;
            float damage = stats.getDamagePerShot();
            n.powerPerSecond = damage > 0f && stats.getSustainedDps() > 0f ? n.powerShot * stats.getSustainedDps() / damage : -1f;
        }
    }

    private static String pw(float v) {
        return v < 10f ? String.valueOf(Math.round(v * 10f) / 10f) : String.valueOf(Math.round(v));
    }

    /** Typical armor class for this thickness. */
    public static String armorClass(float armor) {
        if (armor >= 2000f) return "almost anything";
        if (armor >= 1500f) return "heavy cruisers and capitals";
        if (armor >= 1100f) return "cruisers";
        if (armor >= 800f) return "light cruisers";
        if (armor >= 450f) return "destroyers";
        if (armor >= 200f) return "frigates";
        return "only the lightest frigates";
    }

    public static boolean changed(float now, float base) {
        return Math.abs(now - base) >= 0.5f;
    }

    private static String value(float now, float base, boolean ship, String unit) {
        String text = Math.round(now) + unit;
        if (!ship || !changed(now, base) || base <= 0f) return text;
        int percent = Math.round((now / base - 1f) * 100f);
        return text + " (" + (percent > 0 ? "+" : "") + percent + "%)";
    }

    private static void applyTo(WeaponSpecAPI spec, MutableShipStatsAPI ship) {
        String id = spec.getWeaponId();
        String[] original = originals.get(id);
        if (original == null) {
            original = new String[] {spec.getCustomAncillary(), spec.getCustomAncillaryHL()};
            originals.put(id, original);
        }
        Numbers n = numbers(spec, ship);
        Numbers base = ship == null ? n : numbers(spec, null);
        boolean adjusted = ship != null;
        List<String> lines = new ArrayList<String>();
        List<String> values = new ArrayList<String>();

        if (n.penetration > 0f) {
            lines.add("Armor penetration %s: reliably pierces %s armor (%s), half the time %s."
                    + (n.kinetic ? "" : " High explosive strips armor rather than piercing it.")
                    + " Sloped armor counts as thicker.");
            values.add(value(n.penetration, base.penetration, adjusted, ""));
            values.add(value(n.reliable(), base.reliable(), adjusted, ""));
            values.add(armorClass(n.reliable()));
            values.add(value(n.penetration, base.penetration, adjusted, ""));
        }
        if (n.rounds > 0) {
            if (n.reserve) {
                lines.add("Reserve %s rounds for its reloads, %s of fire. Refilled by supply ships.");
            } else {
                lines.add("Magazine %s rounds, %s of fire. Refilled by supply ships.");
            }
            values.add(value(n.rounds, base.rounds, adjusted, ""));
            values.add(value(n.seconds, base.seconds, adjusted, " s"));
        }
        if (n.guidance != null) {
            lines.add("Guidance %s.");
            values.add(n.guidance.label);
        }
        if (n.range > 0f) {
            lines.add("Range in battle %s.");
            values.add(value(n.range, base.range, adjusted, ""));
        }
        if (n.powerShot > 0f) {
            String onShip = n.shipPower > 0f ? " (this ship: %s power, %s/s regeneration)" : "";
            if (n.beam) {
                lines.add("Power %s a second while firing" + onShip + ".");
                values.add(pw(n.powerShot));
            } else if (n.powerPerSecond > 0f) {
                lines.add("Power %s a shot, %s a second of sustained fire" + onShip + ".");
                values.add(pw(n.powerShot));
                values.add(pw(n.powerPerSecond));
            } else {
                lines.add("Power %s a shot" + onShip + ".");
                values.add(pw(n.powerShot));
            }
            if (n.shipPower > 0f) {
                values.add(String.valueOf(Math.round(n.shipPower)));
                values.add(pw(n.shipRegen));
            }
        }

        String text = original[0] == null ? "" : original[0];
        String highlights = original[1] == null ? "" : original[1];
        // With highlights the game String.formats the text, so escape a weapon's own bare %
        if (!lines.isEmpty() && highlights.trim().isEmpty()) text = text.replace("%", "%%");
        if (!lines.isEmpty()) {
            StringBuilder ours = new StringBuilder(adjusted ? "TCO, for this ship:" : "TCO:");
            for (String line : lines) ours.append("\n").append(line);
            StringBuilder ourValues = new StringBuilder();
            for (String v : values) {
                if (ourValues.length() > 0) ourValues.append(" | ");
                ourValues.append(v);
            }
            text = text.trim().isEmpty() ? ours.toString() : text + "\n\n" + ours;
            highlights = highlights.trim().isEmpty() ? ourValues.toString() : highlights + " | " + ourValues;
        }
        spec.setCustomAncillary(text.isEmpty() ? null : text);
        spec.setCustomAncillaryHL(highlights.isEmpty() ? null : highlights);
    }

    private static Map<String, Float> speeds() {
        if (speeds != null) return speeds;
        speeds = new HashMap<String, Float>();
        try {
            JSONArray rows = Global.getSettings().getMergedSpreadsheetData("id", "data/weapons/weapon_data.csv");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String id = row.optString("id", "");
                String raw = row.optString("proj speed", "");
                if (id.isEmpty() || raw.trim().isEmpty()) continue;
                try {
                    speeds.put(id, Float.parseFloat(raw.trim()));
                } catch (NumberFormatException e) {
                }
            }
        } catch (Exception e) {
            Global.getLogger(WeaponCards.class).warn("TCO: could not read weapon_data.csv for projectile speeds", e);
        }
        return speeds;
    }
}
