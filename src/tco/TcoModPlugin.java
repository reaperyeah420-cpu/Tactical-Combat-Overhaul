package tco;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

import tco.ai.NpcHullmods;
import tco.battle.TcoCampaignPlugin;
import tco.boarding.BoardingResults;
import tco.boarding.MarineScreen;
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
        // Radar setup must run before the weapon cards
        RadarSetup.apply();
        WeaponCards.apply();
    }

    @Override
    public void onGameLoad(boolean newGame) {
        // Transient: nothing of TCO goes in the save
        Global.getSector().registerPlugin(new TcoCampaignPlugin());
        // Re-registered after every other mod's plugins
        Global.getSector().addTransientScript(new TcoCampaignPlugin.LateRegister());
        Global.getSector().addTransientListener(new BoardingResults.Listener());
        Global.getSector().addTransientListener(new PodResults.Listener());
        Global.getSector().addTransientScript(new MarineScreen.Hotkey());
        Global.getSector().addTransientScript(new NpcHullmods.Scanner());
        NpcHullmods.opBonus(Global.getSector().getPlayerStats());
        // LunaLib values may have changed since startup
        WeaponCards.apply();
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
