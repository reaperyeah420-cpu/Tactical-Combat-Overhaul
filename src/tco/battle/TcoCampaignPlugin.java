package tco.battle;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.PluginPick;
import com.fs.starfarer.api.campaign.BaseCampaignPlugin;
import com.fs.starfarer.api.campaign.BattleCreationPlugin;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignPlugin;
import com.fs.starfarer.api.campaign.SectorEntityToken;
import com.fs.starfarer.api.impl.combat.BattleCreationPluginImpl;

import tco.TcoSettings;
import tco.ai.NpcHullmods;

/**
 * Wraps battle creation to scale the map, at MOD_GENERAL so higher-priority picks win.
 * Wraps SotF's Tactical Expansion (at MOD_SET) and RAT's Abyss battles (at their own priority) when present;
 * re-registers after every mod's onGameLoad to win ties.
 */
public class TcoCampaignPlugin extends BaseCampaignPlugin {

    private static final String SOTF_ID = "secretsofthefrontier";
    private static final String SOTF_PLUGIN = "data.scripts.plugins.SotfBattleCreationPluginImpl";
    private static final String RAT_ID = "assortment_of_things";
    private static final String RAT_CAMPAIGN_PLUGIN = "assortment_of_things.RATCampaignPlugin";

    private static CampaignPlugin rat;
    private static boolean ratChecked;

    private static Boolean sotfTactical;

    @Override
    public String getId() {
        return "tco_campaign_plugin";
    }

    @Override
    public boolean isTransient() {
        return true;
    }

    @Override
    public PluginPick<BattleCreationPlugin> pickBattleCreationPlugin(SectorEntityToken opponent) {
        TcoSettings.load();
        if (!(opponent instanceof CampaignFleetAPI)) return null;
        try {
            NpcHullmods.applyToBattle((CampaignFleetAPI) opponent);
        } catch (RuntimeException e) {
            Global.getLogger(TcoCampaignPlugin.class).warn("TCO: could not fit NPC hullmods before battle", e);
        }
        if (TcoSettings.mapSizeMult == 1f && TcoSettings.escapeMapSizeMult == 1f && !TcoSettings.fobEnabled
                && !TcoSettings.dockEnabled) return null;
        PluginPick<BattleCreationPlugin> abyss = ratPick(opponent);
        if (abyss != null) {
            return new PluginPick<BattleCreationPlugin>(new ScaledBattleCreationPlugin(abyss.plugin, true), abyss.priority);
        }
        BattleCreationPlugin sotf = sotfPlugin();
        if (sotf != null) {
            return new PluginPick<BattleCreationPlugin>(new ScaledBattleCreationPlugin(sotf), PickPriority.MOD_SET);
        }
        return new PluginPick<BattleCreationPlugin>(
                new ScaledBattleCreationPlugin(new BattleCreationPluginImpl()), PickPriority.MOD_GENERAL);
    }

    private static PluginPick<BattleCreationPlugin> ratPick(SectorEntityToken opponent) {
        if (!ratChecked) {
            ratChecked = true;
            try {
                if (Global.getSettings().getModManager().isModEnabled(RAT_ID)) {
                    Object plugin = Global.getSettings().getScriptClassLoader().loadClass(RAT_CAMPAIGN_PLUGIN).newInstance();
                    if (plugin instanceof CampaignPlugin) rat = (CampaignPlugin) plugin;
                }
            } catch (Throwable e) {
                Global.getLogger(TcoCampaignPlugin.class).warn("TCO: could not create RAT's campaign plugin; Abyss maps stay unscaled", e);
            }
        }
        if (rat == null) return null;
        try {
            PluginPick<BattleCreationPlugin> pick = rat.pickBattleCreationPlugin(opponent);
            if (pick == null || pick.plugin == null || pick.plugin instanceof ScaledBattleCreationPlugin) return null;
            return pick;
        } catch (Throwable e) {
            Global.getLogger(TcoCampaignPlugin.class).warn("TCO: RAT's battle pick failed; leaving it to RAT", e);
            return null;
        }
    }

    /** Re-registers to the end of the engine's list, to win priority ties. */
    public static void registerLast() {
        Global.getSector().registerPlugin(new TcoCampaignPlugin());
    }

    /** Runs registerLast() on the first campaign frame. */
    public static class LateRegister implements EveryFrameScript {
        private boolean done;

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public boolean runWhilePaused() {
            return true;
        }

        @Override
        public void advance(float amount) {
            if (done) return;
            done = true;
            registerLast();
        }
    }

    private static BattleCreationPlugin sotfPlugin() {
        if (sotfTactical == null) {
            boolean on = false;
            try {
                if (Global.getSettings().getModManager().isModEnabled(SOTF_ID)) {
                    on = Global.getSettings().loadJSON("sotf_settings.ini", SOTF_ID).optBoolean("enableTacticalExpansion", false);
                }
            } catch (Exception e) {
                Global.getLogger(TcoCampaignPlugin.class).warn("TCO: could not read SotF's settings", e);
            }
            sotfTactical = on;
            Global.getLogger(TcoCampaignPlugin.class).info("TCO: SotF Tactical Expansion " + (on ? "on, wrapping its battle creation" : "off"));
        }
        if (!sotfTactical) return null;
        try {
            Object plugin = Global.getSettings().getScriptClassLoader().loadClass(SOTF_PLUGIN).newInstance();
            if (plugin instanceof BattleCreationPlugin) return (BattleCreationPlugin) plugin;
        } catch (Throwable e) {
            Global.getLogger(TcoCampaignPlugin.class).warn("TCO: could not create SotF's battle creation; using vanilla", e);
        }
        sotfTactical = false;
        return null;
    }
}
