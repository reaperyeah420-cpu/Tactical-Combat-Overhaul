package tco.battle;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.campaign.PlanetAPI;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.BattleCreationContext;
import com.fs.starfarer.api.combat.BattleObjectiveAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;

/** Passes everything to the real mission definition, scaling map coordinates. */
public class ScalingLoader implements MissionDefinitionAPI {

    private final MissionDefinitionAPI real;
    private final float mult;
    private float width = 0f;
    private float height = 0f;
    private final List<Vector2f> objectives = new ArrayList<Vector2f>();

    public ScalingLoader(MissionDefinitionAPI real, float mult) {
        this.real = real;
        this.mult = mult;
    }

    /** Scaled width, 0 before initMap. */
    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    /** Scaled positions of objectives added so far. */
    public List<Vector2f> getObjectives() {
        return objectives;
    }

    @Override
    public void initMap(float minX, float maxX, float minY, float maxY) {
        width = (maxX - minX) * mult;
        height = (maxY - minY) * mult;
        real.initMap(minX * mult, maxX * mult, minY * mult, maxY * mult);
    }

    @Override
    public void addNebula(float x, float y, float radius) {
        real.addNebula(x * mult, y * mult, radius * mult);
    }

    @Override
    public void addObjective(float x, float y, String type, BattleObjectiveAPI.Importance importance) {
        objectives.add(new Vector2f(x * mult, y * mult));
        real.addObjective(x * mult, y * mult, type, importance);
    }

    @Override
    public void addObjective(float x, float y, String type) {
        objectives.add(new Vector2f(x * mult, y * mult));
        real.addObjective(x * mult, y * mult, type);
    }

    @Override
    public void addPlanet(float x, float y, float radius, String type, float gravity) {
        real.addPlanet(x * mult, y * mult, radius, type, gravity);
    }

    @Override
    public void addPlanet(float x, float y, float radius, String type, float gravity, boolean backgroundPlanet) {
        if (backgroundPlanet) real.addPlanet(x, y, radius, type, gravity, true);
        else real.addPlanet(x * mult, y * mult, radius, type, gravity, false);
    }

    @Override
    public void addPlanet(float x, float y, float radius, PlanetAPI planet, float gravity, boolean backgroundPlanet) {
        if (backgroundPlanet) real.addPlanet(x, y, radius, planet, gravity, true);
        else real.addPlanet(x * mult, y * mult, radius, planet, gravity, false);
    }

    @Override
    public void addAsteroidField(float x, float y, float angle, float fieldWidth,
            float minSpeed, float maxSpeed, int quantity) {
        real.addAsteroidField(x * mult, y * mult, angle, fieldWidth * mult, minSpeed, maxSpeed,
                Math.round(quantity * mult));
    }

    @Override
    public void addRingAsteroids(float x, float y, float angle, float fieldWidth,
            float minSpeed, float maxSpeed, int quantity) {
        real.addRingAsteroids(x * mult, y * mult, angle, fieldWidth * mult, minSpeed, maxSpeed,
                Math.round(quantity * mult));
    }

    @Override
    public void initFleet(FleetSide side, String shipNamePrefix, FleetGoal goal, boolean useDefaultAI) {
        real.initFleet(side, shipNamePrefix, goal, useDefaultAI);
    }

    @Override
    public void initFleet(FleetSide side, String shipNamePrefix, FleetGoal goal, boolean useDefaultAI,
            int commandRating) {
        real.initFleet(side, shipNamePrefix, goal, useDefaultAI, commandRating);
    }

    @Override
    public void initFleet(FleetSide side, String shipNamePrefix, FleetGoal goal, boolean useDefaultAI,
            int commandRating, int allyCommandRating) {
        real.initFleet(side, shipNamePrefix, goal, useDefaultAI, commandRating, allyCommandRating);
    }

    @Override
    public FleetMemberAPI addToFleet(FleetSide side, String variantId, FleetMemberType type, boolean isFlagship) {
        return real.addToFleet(side, variantId, type, isFlagship);
    }

    @Override
    public FleetMemberAPI addToFleet(FleetSide side, String variantId, FleetMemberType type, String shipName,
            boolean isFlagship) {
        return real.addToFleet(side, variantId, type, shipName, isFlagship);
    }

    @Override
    public void defeatOnShipLoss(String shipName) {
        real.defeatOnShipLoss(shipName);
    }

    @Override
    public void addBriefingItem(String item) {
        real.addBriefingItem(item);
    }

    @Override
    public void setFleetTagline(FleetSide side, String tagline) {
        real.setFleetTagline(side, tagline);
    }

    @Override
    public void setPlanetBgSize(float bgWidth, float bgHeight) {
        real.setPlanetBgSize(bgWidth, bgHeight);
    }

    @Override
    public int getFleetPointCost(String id) {
        return real.getFleetPointCost(id);
    }

    @Override
    public void addPlugin(EveryFrameCombatPlugin plugin) {
        real.addPlugin(plugin);
    }

    @Override
    public void setBackgroundSpriteName(String background) {
        real.setBackgroundSpriteName(background);
    }

    @Override
    public void addFleetMember(FleetSide side, FleetMemberAPI member) {
        real.addFleetMember(side, member);
    }

    @Override
    public void setHyperspaceMode(boolean hyperspaceMode) {
        real.setHyperspaceMode(hyperspaceMode);
    }

    @Override
    public void setNebulaTex(String nebulaTex) {
        real.setNebulaTex(nebulaTex);
    }

    @Override
    public void setNebulaMapTex(String nebulaMapTex) {
        real.setNebulaMapTex(nebulaMapTex);
    }

    @Override
    public void setBackgroundGlowColor(Color backgroundGlowColor) {
        real.setBackgroundGlowColor(backgroundGlowColor);
    }

    @Override
    public BattleCreationContext getContext() {
        return real.getContext();
    }

    @Override
    public PersonAPI getDefaultCommander(FleetSide side) {
        return real.getDefaultCommander(side);
    }

    @Override
    public boolean hasNebula() {
        return real.hasNebula();
    }
}
