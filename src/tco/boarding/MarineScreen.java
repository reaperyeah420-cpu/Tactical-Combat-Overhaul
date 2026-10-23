package tco.boarding;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.input.Keyboard;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.GameState;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.CampaignUIAPI;
import com.fs.starfarer.api.campaign.CustomDialogDelegate;
import com.fs.starfarer.api.campaign.CustomUIPanelPlugin;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.InteractionDialogPlugin;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.combat.EngagementResultAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.CustomPanelAPI;
import com.fs.starfarer.api.ui.LabelAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import tco.InputKeys;
import tco.TcoSettings;
import tco.Typing;
import tco.ammo.AmmoRules;
import tco.hullmods.CombatInformationCenter;
import tco.hullmods.MilitiaLockers;
import tco.hullmods.SecurityForge;
import tco.repair.RepairCombatPlugin;
import tco.rts.Stance;
import tco.tug.TowCombatPlugin;

/**
 * Campaign screen for per-ship marine targets and RTS stances, as a list or a card grid.
 * Fixed-coordinate scrolling panel; view and sort changes rebuild it, keeping unsaved changes.
 */
public final class MarineScreen {

    private static final float WIDTH = 1040f;
    private static final float HEIGHT = 700f;
    private static final float TOP = 172f;
    private static final float LIST_TOP = TOP + 16f;
    private static final float ROW = 46f;
    private static final float CARD_W = 248f;
    private static final float CARD_H = 200f;
    private static final int CARD_COLS = 4;

    private enum Sort {
        FLEET("Fleet order"), ROLE("Role"), SIZE("Size"), POINTS("Deployment points");

        final String label;

        Sort(String label) {
            this.label = label;
        }
    }

    /** Current view and unsaved changes, kept across rebuilds. */
    private static class View {
        Sort sort = Sort.FLEET;
        boolean grid = false;
        Map<String, MarineAllocation.Entry> entries;
        final Map<String, Stance> stances = new HashMap<String, Stance>();

        View() {
            loadPrefs(this);
        }
    }

    private static final String PREFS_FILE = "tco_marine_screen";

    private static void loadPrefs(View view) {
        try {
            if (!Global.getSettings().fileExistsInCommon(PREFS_FILE)) return;
            String text = Global.getSettings().readTextFileFromCommon(PREFS_FILE);
            for (String line : text.split("\\n")) {
                String[] kv = line.trim().split("=", 2);
                if (kv.length != 2) continue;
                if ("grid".equals(kv[0])) view.grid = Boolean.parseBoolean(kv[1].trim());
                else if ("sort".equals(kv[0])) {
                    try {
                        view.sort = Sort.valueOf(kv[1].trim());
                    } catch (IllegalArgumentException e) {
                    }
                }
            }
        } catch (Exception e) {
            Global.getLogger(MarineScreen.class).warn("TCO: could not read the Marines screen settings", e);
        }
    }

    private static void savePrefs(View view) {
        try {
            Global.getSettings().writeTextFileToCommon(PREFS_FILE, "sort=" + view.sort.name() + "\ngrid=" + view.grid + "\n");
        } catch (Exception e) {
            Global.getLogger(MarineScreen.class).warn("TCO: could not save the Marines screen settings", e);
        }
    }

    private MarineScreen() {
    }

    /** Opens the screen on its key, from the campaign map. */
    public static class Hotkey implements EveryFrameScript {
        private boolean wasDown = false;

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
            TcoSettings.load();
            boolean down = InputKeys.isDown(TcoSettings.keyMarineScreen) && !Typing.active();
            boolean pressed = down && !wasDown;
            wasDown = down;
            if (!pressed || Global.getCurrentState() != GameState.CAMPAIGN) return;
            CampaignUIAPI ui = Global.getSector().getCampaignUI();
            if (ui == null || ui.isShowingDialog() || ui.isShowingMenu() || ui.getCurrentCoreTab() != null) return;
            ui.showInteractionDialog(new Dialog(), Global.getSector().getPlayerFleet());
        }
    }

    /** An empty dialog hosting the panel. */
    public static class Dialog implements InteractionDialogPlugin {
        private InteractionDialogAPI dialog;
        View reopen;

        @Override
        public void init(InteractionDialogAPI dialog) {
            this.dialog = dialog;
            dialog.showCustomDialog(WIDTH, HEIGHT, new Delegate(dialog, this, new View()));
        }

        @Override
        public void advance(float amount) {
            if (reopen == null || dialog == null) return;
            View view = reopen;
            reopen = null;
            dialog.showCustomDialog(WIDTH, HEIGHT, new Delegate(dialog, this, view));
        }

        @Override
        public void optionSelected(String optionText, Object optionData) {
        }

        @Override
        public void optionMousedOver(String optionText, Object optionData) {
        }

        @Override
        public void backFromEngagement(EngagementResultAPI battleResult) {
        }

        @Override
        public Object getContext() {
            return null;
        }

        @Override
        public Map<String, MemoryAPI> getMemoryMap() {
            return null;
        }
    }

    private static class Row {
        FleetMemberAPI member;
        MarineAllocation.Entry entry;
        int capacity;
        boolean automated;
        ButtonAPI zero;
        ButtonAPI minusTen;
        ButtonAPI minusOne;
        ButtonAPI plusOne;
        ButtonAPI plusTen;
        ButtonAPI max;
        ButtonAPI priority;
        ButtonAPI excluded;
        ButtonAPI stanceButton;
        LabelAPI target;
        LabelAPI assigned;
    }

    /** A support or command job a ship's hullmods give it, shown as a coloured tag. */
    private enum Role {
        CIC(CombatInformationCenter.ID, "[CIC]", new Color(90, 200, 255)),
        AUX_CIC(CombatInformationCenter.AUX_ID, "[AUX CIC]", new Color(150, 175, 255)),
        SUPPLY(AmmoRules.HULLMOD_ID, "[SUPPLY]", new Color(255, 200, 70)),
        TENDER(RepairCombatPlugin.HULLMOD_ID, "[TENDER]", new Color(110, 230, 120)),
        BOARDING(BoardingCombatPlugin.HULLMOD_ID, "[BOARDING]", new Color(255, 110, 90)),
        TUG(TowCombatPlugin.HULLMOD_ID, "[TUG]", new Color(210, 160, 255));

        final String hullmod;
        final String tag;
        final Color color;

        Role(String hullmod, String tag, Color color) {
            this.hullmod = hullmod;
            this.tag = tag;
            this.color = color;
        }
    }

    static List<Role> rolesOf(FleetMemberAPI member) {
        List<Role> roles = new ArrayList<Role>();
        if (member.getVariant() == null) return roles;
        for (Role role : Role.values()) {
            if (member.getVariant().hasHullMod(role.hullmod)) roles.add(role);
        }
        return roles;
    }

    static String tagsOf(List<Role> roles) {
        StringBuilder text = new StringBuilder();
        for (Role role : roles) text.append(text.length() == 0 ? "" : " ").append(role.tag);
        return text.toString();
    }

    /** Colours each tag in a label that contains them. */
    static void colourTags(LabelAPI label, List<Role> roles) {
        if (roles.isEmpty()) return;
        String[] tags = new String[roles.size()];
        Color[] colors = new Color[roles.size()];
        for (int i = 0; i < roles.size(); i++) {
            tags[i] = roles.get(i).tag;
            colors[i] = roles.get(i).color;
        }
        label.setHighlight(tags);
        label.setHighlightColors(colors);
    }

    static boolean isAutomated(FleetMemberAPI member) {
        if (member.getMinCrew() > 0f) return false;
        return Misc.isAutomated(member) || member.getMaxCrew() <= 0f;
    }

    static int droidsFor(FleetMemberAPI member) {
        HullSize size = member.getHullSpec().getHullSize();
        float droids = size == HullSize.CAPITAL_SHIP ? TcoSettings.droidsCapital : size == HullSize.CRUISER ? TcoSettings.droidsCruiser
                : size == HullSize.DESTROYER ? TcoSettings.droidsDestroyer : TcoSettings.droidsFrigate;
        if (member.getVariant() != null && member.getVariant().getSMods().contains(BoardingCombatPlugin.HULLMOD_ID)) {
            droids *= 1f + TcoSettings.droidSModBonus;
        }
        return Math.round(droids);
    }

    static boolean hasForge(FleetMemberAPI member) {
        return member.getVariant() != null && member.getVariant().hasHullMod(SecurityForge.ID);
    }

    /** "droids now/full (rebuilding)" for a Security Forge ship, after the prefix; empty otherwise. */
    static String securityText(FleetMemberAPI member, String prefix) {
        if (!hasForge(member)) return "";
        int now = Math.round(SecurityDroids.current(member));
        int full = SecurityDroids.full(member);
        return prefix + "droids " + now + "/" + full + (now < full ? " (rebuilding)" : "");
    }

    /** Crew who would fight boarders as militia (Militia Lockers), else 0. */
    static int militiaOf(FleetMemberAPI member) {
        return MilitiaLockers.has(member) ? Math.round(member.getMinCrew()) : 0;
    }

    private static class Delegate implements CustomDialogDelegate, CustomUIPanelPlugin {

        private final InteractionDialogAPI dialog;
        private final Dialog host;
        private final View view;
        private final List<FleetMemberAPI> members = new ArrayList<FleetMemberAPI>();
        private final List<FleetMemberAPI> marineShips = new ArrayList<FleetMemberAPI>();
        private final List<Row> rows = new ArrayList<Row>();
        private final int marines;
        private LabelAPI summary;
        private CustomDialogCallback callback;
        private boolean reopening = false;

        private final Map<Sort, ButtonAPI> sortButtons = new HashMap<Sort, ButtonAPI>();
        private ButtonAPI viewButton;
        private ButtonAPI fillButton;
        private ButtonAPI spreadButton;
        private ButtonAPI noneButton;
        private ButtonAPI resetButton;

        Delegate(InteractionDialogAPI dialog, Dialog host, View view) {
            this.dialog = dialog;
            this.host = host;
            this.view = view;
            CampaignFleetAPI fleet = Global.getSector().getPlayerFleet();
            for (FleetMemberAPI member : fleet.getFleetData().getMembersListCopy()) {
                if (!MarineAllocation.isEligible(member)) continue;
                members.add(member);
                if (!isAutomated(member)) marineShips.add(member);
            }
            if (view.entries == null) view.entries = MarineAllocation.loadAll(marineShips);
            for (FleetMemberAPI member : members) {
                if (!view.stances.containsKey(member.getId())) view.stances.put(member.getId(), Stance.load(member.getId()));
            }
            sort(members, view.sort);
            marines = fleet.getCargo().getMarines();
        }

        private static void sort(List<FleetMemberAPI> list, final Sort sort) {
            if (sort == Sort.FLEET) return;
            Collections.sort(list, new Comparator<FleetMemberAPI>() {
                @Override
                public int compare(FleetMemberAPI a, FleetMemberAPI b) {
                    if (sort == Sort.ROLE) {
                        int byRole = roleRank(a) - roleRank(b);
                        if (byRole != 0) return byRole;
                    }
                    if (sort == Sort.SIZE) {
                        int bySize = b.getHullSpec().getHullSize().ordinal() - a.getHullSpec().getHullSize().ordinal();
                        if (bySize != 0) return bySize;
                    }
                    return Float.compare(b.getDeploymentPointsCost(), a.getDeploymentPointsCost());
                }
            });
        }

        private static int roleRank(FleetMemberAPI member) {
            List<Role> roles = rolesOf(member);
            return roles.isEmpty() ? Role.values().length : roles.get(0).ordinal();
        }

        @Override
        public void createCustomDialog(CustomPanelAPI panel, CustomDialogCallback callback) {
            this.callback = callback;
            TooltipMakerAPI list = panel.createUIElement(WIDTH, HEIGHT, true);
            Color base = Misc.getBasePlayerColor();
            Color dark = Misc.getDarkPlayerColor();
            Color bright = Misc.getBrightPlayerColor();
            Color gray = Misc.getGrayColor();

            place(list.addPara("Marines, droids and stances", Misc.getHighlightColor(), 0f), 10f, 8f, 400f);
            summary = place(list.addPara("", 0f), 10f, 30f, WIDTH - 30f);
            // Tags and how many ships have each, right of the buttons
            LabelAPI roleLine = list.addPara(roleCounts(), gray, 0f);
            roleLine.getPosition().setSize(WIDTH - 710f, 40f);
            roleLine.getPosition().inTL(690f, 112f);
            colourTags(roleLine, presentRoles());
            LabelAPI help = list.addPara("Target is how many marines a ship carries. Grey targets are automatic: boarding"
                    + " ships fill up first and the rest share what's left. A target you set is a cap. Priority ships fill first"
                    + " when marines run short; \"No marines\" ships only get leftovers. Stance is how a ship fights in RTS mode"
                    + " when it has no orders.", gray, 0f);
            help.getPosition().setSize(WIDTH - 30f, 60f);
            help.getPosition().inTL(10f, 48f);

            // Controls: view on the first row, bulk changes on the second
            place(list.addPara("Sort:", gray, 0f), 10f, 116f, 40f);
            float x = 50f;
            for (Sort sort : Sort.values()) {
                float width = sort == Sort.POINTS ? 150f : 104f;
                ButtonAPI button = button(list, sort.label, base, dark, bright, x, 112f, width);
                button.setChecked(sort == view.sort);
                sortButtons.put(sort, button);
                x += width + 4f;
            }
            viewButton = button(list, view.grid ? "Show as list" : "Show as grid", base, dark, bright, x + 16f, 112f, 120f);
            place(list.addPara("All ships:", gray, 0f), 10f, 144f, 70f);
            fillButton = button(list, "Fill boarding ships", base, dark, bright, 84f, 140f, 150f);
            spreadButton = button(list, "Spread evenly", base, dark, bright, 238f, 140f, 120f);
            noneButton = button(list, "No marines: all", base, dark, bright, 362f, 140f, 150f);
            resetButton = button(list, "Reset to automatic", base, dark, bright, 516f, 140f, 150f);

            if (view.grid) layOutGrid(list, base, dark, bright, gray);
            else layOutList(list, base, dark, bright, gray);

            panel.addUIElement(list).inTL(0f, 0f);
            refresh();
        }

        private void layOutList(TooltipMakerAPI list, Color base, Color dark, Color bright, Color gray) {
            place(list.addPara("Ship", gray, 0f), 60f, LIST_TOP - 20f, 200f);
            place(list.addPara("Room", gray, 0f), 300f, LIST_TOP - 20f, 60f);
            place(list.addPara("Target", gray, 0f), 468f, LIST_TOP - 20f, 60f);
            place(list.addPara("Gets", gray, 0f), 818f, LIST_TOP - 20f, 80f);
            place(list.addPara("Stance", gray, 0f), 914f, LIST_TOP - 20f, 100f);
            for (int i = 0; i < members.size(); i++) {
                FleetMemberAPI member = members.get(i);
                Row row = newRow(member);
                float y = LIST_TOP + i * ROW;
                icon(list, member, 10f, y, 40f);
                List<Role> roles = rolesOf(member);
                String tags = tagsOf(roles);
                LabelAPI title = place(list.addPara(tags.isEmpty() ? name(member, 38)
                        : name(member, Math.max(12, 36 - tags.length())) + "  " + tags, 0f), 60f, y + 4f, 236f);
                colourTags(title, roles);
                place(list.addPara(member.getHullSpec().getHullName() + ", " + Math.round(member.getDeploymentPointsCost()) + " DP"
                        + securityText(member, ", "),
                        gray, 0f), 60f, y + 22f, 236f);
                if (row.automated) {
                    place(list.addPara(droidsFor(member) + " droids (automated ship)", gray, 0f), 300f, y + 12f, 470f);
                } else {
                    place(list.addPara("0 - " + row.capacity, gray, 0f), 300f, y + 12f, 66f);
                    row.zero = button(list, "0", base, dark, bright, 368f, y + 8f, 28f);
                    row.minusTen = button(list, "-10", base, dark, bright, 398f, y + 8f, 36f);
                    row.minusOne = button(list, "-1", base, dark, bright, 436f, y + 8f, 28f);
                    row.target = place(list.addPara("", Misc.getHighlightColor(), 0f), 470f, y + 12f, 42f);
                    row.plusOne = button(list, "+1", base, dark, bright, 514f, y + 8f, 28f);
                    row.plusTen = button(list, "+10", base, dark, bright, 544f, y + 8f, 36f);
                    row.max = button(list, "Max", base, dark, bright, 582f, y + 8f, 40f);
                    row.priority = button(list, "Priority", base, dark, bright, 630f, y + 8f, 80f);
                    row.excluded = button(list, "No marines", base, dark, bright, 714f, y + 8f, 96f);
                    row.priority.setChecked(row.entry.priority);
                    row.excluded.setChecked(row.entry.excluded);
                    row.assigned = place(list.addPara("", 0f), 818f, y + 4f, 94f);
                    int militia = militiaOf(member);
                    if (militia > 0) place(list.addPara("+" + militia + " militia", gray, 0f), 818f, y + 22f, 94f);
                }
                row.stanceButton = button(list, view.stances.get(member.getId()).label, base, dark, bright, 914f, y + 8f, 112f);
                rows.add(row);
            }
            list.setHeightSoFar(LIST_TOP + members.size() * ROW + 20f);
        }

        private void layOutGrid(TooltipMakerAPI list, Color base, Color dark, Color bright, Color gray) {
            for (int i = 0; i < members.size(); i++) {
                FleetMemberAPI member = members.get(i);
                Row row = newRow(member);
                float x = 10f + (i % CARD_COLS) * (CARD_W + 8f);
                float y = TOP + (i / CARD_COLS) * (CARD_H + 8f);
                icon(list, member, x + 8f, y + 8f, 64f);
                place(list.addPara(name(member, 26), 0f), x + 80f, y + 8f, CARD_W - 86f);
                place(list.addPara(member.getHullSpec().getHullName(), gray, 0f), x + 80f, y + 26f, CARD_W - 86f);
                place(list.addPara(Math.round(member.getDeploymentPointsCost()) + " DP" + securityText(member, ", "), gray, 0f),
                        x + 80f, y + 44f, CARD_W - 86f);
                if (row.automated) {
                    place(list.addPara(droidsFor(member) + " droids", Misc.getHighlightColor(), 0f), x + 8f, y + 84f, CARD_W - 16f);
                    place(list.addPara("Automated ship: no crew, no marines", gray, 0f), x + 8f, y + 104f, CARD_W - 16f);
                } else {
                    int militia = militiaOf(member);
                    place(list.addPara("Room 0 - " + row.capacity + (militia > 0 ? ", +" + militia + " militia" : ""), gray, 0f),
                            x + 80f, y + 62f, CARD_W - 86f);
                    row.zero = button(list, "0", base, dark, bright, x + 8f, y + 82f, 26f);
                    row.minusTen = button(list, "-10", base, dark, bright, x + 36f, y + 82f, 34f);
                    row.minusOne = button(list, "-1", base, dark, bright, x + 72f, y + 82f, 26f);
                    row.target = place(list.addPara("", Misc.getHighlightColor(), 0f), x + 102f, y + 86f, 40f);
                    row.plusOne = button(list, "+1", base, dark, bright, x + 144f, y + 82f, 26f);
                    row.plusTen = button(list, "+10", base, dark, bright, x + 172f, y + 82f, 34f);
                    row.max = button(list, "Max", base, dark, bright, x + 208f, y + 82f, 34f);
                    row.priority = button(list, "Priority", base, dark, bright, x + 8f, y + 112f, 112f);
                    row.excluded = button(list, "No marines", base, dark, bright, x + 124f, y + 112f, 116f);
                    row.priority.setChecked(row.entry.priority);
                    row.excluded.setChecked(row.entry.excluded);
                    row.assigned = place(list.addPara("", 0f), x + 124f, y + 148f, CARD_W - 130f);
                }
                row.stanceButton = button(list, view.stances.get(member.getId()).label, base, dark, bright, x + 8f, y + 144f, 112f);
                List<Role> roles = rolesOf(member);
                if (!roles.isEmpty()) colourTags(place(list.addPara(tagsOf(roles), 0f), x + 8f, y + 174f, CARD_W - 16f), roles);
                rows.add(row);
            }
            int lines = (members.size() + CARD_COLS - 1) / CARD_COLS;
            list.setHeightSoFar(TOP + lines * (CARD_H + 8f) + 20f);
        }

        private Row newRow(FleetMemberAPI member) {
            Row row = new Row();
            row.member = member;
            row.automated = isAutomated(member);
            row.entry = row.automated ? null : view.entries.get(member.getId());
            row.capacity = row.automated ? 0 : MarineAllocation.capacity(member);
            return row;
        }

        private static String name(FleetMemberAPI member, int max) {
            String name = member.getShipName();
            if (name == null || name.isEmpty()) name = member.getHullSpec().getHullName();
            return name.length() > max ? name.substring(0, max - 1) + "." : name;
        }

        private void icon(TooltipMakerAPI list, FleetMemberAPI member, float x, float y, float size) {
            List<FleetMemberAPI> one = new ArrayList<FleetMemberAPI>();
            one.add(member);
            list.addShipList(1, 1, size, Misc.getBasePlayerColor(), one, 0f);
            list.getPrev().getPosition().inTL(x, y);
        }

        private LabelAPI place(LabelAPI label, float x, float y, float width) {
            PositionAPI position = label.getPosition();
            position.setSize(width, 20f);
            position.inTL(x, y);
            return label;
        }

        private ButtonAPI button(TooltipMakerAPI list, String text, Color base, Color dark, Color bright,
                float x, float y, float width) {
            ButtonAPI button = list.addAreaCheckbox(text, null, base, dark, bright, width, 24f, 0f);
            button.getPosition().inTL(x, y);
            return button;
        }

        private int countOf(Role role) {
            int count = 0;
            for (FleetMemberAPI member : members) {
                if (rolesOf(member).contains(role)) count++;
            }
            return count;
        }

        /** Roles at least one ship has, in tag order. */
        private List<Role> presentRoles() {
            List<Role> present = new ArrayList<Role>();
            for (Role role : Role.values()) {
                if (countOf(role) > 0) present.add(role);
            }
            return present;
        }

        private String roleCounts() {
            StringBuilder text = new StringBuilder();
            for (Role role : presentRoles()) {
                text.append(text.length() == 0 ? "" : "  ").append(role.tag).append(" ").append(countOf(role));
            }
            return text.length() == 0 ? "No support or command ships" : text.toString();
        }

        private void refresh() {
            MarineAllocation.Result result = MarineAllocation.compute(marineShips, marines, view.entries);
            int placed = 0;
            int droids = 0;
            int militia = 0;
            int bayShips = 0;
            int bayRoom = 0;
            int security = 0;
            int securityFull = 0;
            boolean allExcluded = true;
            boolean anyCrewed = false;
            for (Row row : rows) {
                if (row.automated) {
                    droids += droidsFor(row.member);
                    continue;
                }
                anyCrewed = true;
                if (!row.entry.excluded) allExcluded = false;
                militia += militiaOf(row.member);
                if (hasForge(row.member)) {
                    security += Math.round(SecurityDroids.current(row.member));
                    securityFull += SecurityDroids.full(row.member);
                }
                if (row.member.getVariant() != null && row.member.getVariant().hasHullMod(BoardingCombatPlugin.HULLMOD_ID)) {
                    bayShips++;
                    bayRoom += row.capacity;
                }
                int target = MarineAllocation.targetOf(row.member, row.entry);
                Integer gets = result.assigned.get(row.member.getId());
                int got = gets == null ? 0 : gets;
                placed += got;
                row.target.setText(row.entry.excluded ? "-" : "" + target);
                // Set targets in the highlight colour, automatic ones grey
                row.target.setColor(MarineAllocation.isSet(row.entry) && !row.entry.excluded
                        ? Misc.getHighlightColor() : Misc.getGrayColor());
                String extra = got < target && !row.entry.excluded ? " (short)" : got > target ? " (spare)" : "";
                row.assigned.setText("Gets " + got + extra);
                row.priority.setChecked(row.entry.priority);
                row.excluded.setChecked(row.entry.excluded);
            }
            noneButton.setText(anyCrewed && allExcluded ? "Allow marines: all" : "No marines: all");
            StringBuilder text = new StringBuilder("Marines: " + marines + "     On ships: " + placed
                    + "     On no ship: " + result.unassigned);
            if (bayShips > 0) text.append("     Boarding ships: " + bayShips + " (room for " + bayRoom + ")");
            if (militia > 0) text.append("     Militia: " + militia);
            if (securityFull > 0) text.append("     Security droids: " + security + " / " + securityFull);
            if (droids > 0) text.append("     Automated droids: " + droids);
            summary.setText(text.toString());
        }

        @Override
        public void advance(float amount) {
            if (reopening) return;
            for (Map.Entry<Sort, ButtonAPI> entry : sortButtons.entrySet()) {
                if (clicked(entry.getValue()) && entry.getKey() != view.sort) {
                    view.sort = entry.getKey();
                    savePrefs(view);
                    layOutAgain();
                    return;
                }
                entry.getValue().setChecked(entry.getKey() == view.sort);
            }
            if (clicked(viewButton)) {
                view.grid = !view.grid;
                savePrefs(view);
                layOutAgain();
                return;
            }

            boolean changed = false;
            if (clicked(fillButton)) {
                // Fill boarding ships
                for (Row row : rows) {
                    if (row.automated || row.member.getVariant() == null
                            || !row.member.getVariant().hasHullMod(BoardingCombatPlugin.HULLMOD_ID)) continue;
                    row.entry.target = row.capacity;
                    row.entry.excluded = false;
                }
                changed = true;
            }
            if (clicked(spreadButton)) {
                // Spread evenly
                int takers = 0;
                for (Row row : rows) {
                    if (!row.automated && !row.entry.excluded && row.capacity > 0) takers++;
                }
                int share = takers == 0 ? 0 : marines / takers;
                for (Row row : rows) {
                    if (row.automated || row.entry.excluded) continue;
                    row.entry.target = Math.min(row.capacity, share);
                    row.entry.priority = false;
                }
                changed = true;
            }
            if (clicked(noneButton)) {
                // Toggle: every ship "no marines", or none
                boolean allExcluded = true;
                for (Row row : rows) {
                    if (!row.automated && !row.entry.excluded) allExcluded = false;
                }
                for (Row row : rows) {
                    if (row.automated) continue;
                    row.entry.excluded = !allExcluded;
                    if (row.entry.excluded) row.entry.priority = false;
                }
                changed = true;
            }
            if (clicked(resetButton)) {
                for (Row row : rows) {
                    if (row.automated) continue;
                    row.entry.target = -1;
                    row.entry.priority = false;
                    row.entry.excluded = false;
                }
                changed = true;
            }

            for (Row row : rows) {
                if (clicked(row.stanceButton)) {
                    Stance next = view.stances.get(row.member.getId()).next();
                    view.stances.put(row.member.getId(), next);
                    row.stanceButton.setText(next.label);
                }
                if (row.automated) continue;
                if (clicked(row.zero)) {
                    row.entry.target = 0;
                    changed = true;
                }
                if (clicked(row.max)) {
                    row.entry.target = row.capacity;
                    changed = true;
                }
                int step = 0;
                if (clicked(row.minusTen)) step -= 10;
                if (clicked(row.minusOne)) step -= 1;
                if (clicked(row.plusOne)) step += 1;
                if (clicked(row.plusTen)) step += 10;
                if (step != 0) {
                    int current = MarineAllocation.targetOf(row.member, row.entry);
                    row.entry.target = Math.max(0, Math.min(row.capacity, current + step));
                    changed = true;
                }
                if (row.priority.isChecked() != row.entry.priority) {
                    row.entry.priority = row.priority.isChecked();
                    if (row.entry.priority) row.entry.excluded = false;
                    changed = true;
                }
                if (row.excluded.isChecked() != row.entry.excluded) {
                    row.entry.excluded = row.excluded.isChecked();
                    if (row.entry.excluded) row.entry.priority = false;
                    changed = true;
                }
            }
            if (changed) refresh();
        }

        private void layOutAgain() {
            reopening = true;
            host.reopen = view;
            if (callback != null) callback.dismissCustomDialog(1);
        }

        private boolean clicked(ButtonAPI button) {
            if (button == null || !button.isChecked()) return false;
            button.setChecked(false);
            return true;
        }

        @Override
        public boolean hasCancelButton() {
            return true;
        }

        @Override
        public String getConfirmText() {
            return "Save";
        }

        @Override
        public String getCancelText() {
            return "Cancel";
        }

        @Override
        public void customDialogConfirm() {
            for (FleetMemberAPI member : marineShips) {
                MarineAllocation.Entry entry = view.entries.get(member.getId());
                if (entry != null) MarineAllocation.save(member, entry);
            }
            for (FleetMemberAPI member : members) Stance.save(member.getId(), view.stances.get(member.getId()));
            host.reopen = null;
            dialog.dismiss();
        }

        @Override
        public void customDialogCancel() {
            // Closed for a rebuild: the host reopens it
            if (reopening) return;
            host.reopen = null;
            dialog.dismiss();
        }

        @Override
        public CustomUIPanelPlugin getCustomPanelPlugin() {
            return this;
        }

        @Override
        public void positionChanged(PositionAPI position) {
        }

        @Override
        public void renderBelow(float alphaMult) {
        }

        @Override
        public void render(float alphaMult) {
        }

        @Override
        public void processInput(List<InputEventAPI> events) {
        }

        @Override
        public void buttonPressed(Object buttonId) {
        }
    }
}
