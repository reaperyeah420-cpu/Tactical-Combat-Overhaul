package tco;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.combat.MutableShipStatsAPI;
import com.fs.starfarer.api.combat.ShipAPI;

import lunalib.backend.util.ReflectionUtils;

import tco.patches.Patches;

/**
 * Rewrites the weapon cards for the ship open in the refit screen, and back to base values on leaving.
 * The ship is read from the screen's non-API objects via LunaLib's reflection helper; any failure turns this
 * off for the session.
 */
public class RefitShipCards implements EveryFrameScript {

    private static final float INTERVAL = 0.25f;

    private boolean broken;
    private boolean shipValues;
    private float timer;
    private ShipAPI lastShip;
    private String lastKey;

    @Override
    public boolean isDone() {
        return broken;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }

    private static Boolean uwc;

    private static boolean wanted() {
        // The tooltip patch writes the cards as each tooltip opens, once it has shown it gets the ship's stats;
        // until then this script keeps doing it
        if (Patches.refitCards() && Patches.refitCardsDelivered) return false;
        if (uwc == null) uwc = Global.getSettings().getModManager().isModEnabled("unthemedweapons");
        return TcoSettings.refitShipCards && (uwc || TcoSettings.refitShipCardsAlways);
    }

    @Override
    public void advance(float amount) {
        CampaignUIAPI ui = Global.getSector() == null ? null : Global.getSector().getCampaignUI();
        boolean inRefit = ui != null && CoreUITabId.REFIT.equals(ui.getCurrentCoreTab());
        if (!inRefit || !wanted()) {
            if (shipValues) restore();
            return;
        }
        timer -= Math.max(amount, 0.016f);
        if (timer > 0f) return;
        timer = INTERVAL;
        try {
            ShipAPI ship = refitShip(ui);
            if (ship == null) {
                if (shipValues) restore();
                return;
            }
            String key = key(ship.getMutableStats());
            if (ship == lastShip && key.equals(lastKey)) return;
            lastShip = ship;
            lastKey = key;
            WeaponCards.apply(ship.getMutableStats());
            shipValues = true;
        } catch (Throwable e) {
            broken = true;
            Global.getLogger(RefitShipCards.class).warn("TCO: could not read the refit screen's ship; weapon cards keep base values", e);
            restore();
        }
    }

    private void restore() {
        WeaponCards.apply();
        shipValues = false;
        lastShip = null;
        lastKey = null;
    }

    private static ShipAPI refitShip(CampaignUIAPI ui) {
        InteractionDialogAPI dialog = ui.getCurrentInteractionDialog();
        Object core = dialog == null ? ReflectionUtils.INSTANCE.get("core", ui, null)
                : ReflectionUtils.invoke("getCoreUI", dialog, new Object[0], null, null);
        if (core == null) return null;
        Object tab = ReflectionUtils.invoke("getCurrentTab", core, new Object[0], null, null);
        if (tab == null) return null;
        Object refitPanel = ReflectionUtils.invoke("getRefitPanel", tab, new Object[0], null, null);
        if (refitPanel == null) return null;
        Object display = ReflectionUtils.invoke("getShipDisplay", refitPanel, new Object[0], null, null);
        if (display == null) return null;
        Object ship = ReflectionUtils.invoke("getShip", display, new Object[0], null, null);
        return ship instanceof ShipAPI ? (ShipAPI) ship : null;
    }

    private static String key(MutableShipStatsAPI s) {
        return s.getBallisticWeaponDamageMult().getModifiedValue() + "/" + s.getEnergyWeaponDamageMult().getModifiedValue()
                + "/" + s.getMissileWeaponDamageMult().getModifiedValue() + "/" + s.getProjectileSpeedMult().getModifiedValue()
                + "/" + s.getBallisticProjectileSpeedMult().getModifiedValue() + "/" + s.getBallisticAmmoBonus().computeEffective(1000f)
                + "/" + s.getBallisticRoFMult().getModifiedValue()
                + "/" + s.getBallisticWeaponRangeBonus().computeEffective(1000f) + "/" + s.getEnergyWeaponRangeBonus().computeEffective(1000f)
                + "/" + s.getMissileWeaponRangeBonus().computeEffective(1000f) + "/" + s.getBeamWeaponRangeBonus().computeEffective(1000f)
                + "/" + s.getFluxCapacity().getModifiedValue() + "/" + s.getFluxDissipation().getModifiedValue()
                + "/" + s.getEnergyWeaponFluxCostMod().computeEffective(1000f) + "/" + s.getBallisticWeaponFluxCostMod().computeEffective(1000f)
                + "/" + s.getBeamWeaponFluxCostMult().getModifiedValue();
    }
}
