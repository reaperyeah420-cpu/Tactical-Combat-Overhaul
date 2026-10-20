package tco.rts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.CollisionClass;
import com.fs.starfarer.api.combat.CombatAssignmentType;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI;
import com.fs.starfarer.api.combat.CombatFleetManagerAPI.AssignmentInfo;
import com.fs.starfarer.api.combat.CombatTaskManagerAPI;
import com.fs.starfarer.api.combat.CombatUIAPI;
import com.fs.starfarer.api.combat.DeployedFleetMemberAPI;
import com.fs.starfarer.api.combat.MutableStat;
import com.fs.starfarer.api.combat.ShipAIConfig;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.ShipCommand;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.Fonts;
import com.fs.starfarer.api.ui.LabelAPI;

import tco.InputKeys;
import tco.Keybinds;
import tco.TcoSettings;
import tco.ai.SupportRoles;
import tco.ammo.AmmoCombatPlugin;
import tco.ammo.AmmoRules;
import tco.boarding.BoardingCombatPlugin;
import tco.fob.InertAI;
import tco.fob.SupplyFob;
import tco.hangar.HangarCombatPlugin;
import tco.hullmods.ExplosiveReactiveArmor;
import tco.hullmods.SecurityForge;
import tco.missile.IrCombatPlugin;
import tco.power.PowerCombatPlugin;
import tco.radar.RadarCombatPlugin;
import tco.repair.RepairCombatPlugin;
import tco.rescue.EscapePodPlugin;
import tco.rts.UnitState.Order;
import tco.rts.UnitState.OrderType;
import tco.tug.TowCombatPlugin;

/** RTS control mode for combat: free camera, selection, right-click orders. */
public class RtsCombatPlugin extends BaseEveryFrameCombatPlugin {

    private enum Mode { RTS, DIRECT, VANILLA }

    public static final String ENGINE_KEY = "tco_rts_plugin";

    private static RtsCombatPlugin instance(CombatEngineAPI engine) {
        if (engine == null) return null;
        Object plugin = engine.getCustomData().get(ENGINE_KEY);
        return plugin instanceof RtsCombatPlugin ? (RtsCombatPlugin) plugin : null;
    }

    /** The flagship in RTS mode, otherwise the flown ship. */
    public static boolean playerAboard(CombatEngineAPI engine, ShipAPI ship) {
        RtsCombatPlugin rts = instance(engine);
        if (rts == null || rts.mode != Mode.RTS) return ship == engine.getPlayerShip();
        return ship == rts.originalFlagship;
    }

    /** Normal controls, or no RTS plugin. */
    public static boolean isVanillaMode(CombatEngineAPI engine) {
        RtsCombatPlugin rts = instance(engine);
        return rts == null || rts.mode == Mode.VANILLA;
    }

    /** Back to RTS mode when the piloted ship is lost; true if it was piloted. */
    public static boolean leaveLostShip(CombatEngineAPI engine) {
        RtsCombatPlugin rts = instance(engine);
        if (rts == null || rts.mode != Mode.DIRECT) return false;
        rts.setMode(Mode.RTS);
        return true;
    }

    /** The player's shuttle reached a ship: it becomes the flagship. */
    public static void transferFlagship(CombatEngineAPI engine, ShipAPI ship, boolean pilot) {
        RtsCombatPlugin rts = instance(engine);
        if (rts == null) {
            engine.setPlayerShipExternal(ship);
            return;
        }
        rts.originalFlagship = ship;
        if (rts.mode == Mode.VANILLA) {
            engine.setPlayerShipExternal(ship);
        } else if (pilot && rts.mode == Mode.RTS && rts.units.containsKey(ship)) {
            rts.selected.clear();
            rts.selected.add(ship);
            rts.takeDirectControl();
        }
    }

    /** The ship's RTS unit, or null outside RTS and direct control. */
    public static UnitState unitOf(CombatEngineAPI engine, ShipAPI ship) {
        RtsCombatPlugin rts = instance(engine);
        if (rts == null || rts.mode == Mode.VANILLA) return null;
        return rts.units.get(ship);
    }

    /** The unit has an attack order on that target. */
    public static boolean attacking(CombatEngineAPI engine, ShipAPI ship, ShipAPI target) {
        if (engine == null) return false;
        Object plugin = engine.getCustomData().get(ENGINE_KEY);
        if (!(plugin instanceof RtsCombatPlugin)) return false;
        UnitState unit = ((RtsCombatPlugin) plugin).units.get(ship);
        return unit != null && unit.order == OrderType.ATTACK && unit.target == target;
    }

    /** RTS is flying this ship. */
    public static boolean controls(CombatEngineAPI engine, ShipAPI ship) {
        if (engine == null) return false;
        Object plugin = engine.getCustomData().get(ENGINE_KEY);
        if (!(plugin instanceof RtsCombatPlugin)) return false;
        RtsCombatPlugin rts = (RtsCombatPlugin) plugin;
        return rts.mode != Mode.VANILLA && rts.units.containsKey(ship);
    }

    private static final float CLICK_DRAG_THRESHOLD = 8f;
    private static final float OBJECTIVE_PICK_RADIUS = 200f;
    private static final float FORMATION_SPREAD_PER_SHIP = 250f;
    private static final float MAP_EDGE_MARGIN = 1000f;

    private CombatEngineAPI engine;
    private Mode mode = Mode.RTS;

    private final Map<ShipAPI, UnitState> units = new LinkedHashMap<ShipAPI, UnitState>();
    private final Set<ShipAPI> selected = new LinkedHashSet<ShipAPI>();
    private ShipAPI directShip;
    private ShipAPI originalFlagship;

    private final Vector2f camCenter = new Vector2f();
    private float camZoom = 1f;
    private float camZoomTarget = 1f;
    private boolean cameraTaken = false;
    private long lastNanos = 0L;

    private boolean camValid = false;

    private boolean dragging = false;
    private float dragStartX;
    private float dragStartY;

    private boolean panning = false;
    private float panLastX;
    private float panLastY;

    private static final float ORDER_DRAG_THRESHOLD = 14f;
    private boolean orderDragging = false;
    private float orderStartX;
    private float orderStartY;
    private final Vector2f orderStartWorld = new Vector2f();

    // invisible pod holding the engine's player-ship role in RTS mode
    private ShipAPI pod;
    private int podSpawns = 0;

    // helper with an empty weapon group, clears the engine's leftover weapon arcs
    private ShipAPI arcSink;
    private int arcSinkSpawns = 0;
    private float arcSinkHold = 0f;
    private static final float ARC_SINK_HOLD = 1f;
    private static final String ARC_SINK_VARIANT = "hound_Standard";

    private int refanDelay = 0;

    private final Map<Integer, List<ShipAPI>> controlGroups = new HashMap<Integer, List<ShipAPI>>();
    private int lastRecalledGroup = -1;
    private long lastRecallNanos = 0L;

    private static final int PANEL_MAX_ROWS = 8;
    private static final float PANEL_ROW_HEIGHT = 16f;
    private static final float PANEL_X = 16f;
    private static final float PANEL_Y = 16f;
    private static final float PANEL_WIDTH = 430f;
    private final LabelAPI[] panelNames = new LabelAPI[PANEL_MAX_ROWS + 1];
    private final LabelAPI[] panelStatus = new LabelAPI[PANEL_MAX_ROWS + 1];
    private final String[] panelNameText = new String[PANEL_MAX_ROWS + 1];
    private final String[] panelStatusText = new String[PANEL_MAX_ROWS + 1];

    private static final int CARD_AMMO_LINES = 5;
    private static final float CARD_ARMOR_BOX = 100f;
    private final LabelAPI[] cardLabels = new LabelAPI[12 + CARD_AMMO_LINES * 2];
    private final String[] cardText = new String[12 + CARD_AMMO_LINES * 2];

    private float behaviourTimer = 0f;
    private final List<Formation> formations = new ArrayList<Formation>();
    private static final String FORMATION_SPEED_ID = "tco_formation";
    private boolean saveVisible = false;
    private float saveX;
    private float saveY;
    private static final float SAVE_W = 150f;
    private static final float SAVE_H = 20f;
    private LabelAPI saveLabel;
    private final Map<String, SpriteAPI> cardSprites = new HashMap<String, SpriteAPI>();
    private final Map<String, float[]> cardSpriteSizes = new HashMap<String, float[]>();

    private SpriteAPI btnRts;
    private SpriteAPI btnDirect;
    private SpriteAPI btnVanilla;

    @Override
    public void init(CombatEngineAPI engine) {
        this.engine = engine;
        TcoSettings.load();
        if (engine != null) engine.getCustomData().put(ENGINE_KEY, this);
        mode = TcoSettings.rtsDefault ? Mode.RTS : Mode.VANILLA;
        try {
            btnRts = Global.getSettings().getSprite("tco_ui", "btn_rts");
            btnDirect = Global.getSettings().getSprite("tco_ui", "btn_direct");
            btnVanilla = Global.getSettings().getSprite("tco_ui", "btn_vanilla");
        } catch (RuntimeException e) {
            Global.getLogger(RtsCombatPlugin.class).warn("TCO: mode button sprites missing", e);
        }
    }

    private boolean inBattle() {
        return engine != null
                && Global.getCurrentState() == GameState.COMBAT
                && Global.getCombatEngine() == engine
                && engine.getCombatUI() != null;
    }

    private boolean uiBusy() {
        CombatUIAPI ui = engine.getCombatUI();
        return ui.isShowingCommandUI() || ui.isShowingDeploymentDialog()
                || engine.isUIShowingDialog() || engine.isCombatOver();
    }

    // ------------------------------------------------------------------ input

    @Override
    public void processInputPreCoreControls(float amount, List<InputEventAPI> events) {
        // Extra mouse buttons bypass the game's events
        List<Integer> mouseButtons = InputKeys.mousePresses();
        if (!inBattle() || uiBusy()) return;
        boolean shift = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        boolean ctrl = Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
        for (int button : mouseButtons) {
            if (rebinding != null) {
                finishRebind(button);
                continue;
            }
            if (globalPress(button)) continue;
            if (mode == Mode.RTS) rtsPress(button, shift, ctrl);
        }

        for (InputEventAPI event : events) {
            if (event.isConsumed()) continue;

            // Awaiting a new key for the controls sheet
            if (rebinding != null && event.isKeyDownEvent() && !event.isRepeat()) {
                finishRebind(event.getEventValue());
                event.consume();
                continue;
            }

            if (event.isKeyDownEvent() && !event.isRepeat() && globalPress(event.getEventValue())) {
                event.consume();
                continue;
            }
            if (event.isLMBDownEvent() && isOverButton(mouseX(), mouseY())) {
                setMode(mode == Mode.VANILLA ? Mode.RTS : Mode.VANILLA);
                event.consume();
                continue;
            }

            if (mode == Mode.RTS) handleRtsEvent(event);
        }

        if (mode == Mode.RTS) engine.getCombatUI().setDisablePlayerShipControlOneFrame(true);
    }

    private boolean globalPress(int key) {
        if (key == TcoSettings.keyToggleMode) {
            setMode(mode == Mode.VANILLA ? Mode.RTS : Mode.VANILLA);
            return true;
        }
        // Direct control key also leaves normal controls
        if (key == TcoSettings.keyDirectControl && (mode == Mode.DIRECT || mode == Mode.VANILLA)) {
            setMode(Mode.RTS);
            return true;
        }
        if (key == TcoSettings.keyFlares && mode != Mode.RTS && engine.getPlayerShip() != null) {
            List<ShipAPI> piloted = new ArrayList<ShipAPI>();
            piloted.add(engine.getPlayerShip());
            dropFlares(piloted);
            return true;
        }
        if (key == TcoSettings.keyRadarUnlock && mode != Mode.RTS && engine.getPlayerShip() != null && TcoSettings.radarEnabled) {
            boolean had = RadarCombatPlugin.unlockRadar(engine, engine.getPlayerShip());
            message(had ? "Radar lock released." : "No radar lock to release.");
            return true;
        }
        if (key == TcoSettings.keyJammer && mode != Mode.RTS && engine.getPlayerShip() != null) {
            List<ShipAPI> piloted = new ArrayList<ShipAPI>();
            piloted.add(engine.getPlayerShip());
            cycleJammers(piloted, "This ship");
            return true;
        }
        if (key == TcoSettings.keyRadarHold && mode != Mode.RTS && engine.getPlayerShip() != null) {
            List<ShipAPI> piloted = new ArrayList<ShipAPI>();
            piloted.add(engine.getPlayerShip());
            toggleMissileHold(piloted, "This ship");
            return true;
        }
        if (key == TcoSettings.keyRadar && mode != Mode.RTS && engine.getPlayerShip() != null) {
            List<ShipAPI> piloted = new ArrayList<ShipAPI>();
            piloted.add(engine.getPlayerShip());
            toggleRadar(piloted, "This ship");
            return true;
        }
        if (key == TcoSettings.keyHangar && mode == Mode.DIRECT && engine.getPlayerShip() != null) {
            List<ShipAPI> piloted = new ArrayList<ShipAPI>();
            piloted.add(engine.getPlayerShip());
            toggleHangarHold(piloted, "This ship");
            return true;
        }
        return false;
    }

    private boolean rtsPress(int key, boolean shift, boolean ctrl) {
        if (key == TcoSettings.keyDirectControl) takeDirectControl();
        else if (key == TcoSettings.keyToggleShipAI) toggleAiForSelection();
        else if (key == TcoSettings.keyStop) stopSelection();
        else if (key == TcoSettings.keyRetreat) retreatSelection();
        else if (key == TcoSettings.keyHoldFire) toggleHoldFire();
        else if (key == TcoSettings.keyBoard) boardKey(shift, ctrl);
        else if (key == TcoSettings.keyHangar) toggleHangarHold(selected, "Selected ships");
        else if (key == TcoSettings.keyFlares) dropFlares(new ArrayList<ShipAPI>(selected));
        else if (key == TcoSettings.keyRadar) toggleRadar(new ArrayList<ShipAPI>(selected), "Selected ships");
        else if (key == TcoSettings.keyRadarHold) toggleMissileHold(new ArrayList<ShipAPI>(selected), "Selected ships");
        else if (key == TcoSettings.keyJammer) cycleJammers(new ArrayList<ShipAPI>(selected), "Selected ships");
        else if (key == TcoSettings.keyStance) cycleStance();
        else if (key == TcoSettings.keyFormation) cycleFormation();
        else if (isGroupKey(key)) {
            if (ctrl) saveControlGroup(key);
            else recallControlGroup(key, shift);
        } else {
            return false;
        }
        return true;
    }

    private void handleRtsEvent(InputEventAPI event) {
        if (event.isKeyboardEvent()) {
            int key = event.getEventValue();
            if (event.isKeyDownEvent() && !event.isRepeat()) rtsPress(key, event.isShiftDown(), event.isCtrlDown());
            if (isRtsKey(key)) event.consume();
            return;
        }

        // Middle button
        if (event.isMouseDownEvent() && event.getEventValue() == 2) {
            panning = true;
            panLastX = mouseX();
            panLastY = mouseY();
            event.consume();
            return;
        }
        if (event.isMouseUpEvent() && event.getEventValue() == 2) {
            panning = false;
            event.consume();
            return;
        }

        if (event.isMouseScrollEvent()) {
            float step = event.getEventValue() > 0 ? 1f / 1.15f : 1.15f;
            camZoomTarget = RtsMath.clamp(camZoomTarget * step, TcoSettings.zoomMin, TcoSettings.zoomMax);
            event.consume();
        } else if (event.isLMBDownEvent() && helpOpen) {
            clickHelp(mouseX(), mouseY());
            event.consume();
        } else if (event.isLMBDownEvent() && stanceMenuOpen && commandAt(mouseX(), mouseY()) != Command.STANCE) {
            Stance picked = stanceItemAt(mouseX(), mouseY());
            stanceMenuOpen = false;
            if (picked != null) setStance(picked);
            event.consume();
        } else if (event.isRMBDownEvent() && stanceMenuOpen) {
            stanceMenuOpen = false;
            event.consume();
        } else if (event.isLMBDownEvent() && commandAt(mouseX(), mouseY()) != null) {
            runCommand(commandAt(mouseX(), mouseY()));
            event.consume();
        } else if (event.isLMBDownEvent() && pending != null) {
            finishPending(event.isShiftDown());
            event.consume();
        } else if (event.isRMBDownEvent() && pending != null) {
            pending = null;
            event.consume();
        } else if (event.isLMBDownEvent() && boardButtonAt(mouseX(), mouseY()) != null) {
            BoardingCombatPlugin boarding = BoardingCombatPlugin.get(engine);
            String result = boarding == null ? null : boarding.setOrder(boardBtnShip, boardButtonAt(mouseX(), mouseY()));
            if (result != null) message(result);
            event.consume();
        } else if (event.isLMBDownEvent() && saveVisible && mouseX() >= saveX && mouseX() <= saveX + SAVE_W
                && mouseY() >= saveY && mouseY() <= saveY + SAVE_H) {
            saveStances();
            event.consume();
        } else if (event.isLMBDownEvent()) {
            dragging = true;
            dragStartX = mouseX();
            dragStartY = mouseY();
            event.consume();
        } else if (event.isLMBUpEvent()) {
            if (dragging) finishSelection(event.isShiftDown());
            dragging = false;
            event.consume();
        } else if (event.isRMBDownEvent()) {
            ViewportAPI viewport = engine.getViewport();
            orderDragging = true;
            orderStartX = mouseX();
            orderStartY = mouseY();
            orderStartWorld.set(viewport.convertScreenXToWorldX(orderStartX),
                    viewport.convertScreenYToWorldY(orderStartY));
            event.consume();
        } else if (event.isRMBUpEvent()) {
            if (orderDragging) finishOrder(event.isShiftDown(), event.isAltDown(), event.isCtrlDown());
            orderDragging = false;
            event.consume();
        }
    }

    private void cycleJammers(List<ShipAPI> ships, String who) {
        if (!TcoSettings.radarEnabled || !TcoSettings.jamEnabled || ships.isEmpty()) return;
        String mode = null;
        for (ShipAPI ship : ships) {
            String m = RadarCombatPlugin.cycleJammer(engine, ship);
            if (m != null) mode = m;
        }
        message(mode == null ? who + ": no ECM Package, nothing to jam with." : who + ": noise jammer " + mode + ".");
    }

    private void toggleMissileHold(List<ShipAPI> ships, String who) {
        if (!TcoSettings.radarEnabled || ships.isEmpty()) return;
        List<ShipAPI> armed = new ArrayList<ShipAPI>();
        for (ShipAPI ship : ships) {
            if (RadarCombatPlugin.hasRadarMissiles(ship)) armed.add(ship);
        }
        if (armed.isEmpty()) {
            message(who + ": no radar missiles.");
            return;
        }
        boolean anyHeld = false;
        for (ShipAPI ship : armed) {
            if (RadarCombatPlugin.isHoldingMissiles(engine, ship)) anyHeld = true;
        }
        for (ShipAPI ship : armed) RadarCombatPlugin.setHoldingMissiles(engine, ship, !anyHeld);
        message(who + ": radar missiles " + (anyHeld ? "released, firing at will." : "held.")
                + " (" + InputKeys.name(TcoSettings.keyRadarHold) + " to " + (anyHeld ? "hold" : "release") + ")");
    }

    private void toggleRadar(List<ShipAPI> ships, String who) {
        if (!TcoSettings.radarEnabled || ships.isEmpty()) return;
        boolean allOff = true;
        for (ShipAPI ship : ships) {
            if (!RadarCombatPlugin.isSilent(engine, ship)) allOff = false;
        }
        for (ShipAPI ship : ships) {
            boolean off = RadarCombatPlugin.isSilent(engine, ship);
            if (allOff == off) RadarCombatPlugin.toggleRadar(engine, ship);
        }
        message(who + ": radar " + (allOff ? "on." : "off (emission control). Radar missiles need a datalink track."));
    }

    private void dropFlares(List<ShipAPI> ships) {
        if (!TcoSettings.flaresEnabled || ships.isEmpty()) return;
        IrCombatPlugin.dispense(engine, ships);
        for (ShipAPI ship : ships) {
            if (IrCombatPlugin.flaresLeft(engine, ship) == 0 && IrCombatPlugin.reloadsLeft(engine, ship) <= 0) {
                engine.addFloatingText(ship.getLocation(), "Out of flares", 20f, new Color(255, 170, 90), ship, 0.5f, 1f);
            }
        }
    }

    private boolean isRtsKey(int key) {
        return key == Keyboard.KEY_W || key == Keyboard.KEY_A || key == Keyboard.KEY_S || key == Keyboard.KEY_D
                || key == Keyboard.KEY_UP || key == Keyboard.KEY_DOWN
                || key == Keyboard.KEY_LEFT || key == Keyboard.KEY_RIGHT
                || key == TcoSettings.keyDirectControl || key == TcoSettings.keyToggleShipAI
                || key == TcoSettings.keyStop || key == TcoSettings.keyRetreat
                || key == TcoSettings.keyHoldFire || key == TcoSettings.keyBoard
                || key == TcoSettings.keyHangar || key == TcoSettings.keyFlares || key == TcoSettings.keyRadar || key == TcoSettings.keyRadarHold || key == TcoSettings.keyJammer || key == TcoSettings.keyStance
                || key == TcoSettings.keyFormation || isGroupKey(key);
    }

    private boolean isGroupKey(int key) {
        return key >= Keyboard.KEY_1 && key <= Keyboard.KEY_9;
    }

    private void saveControlGroup(int key) {
        int number = key - Keyboard.KEY_1 + 1;
        if (selected.isEmpty()) {
            controlGroups.remove(number);
            return;
        }
        controlGroups.put(number, new ArrayList<ShipAPI>(selected));
        message("Group " + number + " set: " + selected.size() + (selected.size() == 1 ? " ship." : " ships."));
    }

    private void recallControlGroup(int key, boolean additive) {
        int number = key - Keyboard.KEY_1 + 1;
        List<ShipAPI> group = controlGroups.get(number);
        if (group == null) return;

        if (!additive) selected.clear();
        float cx = 0f;
        float cy = 0f;
        int count = 0;
        for (ShipAPI ship : group) {
            if (!units.containsKey(ship)) continue;
            selected.add(ship);
            cx += ship.getLocation().x;
            cy += ship.getLocation().y;
            count++;
        }

        long now = System.nanoTime();
        if (count > 0 && lastRecalledGroup == number && now - lastRecallNanos < 400000000L) {
            camCenter.set(cx / count, cy / count);
        }
        lastRecalledGroup = number;
        lastRecallNanos = now;
    }

    /**
     * B: over a ship our marines are fighting aboard (or with Ctrl, or no boarder selected) cycles orders;
     * otherwise sends the selected boarders.
     */
    private void boardKey(boolean shift, boolean ctrl) {
        BoardingCombatPlugin boarding = BoardingCombatPlugin.get(engine);
        if (boarding != null) {
            ViewportAPI viewport = engine.getViewport();
            ShipAPI target = shipAt(viewport.convertScreenXToWorldX(mouseX()), viewport.convertScreenYToWorldY(mouseY()), false);
            boolean boarders = false;
            for (ShipAPI ship : selected) {
                if (BoardingCombatPlugin.hasBay(ship)) boarders = true;
            }
            if (target != null && (ctrl || !boarders)) {
                String result = boarding.cycleOrder(target);
                if (result != null) {
                    message(result);
                    return;
                }
            }
        }
        boardSelection(shift);
    }

    private void boardSelection(boolean append) {
        if (selected.isEmpty()) return;
        BoardingCombatPlugin boarding = BoardingCombatPlugin.get(engine);
        if (boarding == null) return;
        ViewportAPI viewport = engine.getViewport();
        ShipAPI target = shipAt(viewport.convertScreenXToWorldX(mouseX()),
                viewport.convertScreenYToWorldY(mouseY()), false);
        if (target == null || !boarding.isBoardable(target, 0)) {
            message("Point at an enemy ship and press " + InputKeys.name(TcoSettings.keyBoard) + " to board it.");
            return;
        }
        int count = 0;
        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit == null || !BoardingCombatPlugin.hasBay(ship)) continue;
            unit.give(Order.board(target), append);
            count++;
        }
        if (count == 0) message("None of the selected ships has a Boarding Shuttle Bay.");
        else message(count + (count == 1 ? " ship" : " ships") + " ordered to board " + target.getName() + ".");
    }

    private void tickBoardOrder(UnitState unit) {
        ShipAPI ship = unit.ship;
        ShipAPI target = unit.target;
        BoardingCombatPlugin boarding = BoardingCombatPlugin.get(engine);
        if (boarding == null || target == null || !boarding.isBoardable(target, ship.getOwner())) {
            unit.completeOrder();
            return;
        }
        float gap = RtsMath.distance(ship.getLocation(), target.getLocation())
                - ship.getCollisionRadius() - target.getCollisionRadius();
        if (gap > TcoSettings.boardingLaunchRange || boarding.cooldownOf(ship) > 0f) return;
        message(boarding.launchWave(ship, target));
        unit.completeOrder();
    }

    private void retreatSelection() {
        int count = 0;
        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit == null || unit.order == OrderType.RETREAT) continue;
            unit.give(Order.retreat(), false);
            count++;
        }
        if (count > 0) message("Retreat ordered for " + count + (count == 1 ? " ship." : " ships."));
    }

    private void toggleHoldFire() {
        if (selected.isEmpty()) return;
        boolean anyFiring = false;
        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit != null && !unit.holdFire) anyFiring = true;
        }
        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit == null) continue;
            unit.holdFire = anyFiring;
            ship.setHoldFire(anyFiring);
        }
        message(anyFiring ? "Selected ships holding fire." : "Selected ships free to fire.");
    }

    private void toggleHangarHold(Iterable<ShipAPI> ships, String who) {
        List<ShipAPI> carriers = new ArrayList<ShipAPI>();
        boolean anyOut = false;
        for (ShipAPI ship : ships) {
            if (!HangarCombatPlugin.hasCraft(ship)) continue;
            carriers.add(ship);
            if (!HangarCombatPlugin.isHeld(ship)) anyOut = true;
        }
        if (carriers.isEmpty()) {
            message(who + ": no fighters, shuttles or drones aboard.");
            return;
        }
        for (ShipAPI ship : carriers) HangarCombatPlugin.setHeld(ship, anyOut);
        message(anyOut ? who + ": keeping craft in the hangar." : who + ": launching craft again.");
    }

    private float mouseX() {
        return Global.getSettings().getMouseX();
    }

    private float mouseY() {
        return Global.getSettings().getMouseY();
    }

    // ------------------------------------------------------------------ mode changes

    private void setMode(Mode newMode) {
        if (newMode == mode) return;
        Mode old = mode;
        mode = newMode;
        dragging = false;
        orderDragging = false;
        panning = false;

        if (newMode == Mode.VANILLA) {
            ShipAPI player = engine.getPlayerShip();
            if ((pod != null && player == pod) || (arcSink != null && player == arcSink)) {
                ShipAPI flagship = pickFlagship();
                if (flagship != null) {
                    engine.setPlayerShipExternal(flagship);
                    player = flagship;
                }
            }
            for (UnitState unit : units.values()) {
                releaseToVanilla(unit, unit.ship == player);
            }
            selected.clear();
            directShip = null;
            releaseCamera();
            camValid = false;
            message("Normal controls. " + InputKeys.name(TcoSettings.keyToggleMode) + " or "
                    + InputKeys.name(TcoSettings.keyDirectControl) + " for RTS mode.");
            if (engine.isUIAutopilotOn()) {
                message("Autopilot is on, so the ship flies itself. Press any movement key or the autopilot button to take the helm.");
            }
        } else if (newMode == Mode.RTS) {
            // Re-issue a retreat the piloted ship was carrying out
            UnitState piloted = directShip == null ? null : units.get(directShip);
            if (piloted != null && (piloted.order == OrderType.RETREAT || directShip.isRetreating())) {
                piloted.give(Order.retreat(), false);
            }
            // Give the player-ship role back to the flagship (the engine draws player-only overlays on it)
            if (pod == null && directShip != null && originalFlagship != null && originalFlagship != directShip
                    && originalFlagship.isAlive() && !originalFlagship.isHulk()
                    && engine.isEntityInPlay(originalFlagship)
                    && engine.getPlayerShip() == directShip) {
                engine.setPlayerShipExternal(originalFlagship);
            }
            directShip = null;
            if (old == Mode.VANILLA) {
                message("RTS mode. " + InputKeys.name(TcoSettings.keyToggleMode) + " for normal controls.");
            } else {
                message("Back to RTS mode.");
            }
        } else {
            releaseCamera();
            camValid = false;
        }
    }

    private ShipAPI pickFlagship() {
        if (originalFlagship != null && units.containsKey(originalFlagship)) return originalFlagship;
        for (ShipAPI ship : units.keySet()) return ship;
        return null;
    }

    /**
     * Parks the player-ship role on an invisible pod in RTS mode, as the engine does when the flagship is
     * lost.
     */
    private void usePod(float realDt) {
        if (!TcoSettings.commandPod) return;
        if (pod != null && (pod.isHulk() || !pod.isAlive() || !engine.isEntityInPlay(pod))) pod = null;
        if (arcSink != null && (arcSink.isHulk() || !arcSink.isAlive() || !engine.isEntityInPlay(arcSink))) {
            arcSink = null;
        }

        ShipAPI player = engine.getPlayerShip();
        boolean playerIsUnit = player != null && units.containsKey(player);
        if (pod == null) {
            if (!playerIsUnit || podSpawns >= 3) return;
            podSpawns++;
            pod = spawnHelper("shuttlepod_xfer", camCenter);
            if (pod == null) {
                podSpawns = 3;
                return;
            }
        }
        if (arcSink == null && arcSinkSpawns < 3) {
            arcSinkSpawns++;
            arcSink = spawnHelper(ARC_SINK_VARIANT, parkingSpot());
            if (arcSink != null) {
                arcSink.setCustomData(AmmoRules.SHUTTLE_KEY, Boolean.TRUE);
            }
        }

        if (arcSink != null) {
            arcSink.getLocation().set(parkingSpot());
            arcSink.getVelocity().set(0f, 0f);
            arcSink.setBeingIgnored(true);
            arcSink.setHitpoints(arcSink.getMaxHitpoints());
            for (WeaponAPI weapon : arcSink.getAllWeapons()) {
                if (weapon.getMaxAmmo() != 1) weapon.setMaxAmmo(1);
                if (weapon.getAmmo() != 0) weapon.setAmmo(0);
            }
            // Needs a selected group before becoming the player ship
            if (arcSink.getSelectedGroupAPI() == null && player != arcSink) {
                arcSink.giveCommand(ShipCommand.SELECT_GROUP, null, 0);
            }
        }

        if (playerIsUnit) {
            if (arcSink != null && arcSink.getSelectedGroupAPI() != null) {
                engine.setPlayerShipExternal(arcSink);
                arcSinkHold = ARC_SINK_HOLD;
            } else {
                engine.setPlayerShipExternal(pod);
            }
        } else if (arcSink != null && player == arcSink) {
            // Long enough for the arc display to switch
            arcSinkHold -= realDt;
            if (arcSinkHold <= 0f) engine.setPlayerShipExternal(pod);
        }

        // Park away from the camera (the game draws a tooltip on the player ship)
        Vector2f park = parkingSpot();
        pod.getLocation().set(park.x + 300f, park.y);
        pod.getVelocity().set(0f, 0f);
        pod.setBeingIgnored(true);
        pod.setHitpoints(pod.getMaxHitpoints());
    }

    private ShipAPI spawnHelper(String variant, Vector2f at) {
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(0);
            boolean quiet = fleet.isSuppressDeploymentMessages();
            fleet.setSuppressDeploymentMessages(true);
            ShipAPI spawned;
            try {
                spawned = fleet.spawnShipOrWing(variant, new Vector2f(at), 90f);
            } finally {
                fleet.setSuppressDeploymentMessages(quiet);
            }
            fleet.removeDeployed(spawned, false);
            spawned.setCustomData("tco_ammo_done", Boolean.TRUE);
            spawned.setShipAI(new InertAI());
            spawned.setCollisionClass(CollisionClass.NONE);
            spawned.setExtraAlphaMult(0f);
            spawned.setApplyExtraAlphaToEngines(true);
            spawned.setBeingIgnored(true);
            spawned.setInvalidTransferCommandTarget(true);
            // Drones are skipped by the tactical map and overlay
            spawned.setDrone(true);
            // No friend-or-foe ring
            spawned.setForceHideFFOverlay(true);
            // Never a radar contact
            spawned.setCustomData(RadarCombatPlugin.HELPER_KEY, Boolean.TRUE);
            return spawned;
        } catch (RuntimeException e) {
            Global.getLogger(RtsCombatPlugin.class).warn("TCO: could not create helper ship " + variant, e);
            return null;
        }
    }

    private Vector2f parkingSpot() {
        float sum = 0f;
        for (ShipAPI ship : units.keySet()) sum += ship.getLocation().y;
        float sign = units.isEmpty() || sum / units.size() <= 0f ? -1f : 1f;
        if (parkingSign == 0f) parkingSign = sign;
        return new Vector2f(0f, parkingSign * Math.max(100f, engine.getMapHeight() / 2f - 800f));
    }

    private float parkingSign = 0f;

    private void releaseToVanilla(UnitState unit, boolean playerFlown) {
        if (unit.aiInstalled) {
            unit.ship.resetDefaultAI();
            unit.aiInstalled = false;
        }
        unit.manualVanillaAi = null;
        if (playerFlown) unit.ai.restoreAutofire();
        else unit.savedAutofire = null;
    }

    private void takeDirectControl() {
        if (selected.isEmpty()) {
            message("Select a ship first, then press " + InputKeys.name(TcoSettings.keyDirectControl)
                    + " to pilot it.");
            return;
        }
        ShipAPI ship = selected.iterator().next();
        UnitState unit = units.get(ship);
        if (unit == null || !ship.isAlive()) return;

        releaseToVanilla(unit, true);
        if (engine.getPlayerShip() != ship) engine.setPlayerShipExternal(ship);
        directShip = ship;
        setMode(Mode.DIRECT);
        message("Piloting " + ship.getName() + ". "
                + InputKeys.name(TcoSettings.keyDirectControl) + " to return to RTS mode.");
    }

    private void toggleAiForSelection() {
        if (selected.isEmpty()) return;
        boolean anyPassive = false;
        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit != null && !unit.vanillaAi && !unit.isEngaging()) anyPassive = true;
        }
        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit == null) continue;
            // Drop the old order so it can't resume
            unit.hold();
            unit.vanillaAi = anyPassive;
            if (!anyPassive) unit.manualVanillaAi = null;
        }
        message(anyPassive ? "Ship AI on for selected ships." : "Ship AI off for selected ships.");
    }

    private void stopSelection() {
        BoardingCombatPlugin boarding = BoardingCombatPlugin.get(engine);
        int recalled = 0;
        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit != null) unit.hold();
            if (boarding != null) recalled += boarding.recall(ship);
        }
        if (recalled > 0) message("Boarding shuttles recalled: " + recalled + " marines coming back.");
    }

    private void message(String text) {
        CombatUIAPI ui = engine.getCombatUI();
        if (ui != null) ui.addMessage(0, text);
    }

    // ------------------------------------------------------------------ selection and orders

    private void finishSelection(boolean additive) {
        ViewportAPI viewport = engine.getViewport();
        float mx = mouseX();
        float my = mouseY();
        float moved = Math.abs(mx - dragStartX) + Math.abs(my - dragStartY);

        if (moved < CLICK_DRAG_THRESHOLD) {
            ShipAPI ship = shipAt(viewport.convertScreenXToWorldX(mx), viewport.convertScreenYToWorldY(my), true);
            if (ship == null) {
                // Other ships: show their card
                ShipAPI other = shipAt(viewport.convertScreenXToWorldX(mx), viewport.convertScreenYToWorldY(my), false);
                if (inspectable(other)) {
                    selected.clear();
                    boardFocus = other;
                    return;
                }
            }
            boardFocus = null;
            if (!additive) selected.clear();
            if (ship != null) {
                if (additive && selected.contains(ship)) selected.remove(ship);
                else selected.add(ship);
            }
            return;
        }

        float x1 = viewport.convertScreenXToWorldX(Math.min(mx, dragStartX));
        float x2 = viewport.convertScreenXToWorldX(Math.max(mx, dragStartX));
        float ya = viewport.convertScreenYToWorldY(my);
        float yb = viewport.convertScreenYToWorldY(dragStartY);
        float y1 = Math.min(ya, yb);
        float y2 = Math.max(ya, yb);

        boardFocus = null;
        if (!additive) selected.clear();
        for (ShipAPI ship : units.keySet()) {
            Vector2f loc = ship.getLocation();
            if (loc.x >= x1 && loc.x <= x2 && loc.y >= y1 && loc.y <= y2) selected.add(ship);
        }
    }

    private ShipAPI shipAt(float x, float y, boolean ownOnly) {
        Vector2f point = new Vector2f(x, y);
        float slack = 30f * camZoom;
        ShipAPI best = null;
        float bestDist = Float.MAX_VALUE;
        for (ShipAPI ship : engine.getShips()) {
            if (ownOnly) {
                if (!units.containsKey(ship)) continue;
            } else if (ship.isHulk() || !ship.isAlive() || ship.isFighter() || ship.isDrone()
                    || ship.isShuttlePod() || ship.getOwner() == 100 || FogOfWar.isHidden(ship)) {
                continue;
            }
            float dist = RtsMath.distance(point, ship.getLocation());
            if (dist <= ship.getCollisionRadius() + slack && dist < bestDist) {
                best = ship;
                bestDist = dist;
            }
        }
        return best;
    }

    private BattleObjectiveAPI objectiveAt(float x, float y) {
        Vector2f point = new Vector2f(x, y);
        BattleObjectiveAPI best = null;
        float bestDist = OBJECTIVE_PICK_RADIUS;
        for (BattleObjectiveAPI objective : engine.getObjectives()) {
            float dist = RtsMath.distance(point, objective.getLocation());
            if (dist < bestDist) {
                best = objective;
                bestDist = dist;
            }
        }
        return best;
    }

    private void finishOrder(boolean shift, boolean alt, boolean ctrl) {
        if (selected.isEmpty()) return;
        float moved = Math.abs(mouseX() - orderStartX) + Math.abs(mouseY() - orderStartY);
        if (moved < ORDER_DRAG_THRESHOLD) {
            // Ctrl: attack-move
            issueOrder(orderStartWorld.x, orderStartWorld.y, shift, null, false, ctrl);
            return;
        }

        ViewportAPI viewport = engine.getViewport();
        Vector2f end = new Vector2f(viewport.convertScreenXToWorldX(mouseX()),
                viewport.convertScreenYToWorldY(mouseY()));
        float facing = RtsMath.angle(orderStartWorld, end);

        // Drag from a selected ship (or Alt): turn in place
        ShipAPI pressed = shipAt(orderStartWorld.x, orderStartWorld.y, true);
        if (alt || (pressed != null && selected.contains(pressed))) {
            for (ShipAPI ship : selected) {
                UnitState unit = units.get(ship);
                if (unit != null) unit.give(Order.face(facing), false);
            }
            return;
        }
        // Shift + drag: turn first and hold the facing
        issueOrder(orderStartWorld.x, orderStartWorld.y, false, facing, shift, false);
    }

    private void issueOrder(float x, float y, boolean append, Float facing, boolean strafe, boolean attackMove) {
        if (selected.isEmpty()) return;

        List<UnitState> group = new ArrayList<UnitState>();
        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit == null) continue;
            // Followers keep their slot unless ordered alone
            if (unit.formation != null && unit.formation.leader != unit) {
                if (selected.contains(unit.formation.leader.ship)) continue;
                leaveFormation(unit);
            }
            group.add(unit);
        }
        if (group.isEmpty()) return;

        // A facing drag is always a move
        ShipAPI target = facing == null ? shipAt(x, y, false) : null;
        if (target != null) {
            boolean enemy = target.getOwner() != 0;
            boolean stranded = !enemy && TowCombatPlugin.isImmobilized(engine, target);
            for (UnitState unit : group) {
                if (unit.ship == target) continue;
                // Tugs tow stranded ships; others escort
                if (stranded && TowCombatPlugin.hasTug(unit.ship)) unit.give(Order.tow(target), append);
                else unit.give(enemy ? Order.attack(target) : Order.escort(target), append);
            }
            return;
        }

        BattleObjectiveAPI objective = facing == null ? objectiveAt(x, y) : null;
        if (objective != null) {
            for (UnitState unit : group) unit.give(Order.capture(objective), append);
            return;
        }

        if (group.size() == 1) {
            group.get(0).give(attackMove ? Order.attackMove(x, y) : Order.move(x, y, facing, strafe), append);
            return;
        }

        // Keep the group's shape around the point, squeezed if spread out
        float cx = 0f;
        float cy = 0f;
        for (UnitState unit : group) {
            cx += unit.ship.getLocation().x;
            cy += unit.ship.getLocation().y;
        }
        cx /= group.size();
        cy /= group.size();
        float farthest = 1f;
        for (UnitState unit : group) {
            Vector2f loc = unit.ship.getLocation();
            farthest = Math.max(farthest, (float) Math.hypot(loc.x - cx, loc.y - cy));
        }
        float limit = FORMATION_SPREAD_PER_SHIP * (float) Math.sqrt(group.size());
        float scale = farthest > limit ? limit / farthest : 1f;
        for (UnitState unit : group) {
            Vector2f loc = unit.ship.getLocation();
            float px = x + (loc.x - cx) * scale;
            float py = y + (loc.y - cy) * scale;
            unit.give(attackMove ? Order.attackMove(px, py) : Order.move(px, py, facing, strafe), append);
        }
    }

    // ------------------------------------------------------------------ advance

    @Override
    public void advance(float amount, List<InputEventAPI> events) {
        if (!inBattle()) return;

        long now = System.nanoTime();
        float realDt = lastNanos == 0L ? 0f : RtsMath.clamp((now - lastNanos) / 1.0e9f, 0f, 0.1f);
        lastNanos = now;

        syncUnits();
        if (originalFlagship == null && mode != Mode.DIRECT) {
            ShipAPI player = engine.getPlayerShip();
            if (player != null && units.containsKey(player)) originalFlagship = player;
        }

        if (mode == Mode.DIRECT && (directShip == null || !directShip.isAlive()
                || engine.getPlayerShip() != directShip)) {
            setMode(Mode.RTS);
        }

        if (mode == Mode.RTS && !uiBusy()) {
            updateCamera(realDt);
            usePod(realDt);
            engine.getCombatUI().setDisablePlayerShipControlOneFrame(true);
        } else {
            releaseCamera();
            if (refanDelay > 0 && --refanDelay == 0) engine.getCombatUI().reFanOutShipInfo();
        }

        if (mode != Mode.VANILLA && !engine.isCombatOver()) controlUnits(amount);
        boolean over = engine.isCombatOver();
        FogOfWar.update(engine, (mode == Mode.RTS || TcoSettings.fogHideWhenPiloting) && !over, mode == Mode.RTS && !over,
                amount, arcSink);
    }

    private boolean isControllable(ShipAPI ship) {
        return ship != pod && ship != arcSink && ship.getOwner() == 0 && !ship.isAlly() && ship.isAlive() && !ship.isHulk()
                && !ship.isFighter() && !ship.isDrone() && !ship.isShuttlePod() && !ship.isPiece()
                && !ship.isStation() && !ship.isStationModule() && !SupplyFob.isFobPart(ship) && !AmmoRules.isShuttle(ship)
                && !ship.getCustomData().containsKey(BoardingCombatPlugin.CAPTURED_KEY);
    }

    private void syncUnits() {
        for (ShipAPI ship : engine.getShips()) {
            if (!units.containsKey(ship) && isControllable(ship)) {
                UnitState unit = new UnitState(ship);
                unit.stance = Stance.load(ship.getFleetMemberId());
                unit.savedStance = unit.stance;
                units.put(ship, unit);
            }
        }
        Iterator<Map.Entry<ShipAPI, UnitState>> it = units.entrySet().iterator();
        while (it.hasNext()) {
            ShipAPI ship = it.next().getKey();
            if (!isControllable(ship) || !engine.isEntityInPlay(ship)) {
                selected.remove(ship);
                it.remove();
            }
        }
    }

    private void controlUnits(float amount) {
        ShipAPI player = engine.getPlayerShip();
        behaviourTimer -= amount;
        boolean behaviourTick = amount > 0f && behaviourTimer <= 0f;
        if (behaviourTick) {
            behaviourTimer = 0.5f;
            maintainFormations();
        }
        for (UnitState unit : units.values()) {
            ShipAPI ship = unit.ship;
            boolean isPlayerShip = ship == player;

            if (mode == Mode.DIRECT && isPlayerShip) continue;

            // Clear leftover assignments
            if (unit.pendingClear && !unit.isEngaging() && !unit.isEscortingByAi() && !unit.autoEngaged) {
                clearAttackAssignment(unit);
                unit.pendingClear = false;
            }
            if (behaviourTick) updateBehaviour(unit);

            if (unit.cancelRetreat) {
                unit.cancelRetreat = false;
                if (ship.isRetreating()) cancelRetreat(unit);
            }

            if (unit.order == OrderType.BOARD) tickBoardOrder(unit);

            // Attack order ends when the target dies, if more are queued
            if (unit.isEngaging() && !unit.queue.isEmpty()
                    && (unit.target == null || !unit.target.isAlive() || unit.target.isHulk())) {
                unit.completeOrder();
            }
            // Escort ends when the escorted ship is gone
            if (unit.order == OrderType.ESCORT
                    && (unit.target == null || !unit.target.isAlive() || unit.target.isHulk() || !engine.isEntityInPlay(unit.target))) {
                unit.completeOrder();
            }

            if (unit.isAiFlown() || ship.isRetreating()) {
                if (unit.aiInstalled) {
                    ship.resetDefaultAI();
                    unit.aiInstalled = false;
                    unit.savedAutofire = null;
                } else if (isPlayerShip && ship.getShipAI() == null) {
                    tickManualVanillaAi(unit, amount);
                }
                if (unit.isEngaging() && !unit.attackAssigned) assignAttack(unit);
                if (unit.isEscortingByAi() && !unit.escortAssigned) assignEscort(unit);
                if (unit.order == OrderType.RETREAT && !unit.retreatAssigned) assignRetreat(unit);
                continue;
            }

            unit.manualVanillaAi = null;
            if (unit.attackAssigned || unit.escortAssigned) {
                clearAttackAssignment(unit);
                unit.escortAssigned = false;
            }

            if (isPlayerShip && !unit.aiInstalled && ship.getShipAI() == null) {
                // The engine runs no AI for the player-flown ship
                unit.ai.tick(amount);
                continue;
            }
            if (!unit.aiInstalled) {
                ship.setShipAI(unit.ai);
                unit.aiInstalled = true;
                unit.stallTime = 0f;
            } else if (unit.ai.engineTicks == unit.lastSeenEngineTicks && amount > 0f) {
                // Our AI was replaced: keep flying and restore it
                unit.ai.tick(amount);
                unit.stallTime += amount;
                if (unit.stallTime > 0.5f) unit.aiInstalled = false;
            } else {
                unit.stallTime = 0f;
            }
            unit.lastSeenEngineTicks = unit.ai.engineTicks;
        }
    }

    private void assignAttack(UnitState unit) {
        assignIntercept(unit, unit.target);
    }

    private void assignIntercept(UnitState unit, ShipAPI target) {
        unit.attackAssigned = true;
        unit.pendingClear = false;
        if (target == null || !target.isAlive()) return;
        unit.ship.setShipTarget(target);
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(unit.ship.getOwner());
            CombatTaskManagerAPI tasks = fleet.getTaskManager(false);
            DeployedFleetMemberAPI me = fleet.getDeployedFleetMember(unit.ship);
            DeployedFleetMemberAPI enemy = engine.getFleetManager(target.getOwner()).getDeployedFleetMember(target);
            if (me == null || enemy == null) return;

            AssignmentInfo previous = tasks.getAssignmentFor(unit.ship);
            AssignmentInfo info = tasks.getAssignmentInfoForTarget(enemy);
            if (info == null || info.getType() != CombatAssignmentType.INTERCEPT) {
                info = tasks.createAssignment(CombatAssignmentType.INTERCEPT, enemy, false);
            }
            tasks.giveAssignment(me, info, false);
            if (previous != null && previous != info && previous.getAssignedMembers().isEmpty()) {
                tasks.removeAssignment(previous);
            }
            unit.ship.setShipTarget(target);
        } catch (RuntimeException e) {
            Global.getLogger(RtsCombatPlugin.class).warn("TCO: could not assign attack target", e);
        }
    }

    // ------------------------------------------------------------------ stances, formations, attack-move

    /**
     * Twice a second: whether a ship engages on its own, and the leash on ongoing fights.
     * Depends on attack-move, formation and stance; other orders never stop to fight.
     */
    private void updateBehaviour(UnitState unit) {
        ShipAPI ship = unit.ship;
        if (ship.isRetreating() || unit.order == OrderType.RETREAT || unit.vanillaAi) return;
        if (unit.order == OrderType.HOLD && unit.idleAnchor == null && !unit.autoEngaged) {
            unit.idleAnchor = new Vector2f(ship.getLocation());
        }
        if (unit.stance == Stance.RESCUE && unit.order != OrderType.ATTACK_MOVE) {
            if (unit.autoEngaged) endEngagement(unit);
            boolean ours = unit.order == OrderType.MOVE && unit.rescueMove && RtsMath.distance(unit.point, unit.rescuePoint) < 1f;
            if (unit.order == OrderType.HOLD || ours) rescue(unit, ours);
            return;
        }
        if (unit.stance == Stance.EVASIVE && unit.order != OrderType.ATTACK_MOVE) {
            if (unit.autoEngaged) endEngagement(unit);
            if (unit.order == OrderType.HOLD) evade(unit);
            return;
        }
        if (unit.autoEngaged) {
            checkEngagement(unit);
            return;
        }
        float reach = weaponReach(ship);
        Vector2f centre;
        float radius;
        switch (unit.order) {
            case ATTACK_MOVE:
                centre = ship.getLocation();
                radius = reach + 300f;
                break;
            case FORMATION:
                if (unit.stance == Stance.HOLD || unit.formation == null) return;
                centre = unit.formation.slotPoint(unit);
                radius = reach + 200f;
                break;
            case HOLD:
                if (unit.stance == Stance.HOLD) return;
                if (unit.stance == Stance.AGGRESSIVE) {
                    centre = ship.getLocation();
                    radius = TcoSettings.aggressiveSightRange;
                } else {
                    centre = unit.idleAnchor;
                    radius = reach + 400f;
                }
                break;
            default:
                return;
        }
        if (centre == null) return;
        ShipAPI enemy = nearestEnemy(ship, centre, radius);
        if (enemy == null) return;
        unit.autoEngaged = true;
        unit.engageTarget = enemy;
        unit.engageAnchor.set(unit.order == OrderType.HOLD && unit.idleAnchor != null ? unit.idleAnchor : ship.getLocation());
        assignIntercept(unit, enemy);
    }

    private boolean evade(UnitState unit) {
        ShipAPI ship = unit.ship;
        float awayX = 0f, awayY = 0f, worst = 0f;
        for (ShipAPI other : engine.getShips()) {
            if (other.getOwner() == ship.getOwner() || (other.getOwner() != 0 && other.getOwner() != 1)) continue;
            if (!other.isAlive() || other.isHulk() || other.isFighter() || other.isDrone() || other.isShuttlePod()) continue;
            if (AmmoRules.isShuttle(other) || other.getCustomData().containsKey(BoardingCombatPlugin.CAPTURED_KEY)) continue;
            if (other.getCustomData().containsKey(EscapePodPlugin.POD_KEY) || other.isStationModule()) continue;
            float danger = weaponReach(other) + other.getCollisionRadius() + ship.getCollisionRadius() + TcoSettings.evasiveMargin;
            float dx = ship.getLocation().x - other.getLocation().x, dy = ship.getLocation().y - other.getLocation().y;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            if (d >= danger) continue;
            float inside = danger - d;
            if (d < 1f) {
                dx = 0f;
                dy = ship.getOwner() == 0 ? -1f : 1f;
                d = 1f;
            }
            awayX += dx / d * inside;
            awayY += dy / d * inside;
            worst = Math.max(worst, inside);
        }
        if (worst <= 0f) return false;
        float len = (float) Math.sqrt(awayX * awayX + awayY * awayY);
        if (len < 1f) {
            awayX = 0f;
            awayY = ship.getOwner() == 0 ? -1f : 1f;
            len = 1f;
        }
        float go = worst + 500f;
        float halfW = engine.getMapWidth() / 2f - 800f, halfH = engine.getMapHeight() / 2f - 800f;
        float x = Math.max(-halfW, Math.min(halfW, ship.getLocation().x + awayX / len * go));
        float y = Math.max(-halfH, Math.min(halfH, ship.getLocation().y + awayY / len * go));
        // Cornered at the edge: slide along it
        if (RtsMath.distance(ship.getLocation(), new Vector2f(x, y)) < 300f) {
            x = Math.max(-halfW, Math.min(halfW, ship.getLocation().x - awayY / len * go));
            y = Math.max(-halfH, Math.min(halfH, ship.getLocation().y + awayX / len * go));
        }
        unit.give(Order.move(x, y), false);
        unit.idleAnchor = null;
        return true;
    }

    private void rescue(UnitState unit, boolean onOwnMove) {
        ShipAPI ship = unit.ship;
        if (evade(unit)) {
            unit.rescueMove = true;
            unit.rescuePoint.set(unit.point);
            unit.rescueTarget = null;
            return;
        }
        EscapePodPlugin pods = EscapePodPlugin.get(engine);
        ShipAPI best = null;
        float bestDist = Float.MAX_VALUE;
        if (pods != null) {
            for (ShipAPI pod : pods.podsFor(ship)) {
                boolean taken = false;
                for (UnitState other : units.values()) {
                    if (other != unit && other.stance == Stance.RESCUE && other.rescueTarget == pod) taken = true;
                }
                if (taken || !outOfEnemyReach(ship, pod.getLocation())) continue;
                float d = RtsMath.distance(ship.getLocation(), pod.getLocation());
                if (d < bestDist) {
                    bestDist = d;
                    best = pod;
                }
            }
        }
        if (best == null) {
            unit.rescueTarget = null;
            if (onOwnMove) {
                unit.rescueMove = false;
                unit.completeOrder();
            }
            return;
        }
        Vector2f at = best.getLocation();
        if (best != unit.rescueTarget || !onOwnMove || RtsMath.distance(at, unit.rescuePoint) > 150f) {
            unit.give(Order.move(at.x, at.y), false);
            unit.rescueMove = true;
            unit.rescuePoint.set(unit.point);
            unit.rescueTarget = best;
            unit.idleAnchor = null;
        }
    }

    private boolean outOfEnemyReach(ShipAPI ship, Vector2f point) {
        for (ShipAPI other : engine.getShips()) {
            if (other.getOwner() == ship.getOwner() || (other.getOwner() != 0 && other.getOwner() != 1)) continue;
            if (!other.isAlive() || other.isHulk() || other.isFighter() || other.isDrone() || other.isShuttlePod()) continue;
            if (AmmoRules.isShuttle(other) || other.isStationModule()) continue;
            float reach = weaponReach(other) + other.getCollisionRadius() + ship.getCollisionRadius() + TcoSettings.evasiveMargin;
            if (RtsMath.distance(point, other.getLocation()) < reach) return false;
        }
        return true;
    }

    private Vector2f leashCentre(UnitState unit) {
        if (unit.order == OrderType.FORMATION) return unit.formation == null ? null : unit.formation.slotPoint(unit);
        if (unit.order == OrderType.HOLD && unit.stance == Stance.AGGRESSIVE) return null;
        return unit.engageAnchor;
    }

    private float leashOf(UnitState unit) {
        if (unit.order == OrderType.FORMATION) return TcoSettings.formationLeash;
        if (unit.order == OrderType.ATTACK_MOVE) return TcoSettings.attackMoveLeash;
        return TcoSettings.defensiveLeash;
    }

    private void checkEngagement(UnitState unit) {
        ShipAPI ship = unit.ship;
        Vector2f centre = leashCentre(unit);
        float leash = leashOf(unit);
        if (centre != null && RtsMath.distance(ship.getLocation(), centre) > leash) {
            endEngagement(unit);
            return;
        }
        ShipAPI target = unit.engageTarget;
        boolean gone = target == null || !target.isAlive() || target.isHulk() || !engine.isEntityInPlay(target)
                || target.getOwner() == ship.getOwner();
        boolean strayed = !gone && centre != null
                && RtsMath.distance(target.getLocation(), centre) > leash + weaponReach(ship);
        if (!gone && !strayed) return;
        float radius = unit.order == OrderType.HOLD && unit.stance == Stance.AGGRESSIVE
                ? TcoSettings.aggressiveSightRange : weaponReach(ship) + 400f;
        ShipAPI next = nearestEnemy(ship, centre != null ? centre : ship.getLocation(), radius);
        if (next != null) {
            unit.engageTarget = next;
            assignIntercept(unit, next);
        } else {
            endEngagement(unit);
        }
    }

    private void endEngagement(UnitState unit) {
        unit.autoEngaged = false;
        unit.engageTarget = null;
        clearAttackAssignment(unit);
        if (unit.order == OrderType.HOLD && unit.stance == Stance.DEFENSIVE && unit.idleAnchor != null
                && RtsMath.distance(unit.ship.getLocation(), unit.idleAnchor) > 200f) {
            Vector2f home = new Vector2f(unit.idleAnchor);
            unit.give(Order.move(home.x, home.y), false);
        }
    }

    private float weaponReach(ShipAPI ship) {
        float guns = 0f;
        float missiles = 0f;
        for (WeaponAPI weapon : ship.getAllWeapons()) {
            if (weapon.isDecorative()) continue;
            if (weapon.getType() == WeaponAPI.WeaponType.MISSILE) missiles = Math.max(missiles, weapon.getRange());
            else guns = Math.max(guns, weapon.getRange());
        }
        float reach = guns > 0f ? guns : missiles;
        return reach > 0f ? reach : 600f;
    }

    private ShipAPI nearestEnemy(ShipAPI ship, Vector2f centre, float radius) {
        ShipAPI best = null;
        float bestDist = Float.MAX_VALUE;
        for (ShipAPI other : engine.getShips()) {
            if (other.getOwner() == ship.getOwner() || (other.getOwner() != 0 && other.getOwner() != 1)) continue;
            if (!other.isAlive() || other.isHulk() || other.isFighter() || other.isDrone() || other.isShuttlePod()) continue;
            if (AmmoRules.isShuttle(other) || other.getCustomData().containsKey(BoardingCombatPlugin.CAPTURED_KEY)) continue;
            float dist = RtsMath.distance(centre, other.getLocation()) - other.getCollisionRadius();
            if (dist <= radius && dist < bestDist) {
                bestDist = dist;
                best = other;
            }
        }
        return best;
    }

    private void cycleStance() {
        if (selected.isEmpty()) return;
        Stance next = null;
        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit != null) {
                next = unit.stance.next();
                break;
            }
        }
        if (next != null) setStance(next);
    }

    private void setStance(Stance stance) {
        boolean any = false;
        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit == null) continue;
            unit.stance = stance;
            if (unit.autoEngaged) endEngagement(unit);
            unit.idleAnchor = null;
            unit.rescueTarget = null;
            any = true;
        }
        if (any) message("Stance: " + stance.label + ". Click \"Save stances\" to keep it after this battle.");
    }

    // ------------------------------------------------------------------ stance dropdown

    private boolean stanceMenuOpen;

    private float stanceItemY(int i) {
        return cmdY(Command.STANCE.ordinal()) + (i + 1) * (CMD_H + 2f);
    }

    private Stance stanceItemAt(float x, float y) {
        if (!stanceMenuOpen) return null;
        float bx = cmdX(Command.STANCE.ordinal());
        if (x < bx || x > bx + CMD_W) return null;
        Stance[] all = Stance.values();
        for (int i = 0; i < all.length; i++) {
            if (y >= stanceItemY(i) && y <= stanceItemY(i) + CMD_H) return all[i];
        }
        return null;
    }

    private final LabelAPI[] stanceLabels = new LabelAPI[8];
    private final String[] stanceLabelText = new String[8];

    private void renderStanceMenu() {
        if (!stanceMenuOpen) return;
        if (selected.isEmpty() || mode != Mode.RTS) {
            stanceMenuOpen = false;
            return;
        }
        Stance shared = null;
        boolean mixed = false;
        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit == null) continue;
            if (shared == null) shared = unit.stance;
            else if (shared != unit.stance) mixed = true;
        }
        Stance hover = stanceItemAt(mouseX(), mouseY());
        Stance[] all = Stance.values();
        float x = cmdX(Command.STANCE.ordinal());
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (int i = 0; i < all.length; i++) {
            boolean current = !mixed && all[i] == shared;
            if (all[i] == hover) GL11.glColor4f(0.12f, 0.3f, 0.4f, 0.95f);
            else if (current) GL11.glColor4f(0.3f, 0.25f, 0.07f, 0.95f);
            else GL11.glColor4f(0.03f, 0.05f, 0.07f, 0.92f);
            quad(x, stanceItemY(i), CMD_W, CMD_H);
            GL11.glColor4f(0.3f, 0.5f, 0.6f, 0.7f);
            outline(x, stanceItemY(i), CMD_W, CMD_H);
        }
        GL11.glPopAttrib();
        for (int i = 0; i < all.length; i++) {
            boolean current = !mixed && all[i] == shared;
            drawPanelText(stanceLabels, stanceLabelText, i, all[i].label, x + 7f, stanceItemY(i) + 5f, CMD_W - 10f,
                    current ? new Color(255, 215, 110) : new Color(210, 225, 235));
        }
        if (hover != null) {
            drawPanelText(stanceLabels, stanceLabelText, all.length, stanceTip(hover), x + CMD_W + 8f,
                    stanceItemY(hover.ordinal()) + 5f, 340f, new Color(255, 225, 140));
        }
    }

    private static String stanceTip(Stance stance) {
        switch (stance) {
            case AGGRESSIVE: return "Chases any enemy it can see.";
            case DEFENSIVE: return "Fights enemies near its spot, then goes back.";
            case HOLD: return "Never moves on its own; only shoots.";
            case EVASIVE: return "Keeps out of enemy guns' reach.";
            default: return "Picks up your escape pods, away from enemy guns.";
        }
    }

    private void saveStances() {
        boolean campaign = engine.isInCampaign() && !engine.isSimulation() && Global.getSector() != null;
        if (!campaign) {
            message("Stances can only be saved in a campaign battle.");
            return;
        }
        int saved = 0;
        for (UnitState unit : units.values()) {
            if (unit.stance == unit.savedStance) continue;
            if (Stance.save(unit.ship.getFleetMemberId(), unit.stance)) {
                unit.savedStance = unit.stance;
                saved++;
            }
        }
        message(saved == 1 ? "Saved 1 stance." : "Saved " + saved + " stances.");
    }

    private void cycleFormation() {
        List<UnitState> group = new ArrayList<UnitState>();
        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit != null && !ship.isRetreating()) group.add(unit);
        }
        if (group.size() < 2) {
            message("Select at least two ships to form a formation.");
            return;
        }
        Formation shared = group.get(0).formation;
        boolean same = shared != null && shared.members().size() == group.size();
        for (UnitState unit : group) {
            if (unit.formation != shared) same = false;
        }
        if (same) {
            if (shared.type == Formation.Type.COLUMN) {
                breakFormation(shared);
                message("Formation broken up.");
                return;
            }
            shared.setType(shared.type == Formation.Type.LINE ? Formation.Type.WEDGE : Formation.Type.COLUMN);
            message("Formation: " + shared.type.label + ", led by " + shared.leader.ship.getName() + ".");
            return;
        }
        for (UnitState unit : group) {
            if (unit.formation != null) leaveFormation(unit);
        }
        Formation formation = new Formation(Formation.Type.LINE, group);
        formations.add(formation);
        for (UnitState unit : formation.members()) {
            unit.formation = formation;
            if (unit != formation.leader) unit.give(Order.formation(), false);
        }
        message("Formation: Line, led by " + formation.leader.ship.getName() + ". "
                + InputKeys.name(TcoSettings.keyFormation) + " again for wedge, column, or to break it up.");
    }

    private void leaveFormation(UnitState unit) {
        Formation formation = unit.formation;
        if (formation == null) return;
        UnitState oldLeader = formation.leader;
        unit.formation = null;
        if (!formation.remove(unit)) {
            breakFormation(formation);
            return;
        }
        if (formation.leader != oldLeader) {
            oldLeader.ship.getMutableStats().getMaxSpeed().unmodify(FORMATION_SPEED_ID);
            // New leader takes over the old order
            if (formation.leader.order == OrderType.FORMATION) formation.leader.hold();
        }
    }

    private void breakFormation(Formation formation) {
        formations.remove(formation);
        formation.leader.ship.getMutableStats().getMaxSpeed().unmodify(FORMATION_SPEED_ID);
        for (UnitState unit : formation.members()) {
            unit.formation = null;
            if (unit.order == OrderType.FORMATION) unit.hold();
        }
    }

    private void maintainFormations() {
        for (Formation formation : new ArrayList<Formation>(formations)) {
            for (UnitState unit : formation.members()) {
                boolean gone = !units.containsKey(unit.ship) || unit.ship.isRetreating();
                boolean wandered = unit != formation.leader && unit.order != OrderType.FORMATION;
                if (gone || wandered) leaveFormation(unit);
                if (!formations.contains(formation)) break;
            }
            if (!formations.contains(formation)) continue;
            MutableStat leaderSpeed = formation.leader.ship.getMutableStats().getMaxSpeed();
            leaderSpeed.unmodify(FORMATION_SPEED_ID);
            float own = leaderSpeed.getModifiedValue();
            float slowest = own;
            for (UnitState unit : formation.slots.keySet()) {
                slowest = Math.min(slowest, unit.ship.getMutableStats().getMaxSpeed().getModifiedValue());
            }
            if (own > 1f && slowest < own) leaderSpeed.modifyMult(FORMATION_SPEED_ID, Math.max(0.2f, slowest / own));
        }
    }

    private void assignEscort(UnitState unit) {
        unit.escortAssigned = true;
        ShipAPI friend = unit.target;
        if (friend == null || !friend.isAlive()) return;
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(unit.ship.getOwner());
            CombatTaskManagerAPI tasks = fleet.getTaskManager(false);
            DeployedFleetMemberAPI me = fleet.getDeployedFleetMember(unit.ship);
            DeployedFleetMemberAPI escorted = fleet.getDeployedFleetMember(friend);
            if (me == null || escorted == null) return;

            HullSize size = friend.getHullSize();
            CombatAssignmentType type = size == HullSize.CAPITAL_SHIP ? CombatAssignmentType.HEAVY_ESCORT
                    : size == HullSize.CRUISER ? CombatAssignmentType.MEDIUM_ESCORT : CombatAssignmentType.LIGHT_ESCORT;
            AssignmentInfo previous = tasks.getAssignmentFor(unit.ship);
            AssignmentInfo info = tasks.getAssignmentInfoForTarget(escorted);
            if (info == null || (info.getType() != CombatAssignmentType.LIGHT_ESCORT
                    && info.getType() != CombatAssignmentType.MEDIUM_ESCORT
                    && info.getType() != CombatAssignmentType.HEAVY_ESCORT)) {
                info = tasks.createAssignment(type, escorted, false);
            }
            tasks.giveAssignment(me, info, false);
            if (previous != null && previous != info && previous.getAssignedMembers().isEmpty()) {
                tasks.removeAssignment(previous);
            }
        } catch (RuntimeException e) {
            Global.getLogger(RtsCombatPlugin.class).warn("TCO: could not assign escort", e);
        }
    }

    private void cancelRetreat(UnitState unit) {
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(unit.ship.getOwner());
            CombatTaskManagerAPI tasks = fleet.getTaskManager(false);
            if (tasks.isInFullRetreat()) {
                message("A full retreat cannot be called off.");
                return;
            }
            DeployedFleetMemberAPI me = fleet.getDeployedFleetMember(unit.ship);
            if (me != null) tasks.orderSearchAndDestroy(me, false);
        } catch (RuntimeException e) {
            Global.getLogger(RtsCombatPlugin.class).warn("TCO: could not cancel retreat", e);
        }
    }

    private void assignRetreat(UnitState unit) {
        unit.retreatAssigned = true;
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(unit.ship.getOwner());
            DeployedFleetMemberAPI me = fleet.getDeployedFleetMember(unit.ship);
            if (me != null) fleet.getTaskManager(false).orderRetreat(me, false, false);
        } catch (RuntimeException e) {
            Global.getLogger(RtsCombatPlugin.class).warn("TCO: could not order retreat", e);
        }
    }

    private void clearAttackAssignment(UnitState unit) {
        unit.attackAssigned = false;
        try {
            CombatFleetManagerAPI fleet = engine.getFleetManager(unit.ship.getOwner());
            CombatTaskManagerAPI tasks = fleet.getTaskManager(false);
            AssignmentInfo info = tasks.getAssignmentFor(unit.ship);
            if (info == null) return;
            DeployedFleetMemberAPI me = fleet.getDeployedFleetMember(unit.ship);
            if (me != null) tasks.orderSearchAndDestroy(me, false);
            if (info.getAssignedMembers().isEmpty()) tasks.removeAssignment(info);
        } catch (RuntimeException e) {
            Global.getLogger(RtsCombatPlugin.class).warn("TCO: could not clear attack assignment", e);
        }
    }

    private void tickManualVanillaAi(UnitState unit, float amount) {
        if (amount <= 0f) return;
        try {
            if (unit.manualVanillaAi == null) {
                unit.manualVanillaAi = Global.getSettings().createDefaultShipAI(unit.ship, new ShipAIConfig());
            }
            unit.manualVanillaAi.advance(amount);
        } catch (RuntimeException e) {
            Global.getLogger(RtsCombatPlugin.class).warn("TCO: could not run the default AI on the flagship", e);
            unit.manualVanillaAi = null;
            unit.vanillaAi = false;
        }
    }

    // ------------------------------------------------------------------ camera

    private void updateCamera(float dt) {
        ViewportAPI viewport = engine.getViewport();
        if (!cameraTaken) {
            // Start from the game's view only when entering RTS mode
            if (!camValid) {
                camCenter.set(viewport.getCenter());
                camZoom = RtsMath.clamp(viewport.getViewMult(), TcoSettings.zoomMin, TcoSettings.zoomMax);
                camZoomTarget = camZoom;
                camValid = true;
            }
            cameraTaken = true;
            viewport.setExternalControl(true);
        }

        float dx = 0f;
        float dy = 0f;
        if (Keyboard.isKeyDown(Keyboard.KEY_W) || Keyboard.isKeyDown(Keyboard.KEY_UP)) dy += 1f;
        if (Keyboard.isKeyDown(Keyboard.KEY_S) || Keyboard.isKeyDown(Keyboard.KEY_DOWN)) dy -= 1f;
        if (Keyboard.isKeyDown(Keyboard.KEY_D) || Keyboard.isKeyDown(Keyboard.KEY_RIGHT)) dx += 1f;
        if (Keyboard.isKeyDown(Keyboard.KEY_A) || Keyboard.isKeyDown(Keyboard.KEY_LEFT)) dx -= 1f;

        float speed = TcoSettings.cameraPanSpeed * camZoom;
        if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) {
            speed *= TcoSettings.cameraFastMult;
        }
        camCenter.x += dx * speed * dt;
        camCenter.y += dy * speed * dt;

        if (panning && !Mouse.isButtonDown(2)) panning = false;
        if (panning) {
            // Grab-and-drag
            float mx = mouseX();
            float my = mouseY();
            camCenter.x -= viewport.convertScreenWidthToWorldWidth(mx - panLastX);
            camCenter.y -= viewport.convertScreenHeightToWorldHeight(my - panLastY);
            panLastX = mx;
            panLastY = my;
        }

        float halfW = engine.getMapWidth() / 2f + MAP_EDGE_MARGIN;
        float halfH = engine.getMapHeight() / 2f + MAP_EDGE_MARGIN;
        camCenter.x = RtsMath.clamp(camCenter.x, -halfW, halfW);
        camCenter.y = RtsMath.clamp(camCenter.y, -halfH, halfH);

        camZoom += (camZoomTarget - camZoom) * Math.min(1f, dt * 10f);

        // Zoom by setting the visible rectangle (setViewMult alone doesn't stick)
        float viewMult = viewport.getViewMult();
        float widthPerMult = viewMult > 0f ? viewport.getVisibleWidth() / viewMult
                : Global.getSettings().getScreenWidth();
        float aspect = viewport.getVisibleWidth() > 0f
                ? viewport.getVisibleHeight() / viewport.getVisibleWidth()
                : Global.getSettings().getScreenHeight() / Global.getSettings().getScreenWidth();
        float width = widthPerMult * camZoom;
        float height = width * aspect;
        viewport.setExternalControl(true);
        viewport.set(camCenter.x - width / 2f, camCenter.y - height / 2f, width, height);

        // Hide the flagship status widget
        CombatUIAPI ui = engine.getCombatUI();
        ui.hideShipInfo();
        ui.setShipInfoFanOutBrightness(0f);
    }

    private void releaseCamera() {
        if (!cameraTaken) return;
        cameraTaken = false;
        engine.getViewport().setExternalControl(false);
        // Wait before fading the panel in, or it locks onto the previous ship
        refanDelay = 4;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {
        if (!inBattle() || mode != Mode.RTS || uiBusy()) return;
        FogOfWar.render(viewport);
        RadarCombatPlugin.render(engine, viewport, selected);

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(2f);

        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit == null) continue;
            Vector2f loc = ship.getLocation();

            if (unit.vanillaAi) GL11.glColor4f(1f, 0.65f, 0.1f, 0.9f);
            else if (unit.order == OrderType.RETREAT || ship.isRetreating()) GL11.glColor4f(0.7f, 0.7f, 0.7f, 0.9f);
            else if (unit.isEngaging()) GL11.glColor4f(1f, 0.35f, 0.3f, 0.9f);
            else GL11.glColor4f(0.3f, 1f, 0.4f, 0.9f);
            circle(loc.x, loc.y, ship.getCollisionRadius() + 15f);

            if (!unit.vanillaAi && unit.order == OrderType.FACE && unit.facing != null) {
                GL11.glColor4f(0.3f, 1f, 0.4f, 0.6f);
                arrow(loc.x, loc.y, unit.facing, ship.getCollisionRadius() + 80f);
            }

            Vector2f dest = unit.vanillaAi ? null : unit.getOrderLocation();
            if (dest == null) continue;
            switch (unit.order) {
                case ATTACK:
                    GL11.glColor4f(1f, 0.25f, 0.2f, 0.75f);
                    break;
                case ESCORT:
                    GL11.glColor4f(0.3f, 0.75f, 1f, 0.75f);
                    break;
                case BOARD:
                    GL11.glColor4f(0.85f, 0.45f, 1f, 0.8f);
                    break;
                case CAPTURE:
                    GL11.glColor4f(1f, 0.9f, 0.2f, 0.75f);
                    break;
                default:
                    GL11.glColor4f(0.3f, 1f, 0.4f, 0.6f);
                    break;
            }
            GL11.glBegin(GL11.GL_LINES);
            GL11.glVertex2f(loc.x, loc.y);
            GL11.glVertex2f(dest.x, dest.y);
            GL11.glEnd();
            circle(dest.x, dest.y, 12f * camZoom);
            if (unit.order == OrderType.MOVE && unit.facing != null) {
                arrow(dest.x, dest.y, unit.facing, 70f * camZoom);
            }

            Vector2f from = dest;
            GL11.glColor4f(0.8f, 0.8f, 0.8f, 0.45f);
            for (Order queued : unit.queue) {
                Vector2f next = queued.getLocation();
                if (next == null) continue;
                GL11.glBegin(GL11.GL_LINES);
                GL11.glVertex2f(from.x, from.y);
                GL11.glVertex2f(next.x, next.y);
                GL11.glEnd();
                circle(next.x, next.y, 8f * camZoom);
                from = next;
            }
        }

        if (dragging) {
            float x1 = viewport.convertScreenXToWorldX(dragStartX);
            float y1 = viewport.convertScreenYToWorldY(dragStartY);
            float x2 = viewport.convertScreenXToWorldX(mouseX());
            float y2 = viewport.convertScreenYToWorldY(mouseY());
            GL11.glColor4f(0.3f, 1f, 0.4f, 0.8f);
            GL11.glBegin(GL11.GL_LINE_LOOP);
            GL11.glVertex2f(x1, y1);
            GL11.glVertex2f(x2, y1);
            GL11.glVertex2f(x2, y2);
            GL11.glVertex2f(x1, y2);
            GL11.glEnd();
        }

        if (orderDragging && !selected.isEmpty()
                && Math.abs(mouseX() - orderStartX) + Math.abs(mouseY() - orderStartY) >= ORDER_DRAG_THRESHOLD) {
            float ex = viewport.convertScreenXToWorldX(mouseX());
            float ey = viewport.convertScreenYToWorldY(mouseY());
            float length = (float) Math.hypot(ex - orderStartWorld.x, ey - orderStartWorld.y);
            GL11.glColor4f(0.4f, 0.9f, 1f, 0.9f);
            circle(orderStartWorld.x, orderStartWorld.y, 12f * camZoom);
            arrow(orderStartWorld.x, orderStartWorld.y, RtsMath.angle(orderStartWorld, new Vector2f(ex, ey)), length);
        }

        GL11.glPopAttrib();
    }

    private void arrow(float x, float y, float angle, float length) {
        double rad = Math.toRadians(angle);
        float tipX = x + (float) Math.cos(rad) * length;
        float tipY = y + (float) Math.sin(rad) * length;
        float head = Math.min(length * 0.35f, 22f * camZoom);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(tipX, tipY);
        for (int side = -1; side <= 1; side += 2) {
            double barb = Math.toRadians(angle + 180f + side * 28f);
            GL11.glVertex2f(tipX, tipY);
            GL11.glVertex2f(tipX + (float) Math.cos(barb) * head, tipY + (float) Math.sin(barb) * head);
        }
        GL11.glEnd();
    }

    private void circle(float x, float y, float radius) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < 36; i++) {
            double a = Math.PI * 2.0 * i / 36.0;
            GL11.glVertex2f(x + (float) Math.cos(a) * radius, y + (float) Math.sin(a) * radius);
        }
        GL11.glEnd();
    }

    @Override
    public void renderInUICoords(ViewportAPI viewport) {
        if (!inBattle() || uiBusy()) return;
        if (mode == Mode.RTS) {
            renderSelectionPanel();
            renderCommandCard();
            if (helpOpen) renderHelp();
        }
        SpriteAPI sprite = currentButton();
        if (sprite == null) return;
        sprite.setAlphaMult(isOverButton(mouseX(), mouseY()) ? 1f : 0.8f);
        sprite.renderAtCenter(buttonCenterX(), buttonCenterY());
    }

    private void renderSelectionPanel() {
        saveVisible = false;
        boardBtnY = -1f;
        boardBtnShip = null;
        if (selected.isEmpty()) {
            ShipAPI other = inspected();
            if (other != null) renderCard(other, null);
            return;
        }
        renderPanelOnly();
        renderSaveButton(panelTop);
    }

    private void renderSaveButton(float top) {
        boolean unsaved = false;
        for (UnitState unit : units.values()) {
            if (unit.stance != unit.savedStance) unsaved = true;
        }
        if (!unsaved) return;
        saveVisible = true;
        saveX = PANEL_X;
        saveY = top + 6f;
        boolean over = mouseX() >= saveX && mouseX() <= saveX + SAVE_W && mouseY() >= saveY && mouseY() <= saveY + SAVE_H;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(over ? 0.35f : 0.2f, over ? 0.3f : 0.22f, 0.08f, 0.9f);
        quad(saveX, saveY, SAVE_W, SAVE_H);
        GL11.glPopAttrib();
        try {
            if (saveLabel == null) saveLabel = Global.getSettings().createLabel("Save stances", Fonts.DEFAULT_SMALL);
            saveLabel.setColor(new Color(255, 215, 110));
            saveLabel.getPosition().setSize(SAVE_W - 10f, SAVE_H);
            saveLabel.getPosition().setLocation(saveX + 8f, saveY + 2f);
            saveLabel.render(1f);
        } catch (RuntimeException e) {
            saveLabel = null;
        }
    }

    // ------------------------------------------------------------------ command card

    /**
     * Command card: one button per order or toggle, greyed out when it doesn't apply.
     * Attack-move and Board wait for a map click (right click cancels).
     */
    private enum Command { STOP, HOLD_FIRE, ATTACK_MOVE, RETREAT, STANCE, FORMATION, BOARD, HANGAR, SHIP_AI, PILOT, SAVE, HELP }

    private static final float CMD_W = 150f;
    private static final float CMD_H = 26f;
    private static final float CMD_GAP = 4f;
    private static final int CMD_COLS = 4;
    private Command pending;
    private boolean helpOpen = false;
    private final LabelAPI[] cmdLabels = new LabelAPI[Command.values().length + 1];
    private final String[] cmdText = new String[Command.values().length + 1];
    private final LabelAPI[] helpLabels = new LabelAPI[60];
    private final String[] helpText = new String[60];

    private float cmdX(int index) {
        return PANEL_X + PANEL_WIDTH + 12f + (index % CMD_COLS) * (CMD_W + CMD_GAP);
    }

    private float cmdY(int index) {
        int rows = (Command.values().length + CMD_COLS - 1) / CMD_COLS;
        int row = index / CMD_COLS;
        return PANEL_Y + (rows - 1 - row) * (CMD_H + CMD_GAP);
    }

    private Command commandAt(float x, float y) {
        if (mode != Mode.RTS) return null;
        for (Command command : Command.values()) {
            int i = command.ordinal();
            if (x >= cmdX(i) && x <= cmdX(i) + CMD_W && y >= cmdY(i) && y <= cmdY(i) + CMD_H) return command;
        }
        return null;
    }

    private String key(int code) {
        return InputKeys.name(code);
    }

    private boolean anyCanBoard() {
        for (ShipAPI ship : selected) {
            if (BoardingCombatPlugin.hasBay(ship)) return true;
        }
        return false;
    }

    private boolean anyCarriesCraft() {
        for (ShipAPI ship : selected) {
            if (HangarCombatPlugin.hasCraft(ship)) return true;
        }
        return false;
    }

    private boolean enabled(Command command) {
        switch (command) {
            case HELP:
                return true;
            case SAVE:
                for (UnitState unit : units.values()) {
                    if (unit.stance != unit.savedStance) return true;
                }
                return false;
            case FORMATION:
                return selected.size() >= 2;
            case BOARD:
                return anyCanBoard();
            case HANGAR:
                return anyCarriesCraft();
            default:
                return !selected.isEmpty();
        }
    }

    private String labelOf(Command command) {
        switch (command) {
            case STOP:
                return "Stop  [" + key(TcoSettings.keyStop) + "]";
            case HOLD_FIRE: {
                boolean allHolding = !selected.isEmpty();
                for (ShipAPI ship : selected) {
                    UnitState unit = units.get(ship);
                    if (unit != null && !unit.holdFire) allHolding = false;
                }
                return (allHolding ? "Open fire" : "Hold fire") + "  [" + key(TcoSettings.keyHoldFire) + "]";
            }
            case ATTACK_MOVE:
                return "Attack-move  [Ctrl+RMB]";
            case RETREAT:
                return "Retreat  [" + key(TcoSettings.keyRetreat) + "]";
            case STANCE: {
                Stance shared = null;
                boolean mixed = false;
                for (ShipAPI ship : selected) {
                    UnitState unit = units.get(ship);
                    if (unit == null) continue;
                    if (shared == null) shared = unit.stance;
                    else if (shared != unit.stance) mixed = true;
                }
                String name = shared == null ? "-" : mixed ? "mixed" : shared.label;
                return name + "  [" + key(TcoSettings.keyStance) + "]";
            }
            case FORMATION: {
                Formation shared = null;
                for (ShipAPI ship : selected) {
                    UnitState unit = units.get(ship);
                    if (unit != null && unit.formation != null) shared = unit.formation;
                }
                return (shared == null ? "Formation" : shared.type.label) + "  [" + key(TcoSettings.keyFormation) + "]";
            }
            case BOARD:
                return "Board  [" + key(TcoSettings.keyBoard) + "]";
            case HANGAR: {
                boolean anyHeld = false;
                for (ShipAPI ship : selected) {
                    if (HangarCombatPlugin.isHeld(ship)) anyHeld = true;
                }
                return (anyHeld ? "Open hangar" : "Close hangar") + "  [" + key(TcoSettings.keyHangar) + "]";
            }
            case SHIP_AI:
                return "Ship AI  [" + key(TcoSettings.keyToggleShipAI) + "]";
            case PILOT:
                return "Pilot  [" + key(TcoSettings.keyDirectControl) + "]";
            case SAVE:
                return "Save stances";
            default:
                return "Controls  (?)";
        }
    }

    private String tipOf(Command command) {
        switch (command) {
            case STOP:
                return "Drop all orders and stop. Boarding shuttles still on the way come home.";
            case HOLD_FIRE:
                return "Stop all firing, or let them fire again.";
            case ATTACK_MOVE:
                return "Click a point: move there, fighting whatever is met on the way.";
            case RETREAT:
                return "Leave the battle by your edge of the map. Any new order calls it off.";
            case STANCE:
                return "Pick a stance from the list (" + key(TcoSettings.keyStance) + " cycles them).";
            case FORMATION:
                return "Line up behind the biggest ship; again for wedge, column, or to break up.";
            case BOARD:
                return "Click an enemy ship: boarding ships close in and send marines.";
            case HANGAR:
                return "Keep fighters, supply shuttles and repair drones aboard, or let them out.";
            case SHIP_AI:
                return "Hand the ships to the normal ship AI, or take them back.";
            case PILOT:
                return "Fly the selected ship yourself. " + key(TcoSettings.keyDirectControl) + " again to come back.";
            case SAVE:
                return "Keep stances changed in this battle for the next ones.";
            default:
                return "Show every mouse control and key.";
        }
    }

    private void runCommand(Command command) {
        if (!enabled(command)) return;
        switch (command) {
            case STOP:
                stopSelection();
                break;
            case HOLD_FIRE:
                toggleHoldFire();
                break;
            case ATTACK_MOVE:
            case BOARD:
                pending = command;
                break;
            case RETREAT:
                retreatSelection();
                break;
            case STANCE:
                stanceMenuOpen = !stanceMenuOpen;
                break;
            case FORMATION:
                cycleFormation();
                break;
            case HANGAR:
                toggleHangarHold(selected, "Selected ships");
                break;
            case SHIP_AI:
                toggleAiForSelection();
                break;
            case PILOT:
                takeDirectControl();
                break;
            case SAVE:
                saveStances();
                break;
            default:
                helpOpen = !helpOpen;
                break;
        }
    }

    private void finishPending(boolean shift) {
        Command command = pending;
        pending = null;
        if (command == Command.BOARD) {
            boardSelection(shift);
        } else if (command == Command.ATTACK_MOVE && !selected.isEmpty()) {
            ViewportAPI viewport = engine.getViewport();
            issueOrder(viewport.convertScreenXToWorldX(mouseX()), viewport.convertScreenYToWorldY(mouseY()),
                    shift, null, false, true);
        }
    }

    private void renderCommandCard() {
        Command hover = commandAt(mouseX(), mouseY());
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (Command command : Command.values()) {
            int i = command.ordinal();
            boolean on = enabled(command);
            if (command == pending) GL11.glColor4f(0.45f, 0.35f, 0.08f, 0.9f);
            else if (on && command == hover) GL11.glColor4f(0.12f, 0.3f, 0.4f, 0.9f);
            else GL11.glColor4f(0.03f, 0.05f, 0.07f, on ? 0.8f : 0.5f);
            quad(cmdX(i), cmdY(i), CMD_W, CMD_H);
            GL11.glColor4f(0.3f, 0.5f, 0.6f, on ? 0.7f : 0.25f);
            outline(cmdX(i), cmdY(i), CMD_W, CMD_H);
        }
        GL11.glPopAttrib();
        for (Command command : Command.values()) {
            int i = command.ordinal();
            boolean on = enabled(command);
            Color color = !on ? new Color(110, 115, 120) : command == Command.SAVE ? new Color(255, 215, 110) : new Color(210, 225, 235);
            drawPanelText(cmdLabels, cmdText, i, labelOf(command), cmdX(i) + 7f, cmdY(i) + 5f, CMD_W - 10f, color);
        }
        // Tip line above the card
        String tip = null;
        if (pending == Command.ATTACK_MOVE) tip = "Click a point on the map to attack-move there. Right click to cancel.";
        else if (pending == Command.BOARD) tip = "Click an enemy ship to board it. Right click to cancel.";
        else if (hover != null && !stanceMenuOpen) tip = tipOf(hover);
        renderStanceMenu();
        if (tip != null) {
            drawPanelText(cmdLabels, cmdText, Command.values().length, tip, cmdX(0), cmdY(0) + CMD_H + 6f,
                    CMD_COLS * (CMD_W + CMD_GAP), new Color(255, 225, 140));
        }
    }

    private void outline(float x, float y, float width, float height) {
        GL11.glBegin(GL11.GL_LINE_LOOP);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + width, y);
        GL11.glVertex2f(x + width, y + height);
        GL11.glVertex2f(x, y + height);
        GL11.glEnd();
    }

    private String rebinding;
    private float helpX;
    private float helpY;
    private float helpW;
    private float helpH;
    private float keyRowsTop;
    private static final float HELP_LINE = 18f;
    private static final float KEY_ROW = 20f;

    private static final String[] MOUSE_HELP = {
        "Left click / drag a box: select ships.   Shift: add to or remove from the selection.",
        "Right click: move there / attack an enemy / escort a friend / capture an objective.   Shift: queue it.",
        "Right drag: move there and face that way.   Shift + right drag: turn first, then slide there.",
        "Alt + right drag, or right drag starting on a selected ship: turn in place.",
        "Ctrl + right click: attack-move (fight whatever is met on the way).",
        "Right click a stranded friend with a Tug Rig ship selected: take it in tow.",
        "WASD / arrows, or hold the middle button and drag: move the camera.   Wheel: zoom.",
        "Ctrl + 1-9: save a group.   1-9: select it.   Shift + 1-9: add it to the selection.",
    };

    private float[] resetRect() {
        return new float[] {helpX + helpW - 214f, helpY + 10f, 200f, 22f};
    }

    private void clickHelp(float x, float y) {
        if (x < helpX || x > helpX + helpW || y < helpY || y > helpY + helpH) {
            helpOpen = false;
            rebinding = null;
            return;
        }
        float[] reset = resetRect();
        if (x >= reset[0] && x <= reset[0] + reset[2] && y >= reset[1] && y <= reset[1] + reset[3]) {
            Keybinds.resetToDefaults();
            rebinding = null;
            message("Keys reset to the ones in tco_settings.json.");
            return;
        }
        for (int i = 0; i < Keybinds.ACTIONS.length; i++) {
            float rowY = keyRowsTop - (i + 1) * KEY_ROW;
            if (y >= rowY && y < rowY + KEY_ROW) {
                rebinding = Keybinds.ACTIONS[i][0];
                return;
            }
        }
    }

    private void finishRebind(int key) {
        String action = rebinding;
        rebinding = null;
        if (key == Keyboard.KEY_ESCAPE) return;
        if (Keybinds.isReserved(key)) {
            message(InputKeys.name(key) + " is taken by the camera, groups or modifiers; pick another key.");
            return;
        }
        String swapped = Keybinds.rebind(action, key);
        message("Key set to " + InputKeys.name(key) + "." + (swapped != null ? " (" + swapped + " now uses the old key.)" : ""));
    }

    private void renderHelp() {
        int keys = Keybinds.ACTIONS.length;
        helpW = 780f;
        helpH = 30f + (MOUSE_HELP.length + 3) * HELP_LINE + keys * KEY_ROW + 44f;
        helpX = Global.getSettings().getScreenWidth() / 2f - helpW / 2f;
        helpY = Global.getSettings().getScreenHeight() / 2f - helpH / 2f;
        float top = helpY + helpH - 12f;
        keyRowsTop = top - (MOUSE_HELP.length + 3) * HELP_LINE;
        float[] reset = resetRect();
        float mx = mouseX();
        float my = mouseY();

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0.02f, 0.03f, 0.05f, 0.93f);
        quad(helpX, helpY, helpW, helpH);
        GL11.glColor4f(0.3f, 0.5f, 0.6f, 0.8f);
        outline(helpX, helpY, helpW, helpH);
        for (int i = 0; i < keys; i++) {
            float rowY = keyRowsTop - (i + 1) * KEY_ROW;
            boolean picked = Keybinds.ACTIONS[i][0].equals(rebinding);
            boolean over = mx >= helpX && mx <= helpX + helpW && my >= rowY && my < rowY + KEY_ROW;
            if (picked) GL11.glColor4f(0.45f, 0.35f, 0.08f, 0.9f);
            else if (over) GL11.glColor4f(0.12f, 0.3f, 0.4f, 0.8f);
            else continue;
            quad(helpX + 8f, rowY, helpW - 16f, KEY_ROW);
        }
        boolean overReset = mx >= reset[0] && mx <= reset[0] + reset[2] && my >= reset[1] && my <= reset[1] + reset[3];
        GL11.glColor4f(overReset ? 0.35f : 0.2f, overReset ? 0.2f : 0.12f, 0.1f, 0.9f);
        quad(reset[0], reset[1], reset[2], reset[3]);
        GL11.glPopAttrib();

        Color plain = new Color(210, 225, 235);
        Color gold = new Color(255, 215, 110);
        int label = 0;
        drawPanelText(helpLabels, helpText, label++, "RTS CONTROLS   (click outside to close)", helpX + 14f, top - HELP_LINE, helpW - 28f, gold);
        for (int i = 0; i < MOUSE_HELP.length; i++) {
            drawPanelText(helpLabels, helpText, label++, MOUSE_HELP[i], helpX + 14f, top - (i + 2) * HELP_LINE, helpW - 28f, plain);
        }
        drawPanelText(helpLabels, helpText, label++, "KEYS   (click one, then press its new key or a side mouse button; Esc cancels)",
                helpX + 14f, keyRowsTop + 2f, helpW - 28f, gold);
        for (int i = 0; i < keys; i++) {
            String[] action = Keybinds.ACTIONS[i];
            float rowY = keyRowsTop - (i + 1) * KEY_ROW + 2f;
            boolean picked = action[0].equals(rebinding);
            String keyText = picked ? "press a key or mouse button..." : InputKeys.name(Keybinds.get(action[0]));
            drawPanelText(helpLabels, helpText, label++, action[1], helpX + 18f, rowY, helpW - 200f, plain);
            drawPanelText(helpLabels, helpText, label++, keyText, helpX + helpW - 170f, rowY, 156f, picked ? gold : new Color(150, 220, 255));
        }
        drawPanelText(helpLabels, helpText, label++, "Reset to default keys", reset[0] + 12f, reset[1] + 3f, reset[2] - 16f, gold);
    }

    // ------------------------------------------------------------------ ship card

    private ShipAPI boardFocus;
    private static final float BOARD_BTN_H = 22f;
    private static final float BOARD_BTN_GAP = 4f;
    private static final float ROW_RULE_H = 8f;
    private static final float ROW_BUTTONS_H = BOARD_BTN_H + 6f;
    private float boardBtnY = -1f;
    private ShipAPI boardBtnShip;

    private float boardBtnW() {
        int n = BoardingCombatPlugin.BoardOrder.values().length;
        return (PANEL_WIDTH - 12f - (n - 1) * BOARD_BTN_GAP) / n;
    }

    private float boardBtnX(int i) {
        return PANEL_X + 6f + i * (boardBtnW() + BOARD_BTN_GAP);
    }

    private boolean inspectable(ShipAPI ship) {
        return ship != null && !units.containsKey(ship) && !AmmoRules.isShuttle(ship)
                && !ship.getCustomData().containsKey(EscapePodPlugin.POD_KEY);
    }

    private ShipAPI inspected() {
        if (boardFocus == null || mode != Mode.RTS) return null;
        if (!boardFocus.isAlive() || boardFocus.isHulk() || !engine.isEntityInPlay(boardFocus)
                || boardFocus.getOwner() == 100 || FogOfWar.isHidden(boardFocus) || units.containsKey(boardFocus)) {
            boardFocus = null;
            return null;
        }
        return boardFocus;
    }

    private BoardingCombatPlugin.BoardOrder boardButtonAt(float x, float y) {
        if (boardBtnY < 0f || boardBtnShip == null) return null;
        if (y < boardBtnY || y > boardBtnY + BOARD_BTN_H) return null;
        BoardingCombatPlugin.BoardOrder[] orders = BoardingCombatPlugin.BoardOrder.values();
        for (int i = 0; i < orders.length; i++) {
            if (x >= boardBtnX(i) && x <= boardBtnX(i) + boardBtnW()) return orders[i];
        }
        return null;
    }

    /** A card row: text, bar, divider, or boarding buttons. */
    private static final class CardRow {
        static final int TEXT = 0, BAR = 1, RULE = 2, BUTTONS = 3;
        int kind;
        String text;
        Color color;
        float level = -1f;
        float level2 = -1f;
        float[] barColor;
        String value;
        boolean indent;

        float height() {
            return kind == RULE ? ROW_RULE_H : kind == BUTTONS ? ROW_BUTTONS_H : PANEL_ROW_HEIGHT;
        }
    }

    private void textRows(List<CardRow> rows, String text, Color color, float width, boolean indent) {
        for (String line : wrap(text, width - (indent ? 12f : 0f))) {
            CardRow row = new CardRow();
            row.kind = CardRow.TEXT;
            row.text = line;
            row.color = color;
            row.indent = indent;
            rows.add(row);
        }
    }

    private void barRow(List<CardRow> rows, String label, float level, float level2, float[] color, String value, Color textColor) {
        CardRow row = new CardRow();
        row.kind = CardRow.BAR;
        row.text = label;
        row.level = RtsMath.clamp(level, 0f, 1f);
        row.level2 = level2;
        row.barColor = color;
        row.value = value;
        row.color = textColor;
        rows.add(row);
    }

    private static float rowsHeight(List<CardRow> rows) {
        float h = 0f;
        for (CardRow row : rows) h += row.height();
        return h;
    }

    private LabelAPI wrapProbe;
    private final Map<String, List<String>> wrapCache = new HashMap<String, List<String>>();

    private List<String> wrap(String text, float width) {
        String key = Math.round(width) + "\u0000" + text;
        List<String> known = wrapCache.get(key);
        if (known != null) return known;
        if (wrapCache.size() > 600) wrapCache.clear();
        List<String> out = new ArrayList<String>();
        try {
            if (wrapProbe == null) wrapProbe = Global.getSettings().createLabel(" ", Fonts.DEFAULT_SMALL);
            float room = width - 4f;
            StringBuilder line = new StringBuilder();
            for (String word : text.split(" ")) {
                String trial = line.length() == 0 ? word : line + " " + word;
                if (line.length() == 0 || wrapProbe.computeTextWidth(trial) <= room) {
                    line.setLength(0);
                    line.append(trial);
                } else {
                    out.add(line.toString().trim());
                    line.setLength(0);
                    line.append(word);
                }
            }
            if (line.toString().trim().length() > 0 || out.isEmpty()) out.add(line.toString().trim());
        } catch (RuntimeException e) {
            out.clear();
            out.add(text);
        }
        wrapCache.put(key, out);
        return out;
    }

    private final List<LabelAPI> cardPool = new ArrayList<LabelAPI>();
    private final List<String> cardPoolKeys = new ArrayList<String>();
    private int cardPoolUsed;

    private void cardText(String text, float x, float y, float width, Color color) {
        int i = cardPoolUsed++;
        while (cardPool.size() <= i) {
            cardPool.add(null);
            cardPoolKeys.add(null);
        }
        try {
            LabelAPI label = cardPool.get(i);
            String key = text + "\u0000" + Math.round(width);
            if (label == null) {
                label = Global.getSettings().createLabel(text, Fonts.DEFAULT_SMALL);
                cardPool.set(i, label);
                cardPoolKeys.set(i, null);
            }
            if (!key.equals(cardPoolKeys.get(i))) {
                label.setText(fit(label, text, width));
                cardPoolKeys.set(i, key);
            }
            label.setColor(color);
            label.getPosition().setSize(width, PANEL_ROW_HEIGHT);
            label.getPosition().setLocation(x, y);
            label.render(1f);
        } catch (RuntimeException e) {
            cardPool.set(i, null);
        }
    }

    private static String fullName(ShipAPI ship) {
        String hull = ship.getHullSpec() == null ? "" : ship.getHullSpec().getHullName();
        String name = ship.getName();
        return name == null || name.isEmpty() ? hull : name + " (" + hull + ")";
    }

    private static float armorLevel(ShipAPI ship) {
        ArmorGridAPI grid = ship.getArmorGrid();
        if (grid == null) return 1f;
        float[][] cells = grid.getGrid();
        float total = 0f;
        int count = 0;
        for (int x = 0; x < cells.length; x++) {
            for (int y = 0; y < cells[x].length; y++) {
                total += RtsMath.clamp(grid.getArmorFraction(x, y), 0f, 1f);
                count++;
            }
        }
        return count > 0 ? total / count : 1f;
    }

    /**
     * A ship's card. Others' ships show only what's visible from outside; a boarding action adds
     * its fight, log and (for our boarders) order buttons.
     */
    private void renderCard(ShipAPI ship, UnitState unit) {
        cardPoolUsed = 0;
        boolean enemy = ship.getOwner() == 1;
        boolean intel = !enemy;
        float sideX = PANEL_X + 6f + CARD_ARMOR_BOX + 10f;
        float sideW = PANEL_X + PANEL_WIDTH - 6f - sideX;
        float fullX = PANEL_X + 8f;
        float fullW = PANEL_WIDTH - 14f;
        Color plain = new Color(210, 215, 220);
        Color dim = new Color(160, 170, 180);
        Color lilac = new Color(225, 150, 255);
        List<CardRow> side = new ArrayList<CardRow>();
        List<CardRow> full = new ArrayList<CardRow>();

        // Header
        Color head = unit != null ? (unit.isAiFlown() ? new Color(255, 190, 90) : new Color(170, 255, 180))
                : enemy ? new Color(255, 125, 105) : ship.isAlly() ? new Color(255, 220, 90) : new Color(170, 255, 180);
        String who = unit != null ? unit.describe() : enemy ? "enemy" : ship.isAlly() ? "allied" : "ours, ship AI";
        textRows(side, fullName(ship) + "  -  " + who, head, sideW, false);

        // Hull, flux, power
        float hull = RtsMath.clamp(ship.getHullLevel(), 0f, 1f);
        float flux = RtsMath.clamp(ship.getFluxLevel(), 0f, 1f);
        barRow(side, "Hull", hull, -1f, new float[] {1f - hull * 0.75f, 0.25f + hull * 0.65f, 0.25f},
                Math.round(ship.getHitpoints()) + " / " + Math.round(ship.getMaxHitpoints()), plain);
        barRow(side, "Flux", flux, RtsMath.clamp(ship.getHardFluxLevel(), 0f, flux), new float[] {0.35f, 0.6f, 1f},
                Math.round(ship.getCurrFlux()) + " / " + Math.round(ship.getMaxFlux()), plain);
        PowerCombatPlugin powerPlugin = PowerCombatPlugin.get(engine);
        float power = powerPlugin == null || !intel ? -1f : powerPlugin.powerOf(ship);
        if (power >= 0f) {
            String state = powerPlugin.isMeltedDown(ship) ? "  REACTOR DOWN" : powerPlugin.isOutOfPower(ship) ? "  DEPLETED" : "";
            barRow(side, "Power", power / Math.max(1f, powerPlugin.capacityOf(ship)), -1f, powerPlugin.barColor(ship),
                    Math.round(power) + " / " + Math.round(powerPlugin.capacityOf(ship)) + state,
                    state.isEmpty() ? plain : new Color(255, 150, 90));
        }
        int[] era = ExplosiveReactiveArmor.charges(ship);
        textRows(side, "Armor " + Math.round(armorLevel(ship) * 100f) + "%"
                + (era != null ? "   ERA " + era[0] + "/" + era[1] : "")
                + "   Readiness " + Math.round(ship.getCurrentCR() * 100f) + "%", plain, sideW, false);

        // Status tags
        List<String> tags = new ArrayList<String>();
        boolean unsaved = unit != null && unit.stance != unit.savedStance;
        if (unit != null) tags.add("Stance: " + unit.stance.label + (unsaved ? " (not saved)" : ""));
        if (unit != null && unit.formation != null) {
            tags.add(unit.formation.type.label + (unit.formation.leader == unit ? " leader" : " formation"));
        }
        if (intel) {
            int flaresLeft = IrCombatPlugin.flaresLeft(engine, ship);
            if (flaresLeft >= 0) {
                int reloadsLeft = IrCombatPlugin.reloadsLeft(engine, ship);
                tags.add("Flares " + flaresLeft + (reloadsLeft >= 0 ? " (+" + reloadsLeft + " reloads)" : ""));
            }
            if (RadarCombatPlugin.isSilent(engine, ship)) tags.add("RADAR OFF");
            if (RadarCombatPlugin.hasRadarMissiles(ship)) {
                tags.add(RadarCombatPlugin.isHoldingMissiles(engine, ship) ? "RADAR MSL HELD" : "Radar msl free");
            }
            if (HangarCombatPlugin.isHeld(ship)) tags.add("HANGAR CLOSED");
        }
        String jammer = RadarCombatPlugin.jammerStatus(engine, ship);
        if (jammer != null && (intel || jammer.toUpperCase().contains("JAMMING"))) tags.add(jammer);
        if (!intel) {
            if (SupportRoles.isSupplier(ship)) tags.add("Supply ship");
            if (SupportRoles.isTender(ship)) tags.add("Repair tender");
            if (BoardingCombatPlugin.hasBay(ship)) tags.add("Boarding bay");
            if (SecurityForge.has(ship)) tags.add("Security forge");
        }
        if (power < 0f && powerPlugin != null && powerPlugin.isMeltedDown(ship)) tags.add(0, "REACTOR DOWN");
        if (!tags.isEmpty()) {
            StringBuilder line = new StringBuilder();
            for (String tag : tags) line.append(line.length() == 0 ? "" : "   |   ").append(tag);
            textRows(full, line.toString(), unsaved ? new Color(255, 210, 90) : plain, fullW, false);
        }
        BoardingCombatPlugin boarding = BoardingCombatPlugin.get(engine);
        if (intel) {
            Object stock = ship.getCustomData().get(AmmoRules.SUPPLY_KEY);
            Object stockMax = ship.getCustomData().get(AmmoRules.SUPPLY_MAX_KEY);
            if (stock instanceof Float && stockMax instanceof Float) {
                textRows(full, "Supply stock " + Math.round((Float) stock) + " / " + Math.round((Float) stockMax),
                        new Color(120, 220, 255), fullW, false);
            }
            int security = boarding == null ? -1 : boarding.securityDroidsOf(ship);
            if (security >= 0) {
                textRows(full, "Security droids " + security + " / " + SecurityForge.droidsFor(ship.getHullSize())
                        + "   |   defence only, rebuilt after battle", new Color(170, 200, 255), fullW, false);
            }
            boolean bay = BoardingCombatPlugin.hasBay(ship);
            int marinesAboard = boarding == null ? 0 : boarding.marinesAboard(ship);
            if (bay || marinesAboard > 0) {
                String text = "Marines aboard " + marinesAboard;
                if (bay && boarding != null) {
                    float wait = boarding.cooldownOf(ship);
                    text += wait > 0f ? "   |   shuttles ready in " + (int) Math.ceil(wait) + " s" : "   |   shuttles ready";
                }
                textRows(full, text, lilac, fullW, false);
            }
            RepairCombatPlugin repair = RepairCombatPlugin.get(engine);
            int dronesLeft = repair == null ? -1 : repair.dronesLeft(ship);
            if (dronesLeft >= 0) {
                textRows(full, "Repair drones left " + dronesLeft + "   |   spare parts " + Math.round(repair.partsLeft(ship))
                        + " / " + Math.round(RepairCombatPlugin.partsOf(ship)), new Color(120, 255, 150), fullW, false);
            }

            // Ammo per weapon type
            Object ammoPlugin = engine.getCustomData().get(AmmoCombatPlugin.ENGINE_KEY);
            Map<String, int[]> ammo = new LinkedHashMap<String, int[]>();
            for (WeaponAPI weapon : ship.getAllWeapons()) {
                if (weapon.isDecorative() || !weapon.usesAmmo()) continue;
                int[] sum = ammo.get(weapon.getDisplayName());
                if (sum == null) {
                    sum = new int[] {0, 0, 0, 0};
                    ammo.put(weapon.getDisplayName(), sum);
                }
                sum[0] += weapon.getAmmo();
                sum[1] += weapon.getMaxAmmo();
                sum[2]++;
                if (ammoPlugin instanceof AmmoCombatPlugin) {
                    int reserve = ((AmmoCombatPlugin) ammoPlugin).reserveOf(weapon);
                    if (reserve >= 0) sum[3] += reserve + 1;
                }
            }
            for (Map.Entry<String, int[]> entry : ammo.entrySet()) {
                int[] sum = entry.getValue();
                String count = sum[0] + " / " + sum[1] + (sum[3] > 0 ? "  (+" + (sum[3] - sum[2]) + ")" : "");
                float share = sum[1] > 0 ? (float) sum[0] / sum[1] : 1f;
                Color color = share < 0.15f ? new Color(255, 110, 95) : share < 0.6f ? new Color(255, 210, 90) : dim;
                textRows(full, entry.getKey() + (sum[2] > 1 ? " x" + sum[2] : "") + ":  " + count, color, fullW, false);
            }
        }

        // Boarding action
        BoardingCombatPlugin.BoardingInfo info = boarding == null ? null : boarding.info(ship);
        if (info != null) {
            CardRow rule = new CardRow();
            rule.kind = CardRow.RULE;
            full.add(rule);
            String order = info.withdrawIn >= 0f ? "withdrawing in " + (int) Math.ceil(info.withdrawIn) + " s" : info.order.label;
            String boarders = info.droidAttackers >= info.attackers && info.attackers > 0 ? "droids" : "marines";
            textRows(full, "BOARDING  -  " + info.attackers + " " + boarders
                    + (info.playerMarines > 0 && info.playerMarines < info.attackers ? " (" + info.playerMarines + " yours)" : "")
                    + " vs " + (info.defenders > 0 ? (info.securityDroids > 0 ? (info.defenders - info.securityDroids) + " marines + "
                            + info.securityDroids + " security droids" : info.defenders + (info.droidDefenders ? " droids" : " marines")) : "no defenders")
                    + (info.droidDefenders ? "" : ", " + info.crew + " crew") + "   |   order: " + order, lilac, fullW, false);
            textRows(full, "Garrison " + info.garrison + ", " + info.layout + ", captain " + info.captain
                    + "   |   experience: boarders " + Math.round(info.attackerXp * 100f) + "%, defenders "
                    + Math.round(info.defenderXp * 100f) + "%", plain, fullW, false);
            if (info.rigged != null) {
                textRows(full, info.rigged.toUpperCase() + " RIGGED TO BLOW in " + (int) Math.ceil(info.riggedIn)
                        + " s: beat the defenders or withdraw!", new Color(255, 90, 70), fullW, false);
            }
            StringBuilder decks = new StringBuilder("Decks " + info.taken + "/" + info.decks.size() + ":  ");
            for (int i = 0; i < info.decks.size(); i++) {
                if (i > 0) decks.append("  >  ");
                decks.append(i < info.taken ? "[" + info.decks.get(i) + "]" : info.decks.get(i));
            }
            textRows(full, decks.toString(), plain, fullW, false);
            for (String effect : info.effects) textRows(full, effect, new Color(150, 230, 160), fullW, true);
            for (String event : info.events) textRows(full, event, new Color(255, 200, 120), fullW, true);
            if (info.playerOrders) {
                CardRow buttons = new CardRow();
                buttons.kind = CardRow.BUTTONS;
                buttons.text = info.order.name();
                full.add(buttons);
            }
            List<Object[]> log = boarding.logOf(ship);
            if (!log.isEmpty() && TcoSettings.boardingLogPanel) {
                int lines = Math.min(TcoSettings.boardingLogLines, log.size());
                for (int i = log.size() - lines; i < log.size(); i++) {
                    int good = (Integer) log.get(i)[1];
                    Color color = good > 0 ? new Color(140, 235, 150) : good < 0 ? new Color(255, 130, 110) : new Color(200, 190, 215);
                    textRows(full, (String) log.get(i)[0], color, fullW, true);
                }
            }
        }

        float topBlock = Math.max(rowsHeight(side), CARD_ARMOR_BOX + 4f);
        float height = 6f + topBlock + rowsHeight(full) + 6f;
        float top = PANEL_Y + height;
        panelTop = top;
        float boxX = PANEL_X + 6f;
        float boxY = top - 6f - CARD_ARMOR_BOX;
        float barX = sideX + 44f;
        float barW = 110f;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0.03f, 0.05f, 0.07f, 0.75f);
        quad(PANEL_X, PANEL_Y, PANEL_WIDTH, height);
        if (enemy) GL11.glColor4f(0.7f, 0.3f, 0.25f, 0.5f);
        else if (unit == null) GL11.glColor4f(0.7f, 0.65f, 0.3f, 0.45f);
        else GL11.glColor4f(0.3f, 0.5f, 0.6f, 0.5f);
        outline(PANEL_X, PANEL_Y, PANEL_WIDTH, height);
        GL11.glColor4f(0f, 0f, 0f, 0.5f);
        quad(boxX, boxY, CARD_ARMOR_BOX, CARD_ARMOR_BOX);
        float y = top - 6f;
        for (CardRow row : side) {
            y -= row.height();
            if (row.kind != CardRow.BAR) continue;
            GL11.glColor4f(0.2f, 0.2f, 0.2f, 0.9f);
            quad(barX, y + 5f, barW, 6f);
            GL11.glColor4f(row.barColor[0], row.barColor[1], row.barColor[2], 1f);
            quad(barX, y + 5f, barW * row.level, 6f);
            if (row.level2 >= 0f) {
                GL11.glColor4f(0.8f, 0.9f, 1f, 1f);
                quad(barX, y + 5f, barW * row.level2, 6f);
            }
        }
        BoardingCombatPlugin.BoardOrder hover = null;
        y = top - 6f - topBlock;
        for (CardRow row : full) {
            y -= row.height();
            if (row.kind == CardRow.RULE) {
                GL11.glColor4f(0.6f, 0.4f, 0.8f, 0.45f);
                quad(PANEL_X + 6f, y + row.height() / 2f, PANEL_WIDTH - 12f, 1f);
            } else if (row.kind == CardRow.BUTTONS) {
                boardBtnY = y + 3f;
                boardBtnShip = ship;
                hover = boardButtonAt(mouseX(), mouseY());
                BoardingCombatPlugin.BoardOrder[] orders = BoardingCombatPlugin.BoardOrder.values();
                for (int i = 0; i < orders.length; i++) {
                    if (orders[i].name().equals(row.text)) GL11.glColor4f(0.45f, 0.35f, 0.08f, 0.9f);
                    else if (orders[i] == hover) GL11.glColor4f(0.12f, 0.3f, 0.4f, 0.9f);
                    else GL11.glColor4f(0.05f, 0.08f, 0.1f, 0.85f);
                    quad(boardBtnX(i), boardBtnY, boardBtnW(), BOARD_BTN_H);
                    GL11.glColor4f(0.3f, 0.5f, 0.6f, 0.7f);
                    outline(boardBtnX(i), boardBtnY, boardBtnW(), BOARD_BTN_H);
                }
            }
        }
        GL11.glPopAttrib();
        renderArmorPicture(ship, boxX, boxY);

        y = top - 6f;
        for (CardRow row : side) {
            y -= row.height();
            if (row.kind == CardRow.BAR) {
                cardText(row.text, sideX, y, 42f, row.color);
                cardText(row.value, barX + barW + 8f, y, sideX + sideW - (barX + barW + 8f), row.color);
            } else {
                cardText(row.text, sideX, y, sideW, row.color);
            }
        }
        y = top - 6f - topBlock;
        Color gold = new Color(255, 215, 110);
        for (CardRow row : full) {
            y -= row.height();
            if (row.kind == CardRow.TEXT) {
                float x = fullX + (row.indent ? 12f : 0f);
                cardText(row.text, x, y, fullW - (x - fullX), row.color);
            } else if (row.kind == CardRow.BUTTONS) {
                BoardingCombatPlugin.BoardOrder[] orders = BoardingCombatPlugin.BoardOrder.values();
                for (int i = 0; i < orders.length; i++) {
                    cardText(orders[i].label, boardBtnX(i) + 6f, boardBtnY + 3f, boardBtnW() - 8f,
                            orders[i].name().equals(row.text) ? gold : plain);
                }
            }
        }
        if (hover != null) {
            List<String> tip = wrap(BoardingCombatPlugin.orderTip(hover), PANEL_WIDTH);
            for (int i = 0; i < tip.size(); i++) {
                cardText(tip.get(i), PANEL_X, panelTop + 6f + (tip.size() - 1 - i) * PANEL_ROW_HEIGHT, PANEL_WIDTH, new Color(255, 225, 140));
            }
        }
    }

    private float panelTop = PANEL_Y;

    private void renderPanelOnly() {
        if (selected.isEmpty()) return;

        List<UnitState> rows = new ArrayList<UnitState>();
        for (ShipAPI ship : selected) {
            UnitState unit = units.get(ship);
            if (unit != null) rows.add(unit);
        }
        if (rows.isEmpty()) return;
        if (rows.size() == 1) {
            renderShipCard(rows.get(0));
            return;
        }
        int shown = Math.min(rows.size(), PANEL_MAX_ROWS);
        int hidden = rows.size() - shown;
        int lines = shown + (hidden > 0 ? 1 : 0);
        float height = lines * PANEL_ROW_HEIGHT + 8f;
        panelTop = PANEL_Y + height;

        float barX = PANEL_X + 196f;
        float barWidth = 56f;
        float fluxX = barX + barWidth + 6f;

        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glColor4f(0.03f, 0.05f, 0.07f, 0.7f);
        quad(PANEL_X, PANEL_Y, PANEL_WIDTH, height);

        for (int i = 0; i < shown; i++) {
            ShipAPI ship = rows.get(i).ship;
            float y = rowY(i, lines) + 5f;

            float hull = RtsMath.clamp(ship.getHullLevel(), 0f, 1f);
            GL11.glColor4f(0.2f, 0.2f, 0.2f, 0.9f);
            quad(barX, y, barWidth, 6f);
            GL11.glColor4f(1f - hull * 0.75f, 0.25f + hull * 0.65f, 0.25f, 1f);
            quad(barX, y, barWidth * hull, 6f);

            float flux = RtsMath.clamp(ship.getFluxLevel(), 0f, 1f);
            float hard = RtsMath.clamp(ship.getHardFluxLevel(), 0f, flux);
            GL11.glColor4f(0.2f, 0.2f, 0.2f, 0.9f);
            quad(fluxX, y, barWidth, 6f);
            GL11.glColor4f(0.35f, 0.6f, 1f, 1f);
            quad(fluxX, y, barWidth * flux, 6f);
            GL11.glColor4f(0.8f, 0.9f, 1f, 1f);
            quad(fluxX, y, barWidth * hard, 6f);
        }
        GL11.glPopAttrib();

        for (int i = 0; i < shown; i++) {
            UnitState unit = rows.get(i);
            float y = rowY(i, lines);
            Color color = unit.isAiFlown() ? new Color(255, 190, 90) : new Color(170, 255, 180);
            drawPanelText(panelNames, panelNameText, i, shipLabel(unit.ship), PANEL_X + 6f, y, 186f, color);
            drawPanelText(panelStatus, panelStatusText, i, unit.describe(), fluxX + barWidth + 8f, y, 104f, color);
        }
        if (hidden > 0) {
            drawPanelText(panelNames, panelNameText, PANEL_MAX_ROWS, "+" + hidden + " more",
                    PANEL_X + 6f, rowY(shown, lines), 186f, new Color(200, 200, 200));
        }
    }

    private void renderShipCard(UnitState unit) {
        renderCard(unit.ship, unit);
    }

    private float cardLineY(float top, int line) {
        return top - 6f - (line + 1) * PANEL_ROW_HEIGHT;
    }

    private float renderArmorPicture(ShipAPI ship, float boxX, float boxY) {
        ArmorGridAPI grid = ship.getArmorGrid();
        float centreX = boxX + CARD_ARMOR_BOX / 2f;
        float centreY = boxY + CARD_ARMOR_BOX / 2f;

        float nativeW = ship.getCollisionRadius() * 2f;
        float nativeH = nativeW;
        SpriteAPI sprite = null;
        try {
            String name = ship.getHullSpec().getSpriteName();
            sprite = cardSprites.get(name);
            if (sprite == null) {
                sprite = Global.getSettings().getSprite(name);
                cardSprites.put(name, sprite);
                cardSpriteSizes.put(name, new float[] {sprite.getWidth(), sprite.getHeight()});
            }
            float[] size = cardSpriteSizes.get(name);
            nativeW = size[0];
            nativeH = size[1];
        } catch (RuntimeException e) {
            sprite = null;
        }
        if (grid != null) {
            float cell = grid.getCellSize();
            nativeW = Math.max(nativeW, (grid.getLeftOf() + grid.getRightOf()) * cell);
            nativeH = Math.max(nativeH, (grid.getAbove() + grid.getBelow()) * cell);
        }
        float scale = (CARD_ARMOR_BOX - 6f) / Math.max(1f, Math.max(nativeW, nativeH));

        if (sprite != null) {
            float[] size = cardSpriteSizes.get(ship.getHullSpec().getSpriteName());
            sprite.setSize(size[0] * scale, size[1] * scale);
            sprite.setAngle(0f);
            sprite.setAlphaMult(0.9f);
            sprite.renderAtCenter(centreX, centreY);
        }
        if (grid == null) return 1f;

        float[][] cells = grid.getGrid();
        float cell = grid.getCellSize() * scale;
        float total = 0f;
        int count = 0;
        GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        for (int x = 0; x < cells.length; x++) {
            for (int y = 0; y < cells[x].length; y++) {
                float fraction = RtsMath.clamp(grid.getArmorFraction(x, y), 0f, 1f);
                total += fraction;
                count++;
                if (sprite != null) continue;
                // Cell 0,0 is the lower left
                float cx = centreX + (x - grid.getLeftOf()) * cell;
                float cy = centreY + (y - grid.getBelow()) * cell;
                GL11.glColor4f(1f - fraction * 0.7f, 0.2f + fraction * 0.6f, 0.2f, 0.85f);
                quad(cx, cy, cell, cell);
            }
        }
        GL11.glPopAttrib();
        return count > 0 ? total / count : 1f;
    }

    private float rowY(int row, int lines) {
        return PANEL_Y + 4f + (lines - 1 - row) * PANEL_ROW_HEIGHT;
    }

    private String shipLabel(ShipAPI ship) {
        String hull = ship.getHullSpec() == null ? "" : ship.getHullSpec().getHullName();
        String name = ship.getName();
        String text = name == null || name.isEmpty() ? hull : name + " (" + hull + ")";
        return text.length() > 28 ? text.substring(0, 27) + "." : text;
    }

    private void drawPanelText(LabelAPI[] labels, String[] cache, int index, String text,
            float x, float y, float width, Color color) {
        try {
            // Cache by text and width; labels are cut to one line
            String key = text + "\u0000" + Math.round(width);
            if (labels[index] == null) {
                labels[index] = Global.getSettings().createLabel(text, Fonts.DEFAULT_SMALL);
                labels[index].setText(fit(labels[index], text, width));
                cache[index] = key;
            } else if (!key.equals(cache[index])) {
                labels[index].setText(fit(labels[index], text, width));
                cache[index] = key;
            }
            LabelAPI label = labels[index];
            label.setColor(color);
            label.getPosition().setSize(width, PANEL_ROW_HEIGHT);
            label.getPosition().setLocation(x, y);
            label.render(1f);
        } catch (RuntimeException e) {
            labels[index] = null;
        }
    }

    private static String fit(LabelAPI label, String text, float width) {
        float room = width - 4f;
        if (label.computeTextWidth(text) <= room) return text;
        String cut = text;
        while (cut.length() > 1) {
            cut = cut.substring(0, cut.length() - 1).trim();
            if (label.computeTextWidth(cut + ".") <= room) return cut + ".";
        }
        return cut;
    }

    private void quad(float x, float y, float width, float height) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + width, y);
        GL11.glVertex2f(x + width, y + height);
        GL11.glVertex2f(x, y + height);
        GL11.glEnd();
    }

    private SpriteAPI currentButton() {
        switch (mode) {
            case RTS:
                return btnRts;
            case DIRECT:
                return btnDirect;
            default:
                return btnVanilla;
        }
    }

    private float buttonCenterX() {
        return Global.getSettings().getScreenWidth() * TcoSettings.buttonX;
    }

    private float buttonCenterY() {
        return Global.getSettings().getScreenHeight() - TcoSettings.buttonTopOffset;
    }

    private boolean isOverButton(float x, float y) {
        SpriteAPI sprite = currentButton();
        if (sprite == null) return false;
        return Math.abs(x - buttonCenterX()) <= sprite.getWidth() / 2f
                && Math.abs(y - buttonCenterY()) <= sprite.getHeight() / 2f;
    }
}
