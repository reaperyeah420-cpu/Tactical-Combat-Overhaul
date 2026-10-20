package tco;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * Key codes for keyboard keys plus the mouse side buttons, which the game's input never passes on.
 * Mouse button b is stored as MOUSE_BASE + b and named "MOUSE" + (b + 1).
 */
public final class InputKeys {

    public static final int MOUSE_BASE = 0x10000;
    private static final int FIRST_BUTTON = 3;
    private static final int LAST_BUTTON = 15;
    private static final boolean[] wasDown = new boolean[LAST_BUTTON + 1];

    private InputKeys() {
    }

    public static boolean isMouse(int code) {
        return code >= MOUSE_BASE + FIRST_BUTTON && code <= MOUSE_BASE + LAST_BUTTON;
    }

    public static String name(int code) {
        if (isMouse(code)) return "MOUSE" + (code - MOUSE_BASE + 1);
        if (code < 0 || code >= Keyboard.KEYBOARD_SIZE) return "NONE";
        String name = Keyboard.getKeyName(code);
        return name == null ? "NONE" : name;
    }

    /** Name back to a code; Keyboard.KEY_NONE if unknown. */
    public static int parse(String name) {
        if (name == null) return Keyboard.KEY_NONE;
        String upper = name.trim().toUpperCase();
        if (upper.startsWith("MOUSE")) {
            try {
                int button = Integer.parseInt(upper.substring(5).trim()) - 1;
                if (button >= FIRST_BUTTON && button <= LAST_BUTTON) return MOUSE_BASE + button;
            } catch (NumberFormatException e) {
            }
            return Keyboard.KEY_NONE;
        }
        return Keyboard.getKeyIndex(upper);
    }

    public static boolean isDown(int code) {
        if (isMouse(code)) {
            int button = code - MOUSE_BASE;
            return Mouse.isCreated() && button < Mouse.getButtonCount() && Mouse.isButtonDown(button);
        }
        return code > 0 && code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }

    /** Extra mouse buttons pressed since the last call (once per frame). */
    public static List<Integer> mousePresses() {
        List<Integer> pressed = new ArrayList<Integer>();
        if (!Mouse.isCreated()) return pressed;
        int last = Math.min(LAST_BUTTON, Mouse.getButtonCount() - 1);
        for (int b = FIRST_BUTTON; b <= LAST_BUTTON; b++) {
            boolean down = b <= last && Mouse.isButtonDown(b);
            if (down && !wasDown[b]) pressed.add(MOUSE_BASE + b);
            wasDown[b] = down;
        }
        return pressed;
    }
}
