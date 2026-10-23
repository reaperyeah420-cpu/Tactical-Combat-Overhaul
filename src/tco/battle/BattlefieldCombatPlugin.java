package tco.battle;

import java.util.List;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.TcoSettings;
import tco.armor.ArmorEffects;
import tco.armor.ArmorListener;
import tco.armor.Fires;

/** Battle-wide rules for every ship: weapon range, armor boost, armor bounce and penetration. */
public class BattlefieldCombatPlugin extends BaseEveryFrameCombatPlugin {

    private static final String DONE_KEY = "tco_range_done";
    private static final String ARMOR_BOOST_ID = "tco_armor_boost";

    private CombatEngineAPI engine;
    private final ArmorListener armor = new ArmorListener();

    public static final String MISSILE_DAMAGE_ID = "tco_missile_damage";

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        ArmorEffects.reset();
        Fires.reset();
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine != null && engine.isPaused()) amount = 0f;
        if (engine == null) return;
        for (ShipAPI ship : engine.getShips()) {
            if (ship.getCustomData().containsKey(DONE_KEY)) continue;
            ship.setCustomData(DONE_KEY, Boolean.TRUE);
            if (TcoSettings.weaponRangeMult != 1f) ship.addListener(new RangeListener());
            // Missiles hit harder (fighters' too)
            if (TcoSettings.missileDamageBonus > 0f) {
                ship.getMutableStats().getMissileWeaponDamageMult().modifyPercent(MISSILE_DAMAGE_ID, TcoSettings.missileDamageBonus);
            }
            if (TcoSettings.armorEnabled && !ship.isFighter()) ship.addListener(armor);
            if (TcoSettings.armorBoost != 1f && !ship.isFighter()) boostArmor(ship);
        }
        if (TcoSettings.armorEnabled) ArmorEffects.flush(engine);
        Fires.advance(engine, amount);
    }

    /**
     * Armor boost through the ship's stats, not the grid: grid cell maxima are fixed at build time and
     * saved as a share, so raised cells would stack across battles.
     */
    private static void boostArmor(ShipAPI ship) {
        float boost = TcoSettings.armorBoost;
        if (boost <= 0f) return;
        ship.getMutableStats().getEffectiveArmorBonus().modifyMult(ARMOR_BOOST_ID, boost);
        ship.getMutableStats().getArmorDamageTakenMult().modifyMult(ARMOR_BOOST_ID, 1f / boost);
    }
}
