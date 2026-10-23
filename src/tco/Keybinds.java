package tco;

import org.json.JSONObject;
import org.lwjgl.input.Keyboard;

import com.fs.starfarer.api.Global;

/** Keys changed in game, saved in the common folder and laid over tco_settings.json on load. */
public final class Keybinds {

    private static final String FILE = "tco_keybinds";

    public static final String[][] ACTIONS = {
        {"keyStop", "Stop"},
        {"keyHoldFire", "Hold fire / open fire"},
        {"keyRetreat", "Retreat"},
        {"keyStance", "Change stance"},
        {"keyFormation", "Formation (line, wedge, column, break up)"},
        {"keyBoard", "Board the enemy ship under the cursor"},
        {"keyHangar", "Close / open hangar"},
        {"keyFighters", "Fighters: engage / regroup"},
        {"keyIgnore", "Ignore the enemy ship under the cursor / stop"},
        {"keyFlares", "Drop flares"},
        {"keyRadar", "Radar off / on"},
        {"keyRadarHold", "Hold / release radar missiles"},
        {"keyJammer", "Noise jammer: auto / on / off"},
        {"keyRadarUnlock", "Release the radar lock"},
        {"keyMissileCam", "Hold: follow your newest missile"},
        {"keyToggleShipAI", "Hand to / take back from the ship AI"},
        {"keyDirectControl", "Pilot the selected ship / back to RTS"},
        {"keyToggleMode", "Switch between RTS and normal controls"},
        {"keyMarineScreen", "Marines and stances screen (campaign map)"},
    };

    private Keybinds() {
    }

    public static int get(String name) {
        if ("keyStop".equals(name)) return TcoSettings.keyStop;
        if ("keyHoldFire".equals(name)) return TcoSettings.keyHoldFire;
        if ("keyRetreat".equals(name)) return TcoSettings.keyRetreat;
        if ("keyStance".equals(name)) return TcoSettings.keyStance;
        if ("keyFormation".equals(name)) return TcoSettings.keyFormation;
        if ("keyBoard".equals(name)) return TcoSettings.keyBoard;
        if ("keyHangar".equals(name)) return TcoSettings.keyHangar;
        if ("keyFighters".equals(name)) return TcoSettings.keyFighters;
        if ("keyIgnore".equals(name)) return TcoSettings.keyIgnore;
        if ("keyFlares".equals(name)) return TcoSettings.keyFlares;
        if ("keyRadar".equals(name)) return TcoSettings.keyRadar;
        if ("keyRadarHold".equals(name)) return TcoSettings.keyRadarHold;
        if ("keyJammer".equals(name)) return TcoSettings.keyJammer;
        if ("keyRadarUnlock".equals(name)) return TcoSettings.keyRadarUnlock;
        if ("keyMissileCam".equals(name)) return TcoSettings.keyMissileCam;
        if ("keyToggleShipAI".equals(name)) return TcoSettings.keyToggleShipAI;
        if ("keyDirectControl".equals(name)) return TcoSettings.keyDirectControl;
        if ("keyToggleMode".equals(name)) return TcoSettings.keyToggleMode;
        if ("keyMarineScreen".equals(name)) return TcoSettings.keyMarineScreen;
        return Keyboard.KEY_NONE;
    }

    private static void set(String name, int key) {
        if ("keyStop".equals(name)) TcoSettings.keyStop = key;
        else if ("keyHoldFire".equals(name)) TcoSettings.keyHoldFire = key;
        else if ("keyRetreat".equals(name)) TcoSettings.keyRetreat = key;
        else if ("keyStance".equals(name)) TcoSettings.keyStance = key;
        else if ("keyFormation".equals(name)) TcoSettings.keyFormation = key;
        else if ("keyBoard".equals(name)) TcoSettings.keyBoard = key;
        else if ("keyHangar".equals(name)) TcoSettings.keyHangar = key;
        else if ("keyFighters".equals(name)) TcoSettings.keyFighters = key;
        else if ("keyIgnore".equals(name)) TcoSettings.keyIgnore = key;
        else if ("keyFlares".equals(name)) TcoSettings.keyFlares = key;
        else if ("keyRadar".equals(name)) TcoSettings.keyRadar = key;
        else if ("keyRadarHold".equals(name)) TcoSettings.keyRadarHold = key;
        else if ("keyJammer".equals(name)) TcoSettings.keyJammer = key;
        else if ("keyRadarUnlock".equals(name)) TcoSettings.keyRadarUnlock = key;
        else if ("keyMissileCam".equals(name)) TcoSettings.keyMissileCam = key;
        else if ("keyToggleShipAI".equals(name)) TcoSettings.keyToggleShipAI = key;
        else if ("keyDirectControl".equals(name)) TcoSettings.keyDirectControl = key;
        else if ("keyToggleMode".equals(name)) TcoSettings.keyToggleMode = key;
        else if ("keyMarineScreen".equals(name)) TcoSettings.keyMarineScreen = key;
    }

    /** Keys already used by RTS mode. */
    public static boolean isReserved(int key) {
        return key == Keyboard.KEY_ESCAPE || key == Keyboard.KEY_W || key == Keyboard.KEY_A || key == Keyboard.KEY_S
                || key == Keyboard.KEY_D || key == Keyboard.KEY_UP || key == Keyboard.KEY_DOWN || key == Keyboard.KEY_LEFT
                || key == Keyboard.KEY_RIGHT || (key >= Keyboard.KEY_1 && key <= Keyboard.KEY_9)
                || key == Keyboard.KEY_LSHIFT || key == Keyboard.KEY_RSHIFT || key == Keyboard.KEY_LCONTROL
                || key == Keyboard.KEY_RCONTROL || key == Keyboard.KEY_LMENU || key == Keyboard.KEY_RMENU
                || key == Keyboard.KEY_NONE;
    }

    /** Gives an action a new key; an action that had it swaps. Returns the swapped action's name, or null. */
    public static String rebind(String name, int key) {
        int old = get(name);
        String swapped = null;
        for (String[] action : ACTIONS) {
            if (!action[0].equals(name) && get(action[0]) == key) {
                set(action[0], old);
                swapped = action[1];
            }
        }
        set(name, key);
        save();
        return swapped;
    }

    public static void applySaved() {
        try {
            if (!Global.getSettings().fileExistsInCommon(FILE)) return;
            JSONObject json = new JSONObject(Global.getSettings().readTextFileFromCommon(FILE));
            for (String[] action : ACTIONS) {
                String value = json.optString(action[0], "");
                if (value.isEmpty()) continue;
                int key = InputKeys.parse(value);
                if (key != Keyboard.KEY_NONE) set(action[0], key);
            }
        } catch (Exception e) {
            Global.getLogger(Keybinds.class).warn("TCO: could not read saved key bindings", e);
        }
    }

    private static void save() {
        try {
            JSONObject json = new JSONObject();
            for (String[] action : ACTIONS) json.put(action[0], InputKeys.name(get(action[0])));
            Global.getSettings().writeTextFileToCommon(FILE, json.toString(2));
        } catch (Exception e) {
            Global.getLogger(Keybinds.class).warn("TCO: could not save key bindings", e);
        }
    }

    public static void resetToDefaults() {
        try {
            if (Global.getSettings().fileExistsInCommon(FILE)) Global.getSettings().deleteTextFileFromCommon(FILE);
        } catch (Exception e) {
            Global.getLogger(Keybinds.class).warn("TCO: could not delete saved key bindings", e);
        }
        TcoSettings.reload();
    }
}
