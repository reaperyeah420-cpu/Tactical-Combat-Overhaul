package tco.patches;

import java.awt.Color;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.loading.WeaponSpecAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.util.Misc;

import patchlib.api.context.AfterContext;
import patchlib.api.context.BeforeContext;
import patchlib.api.context.ExceptContext;
import patchlib.api.context.MethodCallContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.After;
import patchlib.api.patch.Before;
import patchlib.api.patch.Except;
import patchlib.api.patch.Patch;
import patchlib.api.patch.RedirectMethodCall;

import tco.TcoSettings;
import tco.WeaponCards;

/**
 * The game's weapon tooltip, built with the weapon and, in the refit screen, the ship's stats. The card is written
 * for that ship just before and put back after, and ship-changed values are drawn in the positive colour.
 */
@Patch(target = @ClassMatch(typeName = Patches.TOOLTIPS))
public class WeaponTooltipPatch {

    private static final String SHIP_CARD = "tco_ship_card";

    @Before(target = @MethodMatch(parameterTypeNames = {Patches.WEAPON_SPEC, Patches.CHARACTER, Patches.SHIP_STATS,
            Patches.TOOLTIP_MODE}))
    public static void beforeWeaponTooltip(BeforeContext context) {
        if (!Patches.refitCards() || !TcoSettings.refitShipCards) return;
        // Found by type, not position
        Object spec = null, stats = null;
        Object[] args = context.getArgs();
        if (args != null) {
            for (Object a : args) {
                if (spec == null && a instanceof WeaponSpecAPI) spec = a;
                if (stats == null && a instanceof MutableShipStatsAPI) stats = a;
            }
        }
        if (!(spec instanceof WeaponSpecAPI)) return;
        if (!(stats instanceof MutableShipStatsAPI)) {
            if (!noStatsLogged) {
                noStatsLogged = true;
                StringBuilder seen = new StringBuilder();
                if (args != null) for (Object a : args) seen.append(a == null ? "null" : a.getClass().getName()).append(", ");
                Global.getLogger(WeaponTooltipPatch.class).info("TCO: weapon tooltip without ship stats (args: " + seen + ")");
            }
            return;
        }
        WeaponCards.applyFor((WeaponSpecAPI) spec, (MutableShipStatsAPI) stats);
        context.setLocal(SHIP_CARD, spec);
        if (!Patches.refitCardsDelivered) {
            Patches.refitCardsDelivered = true;
            Global.getLogger(WeaponTooltipPatch.class).info("TCO: ship-adjusted weapon cards from the tooltip patch");
        }
    }

    private static boolean noStatsLogged;

    @After(target = @MethodMatch(parameterTypeNames = {Patches.WEAPON_SPEC, Patches.CHARACTER, Patches.SHIP_STATS,
            Patches.TOOLTIP_MODE}))
    public static void afterWeaponTooltip(AfterContext context) {
        Object spec = context.getLocal(SHIP_CARD);
        if (spec instanceof WeaponSpecAPI) WeaponCards.applyFor((WeaponSpecAPI) spec, null);
    }

    @Except(target = @MethodMatch(parameterTypeNames = {Patches.WEAPON_SPEC, Patches.CHARACTER, Patches.SHIP_STATS,
            Patches.TOOLTIP_MODE}))
    public static void exceptWeaponTooltip(ExceptContext context) {
        Object spec = context.getLocal(SHIP_CARD);
        if (spec instanceof WeaponSpecAPI) WeaponCards.applyFor((WeaponSpecAPI) spec, null);
    }

    @RedirectMethodCall(
            target = @MethodMatch(parameterTypeNames = {Patches.WEAPON_SPEC, Patches.CHARACTER, Patches.SHIP_STATS,
                    Patches.TOOLTIP_MODE}),
            call = @MethodMatch(methodName = "addPara", parameterTypeNames = {"java.lang.String", "float", "java.awt.Color",
                    "java.lang.String[]"}),
            owner = @ClassMatch(typeName = Patches.TOOLTIP))
    public static void colourCardValues(MethodCallContext context) {
        Object spec = context.getArg(0);
        Object receiver = context.getCallReceiver();
        Object text = context.getCallArg(0);
        float pad = (Float) context.getCallArg(1);
        Color normal = (Color) context.getCallArg(2);
        String[] highlights = (String[]) context.getCallArg(3);
        if (!(receiver instanceof TooltipMakerAPI)) {
            context.setResult(context.call());
            return;
        }
        TooltipMakerAPI tooltip = (TooltipMakerAPI) receiver;
        boolean[] flags = Patches.cardColours() && spec instanceof WeaponSpecAPI
                ? WeaponCards.changedHighlights(((WeaponSpecAPI) spec).getWeaponId()) : null;
        // Only the weapon's own card text, and only when the highlights still line up with the flags.
        // Made on the tooltip directly: PatchLib's call() passes a varargs array on as a single element
        if (flags == null || highlights == null || highlights.length != flags.length
                || !text.equals(((WeaponSpecAPI) spec).getCustomAncillary())) {
            context.setResult(tooltip.addPara((String) text, pad, normal, highlights));
            return;
        }
        Color changed = Misc.getPositiveHighlightColor();
        Color[] colours = new Color[flags.length];
        for (int i = 0; i < flags.length; i++) colours[i] = flags[i] ? changed : normal;
        context.setResult(tooltip.addPara((String) text, pad, colours, highlights));
    }
}
