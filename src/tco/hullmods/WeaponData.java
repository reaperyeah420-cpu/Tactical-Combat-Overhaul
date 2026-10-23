package tco.hullmods;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShieldAPI.ShieldType;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.ui.Alignment;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import tco.TcoSettings;
import tco.WeaponCards;
import tco.WeaponCards.Numbers;
import tco.missile.Guidance;
import tco.power.PowerCombatPlugin;

/**
 * "TCO - Ship and Weapon Data": no combat effect; the description shows the ship's reactor power and TCO's numbers for every weapon on the ship,
 * with ship changes in green.
 */
public class WeaponData extends BaseHullMod {

    @Override
    public void addPostDescriptionSection(TooltipMakerAPI tooltip, HullSize hullSize, ShipAPI ship, float width,
            boolean isForModSpec) {
        TcoSettings.load();
        Color gray = Misc.getGrayColor();
        if (ship == null || isForModSpec) {
            tooltip.addPara("Fit it to a ship to see its weapons' numbers.", gray, 10f);
            return;
        }
        if (TcoSettings.powerEnabled) addPower(tooltip, ship);
        Map<String, WeaponSpecAPI> specs = new LinkedHashMap<String, WeaponSpecAPI>();
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.isDecorative() || weapon.getSpec() == null) continue;
            String id = weapon.getSpec().getWeaponId();
            specs.put(id, weapon.getSpec());
            counts.put(id, (counts.containsKey(id) ? counts.get(id) : 0) + 1);
        }
        if (specs.isEmpty()) {
            tooltip.addPara("No weapons fitted.", gray, 10f);
            return;
        }
        MutableShipStatsAPI stats = ship.getMutableStats();
        tooltip.addSectionHeading("Weapons on this ship", Alignment.MID, 10f);
        for (Map.Entry<String, WeaponSpecAPI> entry : specs.entrySet()) {
            WeaponSpecAPI spec = entry.getValue();
            Numbers n = WeaponCards.numbers(spec, stats);
            Numbers base = WeaponCards.numbers(spec, null);
            int count = counts.get(entry.getKey());

            Line line = new Line();
            line.text.append("%s");
            line.add(spec.getWeaponName() + (count > 1 ? " x" + count : ""), Misc.getTextColor());
            line.text.append(": %s damage");
            line.number(n.damage, base.damage, "");
            if (n.penetration > 0f) {
                line.text.append(", penetration %s (reliably %s: %s)");
                line.number(n.penetration, base.penetration, "");
                line.number(n.reliable(), base.reliable(), "");
                String category = WeaponCards.armorClass(n.reliable());
                line.add(category, category.equals(WeaponCards.armorClass(base.reliable()))
                        ? Misc.getHighlightColor() : Misc.getPositiveHighlightColor());
            }
            if (n.rounds > 0) {
                line.text.append(n.reserve ? ", reserve %s rounds (%s)" : ", magazine %s rounds (%s)");
                line.number(n.rounds, base.rounds, "");
                line.number(n.seconds, base.seconds, " s");
            }
            if (n.guidance != null) {
                boolean unguided = n.guidance == Guidance.Kind.NONE;
                line.text.append(unguided ? ", %s" : ", %s guidance");
                line.add(unguided ? "unguided" : n.guidance == Guidance.Kind.RADAR ? "radar" : n.guidance.label,
                        Misc.getHighlightColor());
            }
            if (n.range > 0f) {
                line.text.append(", range %s");
                line.number(n.range, base.range, "");
            }
            line.text.append(".");
            tooltip.addPara(line.text.toString(), 6f, line.colors.toArray(new Color[0]), line.values.toArray(new String[0]));
        }
        tooltip.addPara("Green: changed by this ship's hullmods or skills. Penetration is for head-on hits.", gray, 8f);
    }

    /**
     * Reactor power: capacity, regen (and while venting), what all the weapons draw firing non-stop, and how long
     * that lasts. Values the power hullmods change are green.
     */
    private static void addPower(TooltipMakerAPI tooltip, ShipAPI ship) {
        MutableShipStatsAPI stats = ship.getMutableStats();
        float capacity = PowerCombatPlugin.capacityFor(stats, ship.getVariant());
        float regen = PowerCombatPlugin.regenFor(stats, ship.getVariant());
        float drain = 0f;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.isDecorative() || weapon.getSpec() == null) continue;
            Numbers n = WeaponCards.numbers(weapon.getSpec(), stats);
            if (n.powerPerSecond > 0f) drain += n.powerPerSecond;
        }
        Color h = Misc.getHighlightColor();
        Color green = Misc.getPositiveHighlightColor();
        Color gray = Misc.getGrayColor();
        tooltip.addSectionHeading("Reactor power", Alignment.MID, 10f);
        tooltip.addPara("Capacity %s, regenerates %s per second (%s while venting).", 6f,
                new Color[] {SecondaryEnergyBanks.has(ship.getVariant()) ? green : h,
                        AuxiliaryGenerator.has(ship.getVariant()) ? green : h, h},
                Math.round(capacity) + "", Math.round(regen) + "",
                Math.round(regen * (1f + TcoSettings.powerVentRegenBonus)) + "");
        if (drain <= 0f) {
            tooltip.addPara("Its weapons draw no power.", 4f);
        } else if (drain <= regen) {
            tooltip.addPara("All weapons firing draw %s per second: the reactor keeps up.", 4f, green,
                    Math.round(drain) + "");
        } else {
            float seconds = capacity / (drain - regen);
            tooltip.addPara("All weapons firing draw %s per second: a full reactor runs dry in %s.", 4f,
                    new Color[] {h, Misc.getNegativeHighlightColor()}, Math.round(drain) + "",
                    (seconds < 60f ? Math.round(seconds) + " s" : Math.round(seconds / 6f) / 10f + " min"));
        }
        ShieldAPI shield = ship.getShield();
        if (shield != null && shield.getType() != ShieldType.NONE && shield.getType() != ShieldType.PHASE) {
            tooltip.addPara("Shield hits draw %s power per point of flux. Below %s power the shield weakens and lets "
                    + "kinetic shots through (up to %s when empty).", 4f, h,
                    Math.round(PowerCombatPlugin.shieldPowerMult(ship.getVariant()) * 100f) / 100f + "", Math.round(TcoSettings.shieldWeakStart * 100f) + "%",
                    Math.round(TcoSettings.shieldPierceMax * 100f
                            * (ship.getVariant().hasHullMod("hardenedshieldemitter") ? TcoSettings.pierceHardenedMult : 1f)) + "%");
        }
        if (BackupGenerator.has(ship.getVariant())) {
            tooltip.addPara("Backup Generator: the engines keep running after a reactor meltdown.", green, 4f);
        }
    }

    /** A paragraph and its coloured highlights. */
    private static final class Line {
        final StringBuilder text = new StringBuilder();
        final List<String> values = new ArrayList<String>();
        final List<Color> colors = new ArrayList<Color>();

        void add(String value, Color color) {
            values.add(value);
            colors.add(color);
        }

        void number(float now, float base, String unit) {
            add(Math.round(now) + unit, WeaponCards.changed(now, base) ? Misc.getPositiveHighlightColor() : Misc.getHighlightColor());
        }
    }
}
