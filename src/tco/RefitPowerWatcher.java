package tco;

import com.fs.starfarer.api.EveryFrameScript;

import tco.patches.Patches;
import tco.patches.RefitPowerPatch;

/**
 * Ticks the refit screen's power boxes every frame (also while paused, which the refit screen is): their place next to
 * MagicLib's Paintjobs button, and live values. Only touches the PatchLib patch class while that patch is on.
 */
public class RefitPowerWatcher implements EveryFrameScript {

    @Override
    public boolean isDone() {
        return false;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }

    @Override
    public void advance(float amount) {
        if (Patches.refitPower()) RefitPowerPatch.tick();
    }
}
