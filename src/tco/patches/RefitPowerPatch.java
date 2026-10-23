package tco.patches;

import java.awt.Color;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.campaign.ui.marketinfo.L;
import com.fs.starfarer.coreui.refit.ModWidget;
import com.fs.starfarer.coreui.refit.U;
import com.fs.starfarer.coreui.refit.ooOO;
import com.fs.starfarer.ui.n;

import patchlib.api.context.AfterContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.After;
import patchlib.api.patch.Patch;
import patchlib.api.query.FieldQuery;
import patchlib.api.spec.FieldQuerySpec;

import tco.TcoSettings;
import tco.hullmods.AuxiliaryGenerator;
import tco.hullmods.SecondaryEnergyBanks;
import tco.power.PowerCombatPlugin;

/**
 * Reactor power in the refit screen: two stat boxes like the game's own, "power" (capacity) and "power regen",
 * directly below the "Build in (Q)" button, or below MagicLib's "Paintjobs" button when the ship has one.
 * <p>
 * The hullmod widget (`coreui.refit.ModWidget`, from the stats panel's `getMods()`) is a chain: header, the hullmod
 * list `belowRight` it (resized to the number of hullmods, then packed), "Add" `belowRight` the list, and "Build in"
 * `belowRight` "Add", the last only when the game lets the ship build in hullmods. The boxes join that chain as
 * siblings, `belowRight` "Build in" (or "Add" when "Build in" isn't there), so they follow the list as it grows.
 * MagicLib adds its Paintjobs button (a ButtonAPI child with custom data "PAINTJOB_BUTTON") `belowLeft` whatever was
 * the widget's last child, often our box; `tick` (every frame, from tco.RefitPowerWatcher) anchors that button on
 * "Build in" and the boxes on it, and back on "Build in" when MagicLib takes the button away.
 * <p>
 * Values come from the refit screen's preview ship and the variant being edited (what the game's own stats panel
 * reads), on every `syncWithVariant` and every tick, so they follow changes live. The fleet member's own stats and
 * variant only catch up when the refit screen closes.
 */
@Patch(target = @ClassMatch(typeName = Patches.REFIT_STATS))
public class RefitPowerPatch {

    // The refit screen, found by type (the obfuscated field names don't match what javap prints)
    private static final FieldQuerySpec REFIT = FieldQuery.create().typeName("com.fs.starfarer.coreui.refit.U").build();
    // The game's gap between the widget's rows
    private static final float PAD = 3f;
    private static final String PAINTJOB = "PAINTJOB_BUTTON";

    private static final class Boxes {
        L power, regen;
        PositionAPI regenPos;
        n anchor;
        // The Paintjobs button the boxes sit under, or null
        UIComponentAPI under;
        U refit;
    }

    // hullmod widget -> its boxes
    private static final Map<ModWidget, Boxes> boxes = new WeakHashMap<ModWidget, Boxes>();
    private static final Set<String> noted = new HashSet<String>();

    private static void note(String what) {
        if (noted.add(what)) Global.getLogger(RefitPowerPatch.class).info("TCO refit power: " + what);
    }

    @After(target = @MethodMatch(methodName = "syncWithVariant", parameterCount = 1))
    public static void afterSync(AfterContext context) {
        if (!Patches.refitPower() || !TcoSettings.powerEnabled) return;
        try {
            ModWidget mods = ((ooOO) context.getSelf()).getMods();
            if (mods == null) {
                note("no hullmod widget on the stats panel");
                return;
            }
            Boxes mine = boxes.get(mods);
            if (mine == null) {
                mine = attach(mods);
                if (mine == null) return;
            }
            Object refit = context.getField(REFIT).get();
            if (!(refit instanceof U)) {
                note("no refit screen on the stats panel (" + refit + ")");
                return;
            }
            mine.refit = (U) refit;
            Object arg = context.getArg(0);
            update(mods, mine, arg instanceof ShipVariantAPI ? (ShipVariantAPI) arg : null);
        } catch (Throwable e) {
            note("could not show the boxes: " + e);
        }
    }

    /** Every frame while a refit screen is open (tco.RefitPowerWatcher): placement next to Paintjobs, live values. */
    public static void tick() {
        if (boxes.isEmpty() || !TcoSettings.powerEnabled) return;
        try {
            for (Map.Entry<ModWidget, Boxes> e : boxes.entrySet()) {
                Boxes b = e.getValue();
                if (b.refit == null) continue;
                update(e.getKey(), b, null);
            }
        } catch (Throwable e) {
            note("could not update the boxes: " + e);
        }
    }

    private static void update(ModWidget mods, Boxes mine, ShipVariantAPI variant) {
        place(mods, mine);
        MutableShipStatsAPI stats = null;
        // The preview ship: the stats the game's own panel shows, already counting the edit in progress
        try {
            Object ship = mine.refit.getShipDisplay().getShip();
            if (ship instanceof ShipAPI) {
                stats = ((ShipAPI) ship).getMutableStats();
                if (variant == null) variant = ((ShipAPI) ship).getVariant();
            }
        } catch (Throwable e) {
            note("no preview ship, using the fleet member (" + e + ")");
        }
        if (stats == null || variant == null) {
            FleetMemberAPI member = mine.refit.getMember();
            if (member == null) return;
            if (stats == null) stats = member.getStats();
            if (variant == null) variant = member.getVariant();
        }
        if (stats == null || variant == null) return;
        float capacity = PowerCombatPlugin.capacityFor(stats, variant);
        float regen = PowerCombatPlugin.regenFor(stats, variant);
        mine.power.setValue(String.valueOf(Math.round(capacity)));
        mine.regen.setValue("+" + Math.round(regen) + "/s");
        colour(mine.power, SecondaryEnergyBanks.has(variant));
        colour(mine.regen, AuxiliaryGenerator.has(variant));
        note("values shown (" + Math.round(capacity) + ", +" + Math.round(regen) + "/s)");
    }

    /** Under MagicLib's Paintjobs button when the widget has one, else under "Build in" (or "Add"). */
    private static void place(ModWidget mods, Boxes mine) {
        UIComponentAPI paintjob = null;
        for (Object child : mods.getChildrenNonCopy()) {
            if (child instanceof ButtonAPI && PAINTJOB.equals(((ButtonAPI) child).getCustomData())) {
                paintjob = (UIComponentAPI) child;
                break;
            }
        }
        if (paintjob == mine.under) return;
        if (paintjob != null) {
            // MagicLib anchors it on the widget's last child, often our box: put it under Build in first, then us
            paintjob.getPosition().belowLeft(mine.anchor, PAD);
            mine.regenPos.belowRight(paintjob, PAD);
            note("boxes moved below the Paintjobs button");
        } else {
            mine.regenPos.belowRight(mine.anchor, PAD);
        }
        mine.under = paintjob;
    }

    /** Adds the boxes to the hullmod widget, side by side directly below "Build in" (or "Add"). */
    private static Boxes attach(ModWidget mods) {
        // Anchoring on something that isn't a child of the widget crashes ("May only anchor on siblings")
        n anchor = mods.getPerm();
        String under = "Build in";
        if (anchor == null || !mods.getChildrenNonCopy().contains(anchor)) {
            anchor = mods.getAdd();
            under = "Add";
        }
        if (anchor == null || !mods.getChildrenNonCopy().contains(anchor)) {
            note("neither Build in nor Add is in the hullmod widget");
            return null;
        }
        L power = new L("power");
        L regen = new L("power regen");
        n powerButton = button(power);
        n regenButton = button(regen);
        // Right-aligned under the button like the game's own rows; power to the left of regen
        PositionAPI regenPos = mods.add(regenButton).belowRight(anchor, PAD);
        mods.add(powerButton).leftOfMid(regenButton, PAD);
        Boxes made = new Boxes();
        made.power = power;
        made.regen = regen;
        made.regenPos = regenPos;
        made.anchor = anchor;
        boxes.put(mods, made);
        note("boxes added below " + under);
        return made;
    }

    private static n button(L box) {
        n button = L.createButton(box);
        button.setSize(box.getWidth(), box.getHeight());
        return button;
    }

    // Boosted by a power hullmod: green, as the game shows boosted stats; otherwise its own colour
    private static final Map<L, Color> plain = new WeakHashMap<L, Color>();

    private static void colour(L box, boolean boosted) {
        if (box.valueLabel == null) return;
        if (!plain.containsKey(box)) plain.put(box, box.valueLabel.getColor());
        box.valueLabel.setColor(boosted ? Misc.getPositiveHighlightColor() : plain.get(box));
    }
}
