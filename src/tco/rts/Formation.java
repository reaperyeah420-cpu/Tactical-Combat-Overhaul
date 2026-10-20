package tco.rts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;

/** RTS units keeping station on a leader; slots are in the leader's frame, so the shape turns with it. */
public class Formation {

    public enum Type {
        LINE("Line"), WEDGE("Wedge"), COLUMN("Column");

        public final String label;

        Type(String label) {
            this.label = label;
        }
    }

    public Type type;
    public UnitState leader;
    public final Map<UnitState, Vector2f> slots = new LinkedHashMap<UnitState, Vector2f>();

    public Formation(Type type, List<UnitState> members) {
        this.type = type;
        List<UnitState> all = new ArrayList<UnitState>(members);
        leader = pickLeader(all);
        all.remove(leader);
        layOut(all);
    }

    public List<UnitState> members() {
        List<UnitState> all = new ArrayList<UnitState>();
        all.add(leader);
        all.addAll(slots.keySet());
        return all;
    }

    public boolean contains(UnitState unit) {
        return unit == leader || slots.containsKey(unit);
    }

    /** Removes a ship, picking a new leader if needed. False under two ships. */
    public boolean remove(UnitState unit) {
        if (unit == leader) {
            List<UnitState> rest = new ArrayList<UnitState>(slots.keySet());
            if (rest.isEmpty()) return false;
            leader = pickLeader(rest);
            rest.remove(leader);
            layOut(rest);
        } else if (slots.remove(unit) != null) {
            layOut(new ArrayList<UnitState>(slots.keySet()));
        }
        return !slots.isEmpty();
    }

    public void setType(Type type) {
        this.type = type;
        layOut(new ArrayList<UnitState>(slots.keySet()));
    }

    /** A follower's world position right now. */
    public Vector2f slotPoint(UnitState unit) {
        Vector2f local = slots.get(unit);
        if (local == null) return null;
        Vector2f at = leader.ship.getLocation();
        double f = Math.toRadians(leader.ship.getFacing());
        float cos = (float) Math.cos(f);
        float sin = (float) Math.sin(f);
        return new Vector2f(at.x + local.x * cos - local.y * sin, at.y + local.x * sin + local.y * cos);
    }

    private static UnitState pickLeader(List<UnitState> units) {
        UnitState best = null;
        for (UnitState unit : units) {
            if (best == null || rank(unit) > rank(best)) best = unit;
        }
        return best;
    }

    private static float rank(UnitState unit) {
        HullSize size = unit.ship.getHullSize();
        float score = size == null ? 0f : size.ordinal() * 10000f;
        if (unit.ship.getFleetMember() != null) score += unit.ship.getFleetMember().getDeploymentPointsCost();
        return score;
    }

    private void layOut(List<UnitState> followers) {
        slots.clear();
        if (followers.isEmpty()) return;
        float widest = leader.ship.getCollisionRadius();
        for (UnitState unit : followers) widest = Math.max(widest, unit.ship.getCollisionRadius());
        final float spacing = widest * 2f + 150f;

        final Vector2f at = leader.ship.getLocation();
        double f = Math.toRadians(leader.ship.getFacing());
        final float cos = (float) Math.cos(f);
        final float sin = (float) Math.sin(f);
        List<UnitState> ordered = new ArrayList<UnitState>(followers);
        final boolean byDepth = type == Type.COLUMN;
        Collections.sort(ordered, new Comparator<UnitState>() {
            @Override
            public int compare(UnitState a, UnitState b) {
                return Float.compare(key(a), key(b));
            }

            private float key(UnitState unit) {
                float dx = unit.ship.getLocation().x - at.x;
                float dy = unit.ship.getLocation().y - at.y;
                float ahead = dx * cos + dy * sin;
                float left = -dx * sin + dy * cos;
                return byDepth ? -ahead : -left;
            }
        });

        int n = ordered.size();
        for (int i = 0; i < n; i++) {
            Vector2f slot;
            if (type == Type.COLUMN) {
                slot = new Vector2f(-(i + 1) * spacing, 0f);
            } else {
                // Half on each side
                int leftCount = (n + 1) / 2;
                boolean onLeft = i < leftCount;
                int rankFromLeader = onLeft ? leftCount - i : i - leftCount + 1;
                float side = onLeft ? 1f : -1f;
                if (type == Type.LINE) {
                    slot = new Vector2f(0f, side * rankFromLeader * spacing);
                } else {
                    slot = new Vector2f(-rankFromLeader * spacing * 0.8f, side * rankFromLeader * spacing * 0.8f);
                }
            }
            slots.put(ordered.get(i), slot);
        }
    }
}
