package tco;

import org.lazywizard.console.overlay.v2.panels.ConsoleOverlayPanel;

/** Console Commands' console overlay. Only loaded through Typing, when lw_console is enabled. */
final class ConsoleCheck {

    private ConsoleCheck() {
    }

    static boolean open() {
        return ConsoleOverlayPanel.getInstance() != null;
    }
}
