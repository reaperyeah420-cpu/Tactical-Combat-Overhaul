package tco.patches;

import com.fs.starfarer.api.combat.ShipAPI;

import patchlib.api.context.BeforeContext;
import patchlib.api.match.ClassMatch;
import patchlib.api.match.MethodMatch;
import patchlib.api.patch.Before;
import patchlib.api.patch.Patch;
import patchlib.api.query.FieldQuery;
import patchlib.api.spec.FieldQuerySpec;

import tco.rts.FogOfWar;

/**
 * Fog of war for the status overlay the game draws by a ship (name, hull and flux bars, the officer's badge),
 * which ignores the ship's alpha and the friend-or-foe flag.
 */
@Patch(target = @ClassMatch(typeName = Patches.STATUS_OVERLAY))
public class FogOverlayPatch {

    private static final FieldQuerySpec SHIP_FIELD = FieldQuery.create().typeName(Patches.SHIP).build();

    @Before(target = @MethodMatch(parameterTypeNames = {"float", "float", Patches.VECTOR, "boolean", Patches.VECTOR}))
    public static void beforeRender(BeforeContext context) {
        if (!FogOfWar.isActive() || !Patches.overlay()) return;
        Object ship = context.getField(SHIP_FIELD).get();
        if (ship instanceof ShipAPI && FogOfWar.isDrawnHidden((ShipAPI) ship)) context.skipOriginal(null);
    }
}
