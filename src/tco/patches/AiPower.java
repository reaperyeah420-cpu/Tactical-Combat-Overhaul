package tco.patches;

import com.fs.starfarer.api.combat.ShipAPI;

import patchlib.api.context.MethodCallContext;

import tco.power.PowerCombatPlugin;

/**
 * Shared by the AI power patches: the game's AI reads a ship's flux level to decide when to hold fire and back
 * off. These reads get the higher of the real level and how low the ship's power is, so the AI rations power
 * the way it rations flux. Venting, shields and ship systems keep the real level.
 */
final class AiPower {

    private AiPower() {
    }

    static void fluxLevel(MethodCallContext context) {
        ShipAPI ship = (ShipAPI) context.getCallReceiver();
        float real = ship.getFluxLevel();
        context.setResult(Patches.aiPower() ? Math.max(real, PowerCombatPlugin.aiPressure(ship)) : real);
    }
}
