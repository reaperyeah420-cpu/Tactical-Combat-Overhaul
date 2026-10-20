package tco.rescue;

import java.util.ArrayList;
import java.util.List;

/**
 * The player's pod survivors in the last battle (rescued, in transit, drifting), kept for the campaign side.
 */
public final class PodLedger {

    private static boolean active;
    private static float rescuedCrew;
    private static float rescuedMarines;
    private static float transitCrew;
    private static float transitMarines;
    private static List<float[]> drifting = new ArrayList<float[]>();
    private static float lostCrew;
    private static float lostMarines;

    private PodLedger() {
    }

    static void begin() {
        active = true;
        rescuedCrew = 0f;
        rescuedMarines = 0f;
        transitCrew = 0f;
        transitMarines = 0f;
        lostCrew = 0f;
        lostMarines = 0f;
        drifting = new ArrayList<float[]>();
    }

    static void lost(float crew, float marines) {
        lostCrew += crew;
        lostMarines += marines;
    }

    public static float lostCrew() {
        return lostCrew;
    }

    public static float lostMarines() {
        return lostMarines;
    }

    static void rescued(float crew, float marines) {
        rescuedCrew += crew;
        rescuedMarines += marines;
    }

    static void update(float crewInTransit, float marinesInTransit, List<float[]> pods) {
        transitCrew = crewInTransit;
        transitMarines = marinesInTransit;
        drifting = pods;
    }

    public static boolean isActive() {
        return active;
    }

    /** Rescued, or aboard a rescue shuttle at the end. */
    public static float savedCrew() {
        return rescuedCrew + transitCrew;
    }

    public static float savedMarines() {
        return rescuedMarines + transitMarines;
    }

    /** {crew, marines} per pod still drifting at the end. */
    public static List<float[]> drifting() {
        return new ArrayList<float[]>(drifting);
    }

    public static void clear() {
        active = false;
        lostCrew = 0f;
        lostMarines = 0f;
        rescuedCrew = 0f;
        rescuedMarines = 0f;
        transitCrew = 0f;
        transitMarines = 0f;
        drifting = new ArrayList<float[]>();
    }
}
