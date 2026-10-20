package tco.battle;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.combat.WeaponAPI.AIHints;
import com.fs.starfarer.api.combat.listeners.WeaponRangeModifier;

import tco.TcoSettings;

/** Range multiplier for non-PD weapons; the engine uses it for missile flight time too. */
public class RangeListener implements WeaponRangeModifier {

    @Override
    public float getWeaponRangePercentMod(ShipAPI ship, WeaponAPI weapon) {
        return 0f;
    }

    @Override
    public float getWeaponRangeMultMod(ShipAPI ship, WeaponAPI weapon) {
        if (weapon == null || weapon.hasAIHint(AIHints.PD)) return 1f;
        return TcoSettings.weaponRangeMult;
    }

    @Override
    public float getWeaponRangeFlatMod(ShipAPI ship, WeaponAPI weapon) {
        return 0f;
    }
}
