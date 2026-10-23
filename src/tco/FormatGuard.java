package tco;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.loading.HullModSpecAPI;

/**
 * The game builds a hullmod's description with String.format(description, 10 string params), so a mod's bare % ("50%
 * less") crashes whatever shows it (UnknownFormatConversionException). At load, every description that would throw
 * gets its bare % escaped, or every % if that still isn't enough.
 */
public final class FormatGuard {

    private static final int PARAMS = 10;
    // What a description can use with string params: %s, %1$s, %% and %n
    private static final Pattern VALID = Pattern.compile("%(\\d+\\$)?[sS]|%%|%n");

    private FormatGuard() {
    }

    public static void apply() {
        int fixed = 0;
        for (HullModSpecAPI spec : Global.getSettings().getAllHullModSpecs()) {
            try {
                String format = spec.getDescriptionFormat();
                String safe = safe(format);
                if (safe.equals(format == null ? "" : format)) continue;
                spec.setDescriptionFormat(safe);
                fixed++;
                Global.getLogger(FormatGuard.class).info("TCO: escaped a stray % in hullmod " + spec.getId() + "'s description");
            } catch (RuntimeException e) {
                Global.getLogger(FormatGuard.class).warn("TCO: could not check hullmod " + spec.getId() + "'s description", e);
            }
        }
        if (fixed > 0) Global.getLogger(FormatGuard.class).info("TCO: fixed " + fixed + " hullmod descriptions that would crash");
    }

    private static String safe(String format) {
        if (format == null) return "";
        if (format.indexOf('%') < 0 || formats(format)) return format;
        String escaped = escape(format);
        return formats(escaped) ? escaped : format.replace("%", "%%");
    }

    private static boolean formats(String format) {
        try {
            Object[] args = new Object[PARAMS];
            for (int i = 0; i < PARAMS; i++) args[i] = "";
            String.format(format, args);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String escape(String format) {
        StringBuilder out = new StringBuilder(format.length() + 8);
        Matcher m = VALID.matcher(format);
        int i = 0;
        while (i < format.length()) {
            char c = format.charAt(i);
            if (c != '%') {
                out.append(c);
                i++;
            } else if (m.region(i, format.length()).lookingAt()) {
                out.append(m.group());
                i = m.end();
            } else {
                out.append("%%");
                i++;
            }
        }
        return out.toString();
    }
}
