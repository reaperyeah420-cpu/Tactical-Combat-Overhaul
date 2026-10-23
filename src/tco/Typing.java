package tco;

import com.fs.starfarer.api.Global;

/**
 * Whether another mod's text box has the keyboard, so TCO's key bindings stay off. Today: Console Commands' console
 * (lw_console), checked only when that mod is enabled.
 */
public final class Typing {

    private static Boolean console;

    private Typing() {
    }

    public static boolean active() {
        if (console == null) {
            try {
                console = Global.getSettings().getModManager().isModEnabled("lw_console");
            } catch (RuntimeException e) {
                console = false;
            }
        }
        if (!console) return false;
        try {
            return ConsoleCheck.open();
        } catch (Throwable t) {
            // A Console Commands without the overlay this checks for
            console = false;
            Global.getLogger(Typing.class).warn("TCO: can't tell when the Console Commands console is open;"
                    + " TCO's keys stay active while typing in it", t);
            return false;
        }
    }
}
