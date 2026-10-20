package tco.hullmods;

import com.fs.starfarer.api.combat.BaseHullMod;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

import tco.TcoSettings;

/**
 * Compact Security Forge: trades cargo space for security droids that defend the ship (never board),
 * take losses before marines and are rebuilt between battles. Fighting is in BoardingCombatPlugin.
 */
public class SecurityForge extends BaseHullMod {

    public static final String ID = "tco_security_forge";

    public static int droidsFor(HullSize size) {
        TcoSettings.load();
        if (size == HullSize.CAPITAL_SHIP) return TcoSettings.securityDroidsCapital;
        if (size == HullSize.CRUISER) return TcoSettings.securityDroidsCruiser;
        if (size == HullSize.DESTROYER) return TcoSettings.securityDroidsDestroyer;
        return TcoSettings.securityDroidsFrigate;
    }

    public static boolean has(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && ship.getVariant().hasHullMod(ID);
    }

    @Override
    public void applyEffectsBeforeShipCreation(HullSize hullSize, MutableShipStatsAPI stats, String id) {
        stats.getCargoMod().modifyMult(id, Math.max(0f, 1f - TcoSettings.securityForgeCargoCost));
    }

    @Override
    public boolean isApplicableToShip(ShipAPI ship) {
        return ship != null && ship.getVariant() != null && !ship.getVariant().hasHullMod("automated")
                && !ship.isStation();
    }

    @Override
    public String getUnapplicableReason(ShipAPI ship) {
        return "Automated ships already have their own droid defenders";
    }

    @Override
    public String getDescriptionParam(int index, HullSize hullSize) {
        TcoSettings.load();
        if (index == 0) return Math.round(TcoSettings.securityForgeCargoCost * 100f) + "%";
        if (index == 1) return "" + TcoSettings.securityDroidsFrigate;
        if (index == 2) return "" + TcoSettings.securityDroidsDestroyer;
        if (index == 3) return "" + TcoSettings.securityDroidsCruiser;
        if (index == 4) return "" + TcoSettings.securityDroidsCapital;
        if (index == 5) return Math.round(TcoSettings.securityDroidStrength * 100f) + "%";
        return null;
    }
}
