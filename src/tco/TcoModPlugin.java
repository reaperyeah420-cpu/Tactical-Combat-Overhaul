package tco;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

import tco.ai.NpcHullmods;
import tco.ammo.EnergyAmmo;
import tco.battle.TcoCampaignPlugin;
import tco.boarding.BoardingResults;
import tco.boarding.MarineScreen;
import tco.patches.PatchLibSetup;
import tco.radar.RadarSetup;
import tco.rescue.PodResults;

public class TcoModPlugin extends BaseModPlugin {

    @Override
    public void onApplicationLoad() {
        TcoSettings.load();
        // LunaLib settings menu (optional dependency)
        if (TcoSettings.lunaEnabled()) {
            try {
                TcoLuna.register();
            } catch (Throwable e) {
                Global.getLogger(TcoModPlugin.class).warn("TCO: could not set up the LunaLib settings menu", e);
            }
        }
        // PatchLib is optional; it can only look up its targets during application load
        if (Global.getSettings().getModManager().isModEnabled("patchlib")) {
            try {
                PatchLibSetup.init();
            } catch (Throwable e) {
                Global.getLogger(TcoModPlugin.class).warn("TCO: PatchLib unavailable, using the old methods", e);
            }
        } else {
            Global.getLogger(TcoModPlugin.class).info("TCO: PatchLib not enabled, using the API-only methods");
        }
        // Radar setup and energy ammo must run before the weapon cards
        RadarSetup.apply();
        EnergyAmmo.apply();
        WeaponCards.apply();
        FormatGuard.apply();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // Transient: nothing of TCO goes in the save
        Global.getSector().registerPlugin(new TcoCampaignPlugin());
        // Re-registered after every other mod's plugins
        Global.getSector().addTransientScript(new TcoCampaignPlugin.LateRegister());
        Global.getSector().addTransientListener(new BoardingResults.Listener());
        Global.getSector().addTransientScript(new BoardingResults.Returned());
        Global.getSector().addTransientListener(new PodResults.Listener());
        Global.getSector().addTransientScript(new MarineScreen.Hotkey());
        Global.getSector().addTransientScript(new NpcHullmods.Scanner());
        Global.getSector().addTransientScript(new RefitPowerWatcher());
        NpcHullmods.opBonus(Global.getSector().getPlayerStats());
        // LunaLib values may have changed since startup
        EnergyAmmo.apply();
        WeaponCards.apply();
        // Again here, for mods that load after TCO or rewrite descriptions on game load
        FormatGuard.apply();
        // Ship-adjusted refit cards (needs LunaLib)
        if (TcoSettings.lunaEnabled()) {
            try {
                Global.getSector().addTransientScript(new RefitShipCards());
            } catch (Throwable e) {
                Global.getLogger(TcoModPlugin.class).warn("TCO: could not set up ship-adjusted weapon cards", e);
            }
        }
    }
}
