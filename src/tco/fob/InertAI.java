package tco.fob;

import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAIPlugin;
import com.fs.starfarer.api.combat.ShipwideAIFlags;

/** FOB AI: does nothing. */
public class InertAI implements ShipAIPlugin {

    private final ShipwideAIFlags flags = new ShipwideAIFlags();
    private final ShipAIConfig config = new ShipAIConfig();

    @Override
    public void setDoNotFireDelay(float amount) {
    }

    @Override
    public void forceCircumstanceEvaluation() {
    }

    @Override
    public void advance(float amount) {
    }

    @Override
    public boolean needsRefit() {
        return false;
    }

    @Override
    public ShipwideAIFlags getAIFlags() {
        return flags;
    }

    @Override
    public void cancelCurrentManeuver() {
    }

    @Override
    public ShipAIConfig getConfig() {
        return config;
    }
}
