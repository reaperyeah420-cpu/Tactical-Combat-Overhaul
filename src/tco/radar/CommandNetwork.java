package tco.radar;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipwideAIFlags;
import com.fs.starfarer.api.combat.ShipwideAIFlags.AIFlags;
import com.fs.starfarer.api.input.InputEventAPI;

import tco.AiUtil;
import tco.TcoSettings;
import tco.ai.AiAwareness;
import tco.battle.DeathThroes;
import tco.hullmods.CombatInformationCenter;

/**
 * What a command ship does for the ships around it (stage 3):
 * <ul>
 * <li>PD network: while its radar is on, friendly ships within pdNetworkRange get pdNetworkRangeBonus percent point
 * defence range (cued by its tracks).</li>
 * <li>Fighter direction: AI-flown carriers within fighterDirectionRange whose own AI has no strike target send their
 * fighters at the nearest enemy (not a fighter) the command ship's radar holds within the wing's reach
 * (CARRIER_FIGHTER_TARGET, which the game's fighter AI reads first). Directed targets are revealed to their side's AI
 * fog (SideSight, feedAi) so the fighters will engage them.</li>
 * </ul>
 * Third-party missile guidance is in RadarCombatPlugin (commandTrack). Nothing works while the side is decapitated.
 */
public class CommandNetwork extends BaseEveryFrameCombatPlugin {

    public static final String ENGINE_KEY = "tco_commandNetwork";
    private static final float INTERVAL = 0.5f;
    private static final float FLAG_SECONDS = 1.5f;
    private static final String PD_ID = "tco_pdnet";

    private CombatEngineAPI engine;
    private float timer;
    private final Set<ShipAPI> pdBoosted = new HashSet<ShipAPI>();
    // Carrier -> the target we gave its fighters
    private final Map<ShipAPI, ShipAPI> directed = new HashMap<ShipAPI, ShipAPI>();
    private final List<Set<ShipAPI>> directedTargets = new ArrayList<Set<ShipAPI>>();

    public CommandNetwork() {
        for (int i = 0; i < 2; i++) directedTargets.add(new HashSet<ShipAPI>());
    }

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
    }

    private static CommandNetwork get(CombatEngineAPI engine) {
        Object plugin = engine == null ? null : engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof CommandNetwork ? (CommandNetwork) plugin : null;
    }

    /** Enemy ships this side's carriers were sent at by a command ship (revealed to the side's AI fog). */
    public static Set<ShipAPI> directedTargets(CombatEngineAPI engine, int side) {
        CommandNetwork net = get(engine);
        return net == null || side < 0 || side > 1 ? new HashSet<ShipAPI>() : net.directedTargets.get(side);
    }

    public static float pdRange(ShipAPI command) {
        return CombatInformationCenter.level(command) == 2 ? TcoSettings.pdNetworkRangeCic : TcoSettings.pdNetworkRangeAux;
    }

    public static float fighterRange(ShipAPI command) {
        return CombatInformationCenter.level(command) == 2 ? TcoSettings.fighterDirectionRangeCic : TcoSettings.fighterDirectionRangeAux;
    }

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (engine == null || engine.isPaused() || !TcoSettings.radarEnabled) return;
        timer -= amount;
        if (timer > 0f) return;
        timer = INTERVAL;
        RadarCombatPlugin radar = RadarCombatPlugin.get(engine);
        if (radar == null) return;
        List<ShipAPI> commands = new ArrayList<ShipAPI>();
        for (ShipAPI ship : engine.getShips()) {
            int side = ship.getOwner();
            if (side < 0 || side > 1 || CombatInformationCenter.level(ship) == 0) continue;
            if (!ship.isAlive() || ship.isHulk() || DeathThroes.isDying(ship) || !radar.isEmitting(ship)) continue;
            if (AiAwareness.isDecapitated(engine, side)) continue;
            commands.add(ship);
        }
        pdNetwork(commands);
        fighterDirection(commands, radar);
        keepBack();
    }

    /** AI command ships (not the player's) back off from known enemies within cicKeepBackRange. */
    private void keepBack() {
        if (TcoSettings.cicKeepBackRange <= 0f) return;
        int player = engine.getPlayerShip() != null ? engine.getPlayerShip().getOwner() : 0;
        for (ShipAPI ship : engine.getShips()) {
            if (CombatInformationCenter.level(ship) == 0 || !ship.isAlive() || ship.isHulk() || ship.isRetreating()) continue;
            if (ship.getOwner() == player && !ship.isAlly()) continue;
            if (ship.getAIFlags() == null) continue;
            for (ShipAPI other : engine.getShips()) {
                if (other.getOwner() == ship.getOwner() || other.getOwner() < 0 || other.getOwner() > 1 || !other.isAlive()
                        || other.isHulk() || other.isFighter()) {
                    continue;
                }
                if (dist(ship.getLocation(), other.getLocation()) > TcoSettings.cicKeepBackRange) continue;
                if (!AiAwareness.knowsShip(engine, ship, other)) continue;
                ship.getAIFlags().setFlag(AIFlags.BACK_OFF, 1f);
                break;
            }
        }
    }

    private void pdNetwork(List<ShipAPI> commands) {
        Set<ShipAPI> now = new HashSet<ShipAPI>();
        if (TcoSettings.pdNetworkRangeBonus > 0f) {
            for (ShipAPI ship : engine.getShips()) {
                if (!ship.isAlive() || ship.isHulk() || ship.isFighter()) continue;
                for (ShipAPI command : commands) {
                    if (command.getOwner() == ship.getOwner() && dist(command.getLocation(), ship.getLocation()) <= pdRange(command)) {
                        now.add(ship);
                        break;
                    }
                }
            }
        }
        for (ShipAPI ship : now) {
            if (!pdBoosted.add(ship)) continue;
            ship.getMutableStats().getBeamPDWeaponRangeBonus().modifyPercent(PD_ID, TcoSettings.pdNetworkRangeBonus);
            ship.getMutableStats().getNonBeamPDWeaponRangeBonus().modifyPercent(PD_ID, TcoSettings.pdNetworkRangeBonus);
        }
        pdBoosted.removeIf(ship -> {
            if (now.contains(ship) && engine.isEntityInPlay(ship)) return false;
            ship.getMutableStats().getBeamPDWeaponRangeBonus().unmodify(PD_ID);
            ship.getMutableStats().getNonBeamPDWeaponRangeBonus().unmodify(PD_ID);
            return true;
        });
    }

    private void fighterDirection(List<ShipAPI> commands, RadarCombatPlugin radar) {
        for (Set<ShipAPI> s : directedTargets) s.clear();
        Set<ShipAPI> stillOurs = new HashSet<ShipAPI>();
        for (ShipAPI carrier : engine.getShips()) {
            if (carrier.isFighter() || !carrier.isAlive() || carrier.isHulk() || carrier.getOwner() < 0 || carrier.getOwner() > 1) continue;
            if (carrier.getNumFighterBays() <= 0 || carrier.isPullBackFighters() || AiUtil.playerFlying(engine, carrier)) continue;
            ShipwideAIFlags flags = carrier.getAIFlags();
            if (flags == null) continue;
            ShipAPI ours = directed.get(carrier);
            Object current = flags.hasFlag(AIFlags.CARRIER_FIGHTER_TARGET) ? flags.getCustom(AIFlags.CARRIER_FIGHTER_TARGET) : null;
            // The carrier's own AI picked a strike: leave it
            if (current != null && current != ours) continue;
            float reach = wingReach(carrier);
            if (reach <= 0f) continue;
            ShipAPI best = null;
            float bestD = Float.MAX_VALUE;
            for (ShipAPI command : commands) {
                if (command.getOwner() != carrier.getOwner()) continue;
                if (dist(command.getLocation(), carrier.getLocation()) > fighterRange(command)) continue;
                for (ShipAPI target : radar.heldBy(command)) {
                    if (target.getOwner() == carrier.getOwner() || target.isFighter() || !target.isAlive() || target.isHulk()) continue;
                    float d = dist(carrier.getLocation(), target.getLocation());
                    if (d <= reach && d < bestD) {
                        bestD = d;
                        best = target;
                    }
                }
            }
            if (best == null) continue;
            flags.setFlag(AIFlags.CARRIER_FIGHTER_TARGET, FLAG_SECONDS, best);
            directed.put(carrier, best);
            directedTargets.get(carrier.getOwner()).add(best);
            stillOurs.add(carrier);
        }
        directed.keySet().retainAll(stillOurs);
    }

    /** How far the carrier's wings reach. */
    private static float wingReach(ShipAPI carrier) {
        float reach = 0f;
        List<FighterWingAPI> wings = carrier.getAllWings();
        if (wings == null) return 0f;
        for (FighterWingAPI wing : wings) {
            if (wing != null) reach = Math.max(reach, wing.getRange());
        }
        return reach;
    }

    private static float dist(Vector2f a, Vector2f b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
