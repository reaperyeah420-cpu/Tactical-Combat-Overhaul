package tco.hullmods;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
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

/**
 * "TCO - Weapon Data": no combat effect; the description lists TCO's numbers for every weapon on the ship,
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
        tooltip.addPara("Green: changed by this ship's hullmods or skills. Penetration is for square-on hits;"
                + " sloped armor counts as thicker.", gray, 8f);
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
