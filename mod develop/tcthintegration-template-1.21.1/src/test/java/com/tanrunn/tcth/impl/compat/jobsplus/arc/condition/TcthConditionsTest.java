package com.tanrunn.tcth.impl.compat.jobsplus.arc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.daqem.arc.api.action.data.ActionData;
import com.daqem.arc.api.player.ArcPlayer;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.tanrunn.tcth.api.cooking.CookingDevice;
import com.tanrunn.tcth.api.cooking.DishCookedEvent;
import com.tanrunn.tcth.api.cooking.DishQuality;
import com.tanrunn.tcth.impl.compat.jobsplus.DishTier;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.AutomatedCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.CookingDeviceCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.DishQualityCondition;
import com.tanrunn.tcth.impl.compat.jobsplus.arc.condition.DishTierCondition;
import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Unit tests for the four TCTH Arc conditions.
 */
class TcthConditionsTest {

    private ServerLevel level;
    private ServerPlayer player;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    private ActionData dishData(DishTier tier, DishQuality quality, CookingDevice device, boolean automated) {
        level = Mockito.mock(ServerLevel.class);
        player = Mockito.mock(ServerPlayer.class);
        DishCookedEvent e = new DishCookedEvent(UUID.randomUUID(), player, null,
                new ItemStack(Items.COOKED_BEEF), device, quality, automated, level, null);
        return DishActionDispatcher.buildActionData(Mockito.mock(ArcPlayer.class), e, tier);
    }

    // ---- tcth:dish_tier ----

    @Test
    void dishTierMatchesAndInverts() {
        ActionData data = dishData(DishTier.T3, DishQuality.UNKNOWN, CookingDevice.FURNACE, false);

        assertTrue(new DishTierCondition(false, "T3").isMet(data));
        assertFalse(new DishTierCondition(false, "T2").isMet(data));
        assertTrue(new DishTierCondition(true, "T2").isMet(data), "inverted must flip the result");
    }

    @Test
    void dishTierJsonNormalizesCaseAndRejectsUnknown() {
        JsonObject json = new JsonObject();
        json.addProperty("tier", "t3");
        var cond = new DishTierCondition.Serializer().fromJson(
                ResourceLocation.parse("tcth:dish_tier"), json, false);
        assertEquals("T3", cond.tier());

        json.addProperty("tier", "T4");
        assertThrows(JsonSyntaxException.class,
                () -> new DishTierCondition.Serializer().fromJson(
                        ResourceLocation.parse("tcth:dish_tier"), json, false),
                "unknown tier must fail data loading with a clear error");
    }

    // ---- tcth:dish_quality ----

    @Test
    void dishQualityMatchesListAndInverts() {
        ActionData superb = dishData(DishTier.COMMON, DishQuality.SUPERB, CookingDevice.FURNACE, false);
        ActionData unknown = dishData(DishTier.COMMON, DishQuality.UNKNOWN, CookingDevice.FURNACE, false);

        assertTrue(new DishQualityCondition(false, List.of("EXCELLENT", "SUPERB")).isMet(superb));
        assertFalse(new DishQualityCondition(false, List.of("EXCELLENT", "SUPERB")).isMet(unknown));
        assertTrue(new DishQualityCondition(true, List.of("EXCELLENT", "SUPERB")).isMet(unknown));
    }

    @Test
    void dishQualityJsonRejectsUnknownAndEmpty() {
        JsonObject json = new JsonObject();
        json.add("quality", com.google.gson.JsonParser.parseString("[\"SUPERB\"]"));
        var cond = new DishQualityCondition.Serializer().fromJson(
                ResourceLocation.parse("tcth:dish_quality"), json, false);
        assertEquals(List.of("SUPERB"), cond.qualities());

        json.add("quality", com.google.gson.JsonParser.parseString("[\"EPIC\"]"));
        assertThrows(JsonSyntaxException.class,
                () -> new DishQualityCondition.Serializer().fromJson(
                        ResourceLocation.parse("tcth:dish_quality"), json, false));

        json.add("quality", com.google.gson.JsonParser.parseString("[]"));
        assertThrows(JsonSyntaxException.class,
                () -> new DishQualityCondition.Serializer().fromJson(
                        ResourceLocation.parse("tcth:dish_quality"), json, false));
    }

    // ---- tcth:cooking_device ----

    @Test
    void cookingDeviceMatchesListAndInverts() {
        ActionData fd = dishData(DishTier.COMMON, DishQuality.UNKNOWN,
                CookingDevice.FARMERS_DELIGHT_COOKING_POT, false);
        ActionData furnace = dishData(DishTier.COMMON, DishQuality.UNKNOWN, CookingDevice.FURNACE, false);

        assertTrue(new CookingDeviceCondition(false,
                List.of("FARMERS_DELIGHT_COOKING_POT", "KALEIDOSCOPE_STEAMER")).isMet(fd));
        assertFalse(new CookingDeviceCondition(false,
                List.of("FARMERS_DELIGHT_COOKING_POT", "KALEIDOSCOPE_STEAMER")).isMet(furnace));
        assertTrue(new CookingDeviceCondition(true,
                List.of("FARMERS_DELIGHT_COOKING_POT", "KALEIDOSCOPE_STEAMER")).isMet(furnace));
    }

    @Test
    void cookingDeviceJsonRejectsUnknown() {
        JsonObject json = new JsonObject();
        json.add("devices", com.google.gson.JsonParser.parseString("[\"FURNACE\"]"));
        var cond = new CookingDeviceCondition.Serializer().fromJson(
                ResourceLocation.parse("tcth:cooking_device"), json, false);
        assertEquals(List.of("FURNACE"), cond.devices());

        json.add("devices", com.google.gson.JsonParser.parseString("[\"NOT_A_DEVICE\"]"));
        assertThrows(JsonSyntaxException.class,
                () -> new CookingDeviceCondition.Serializer().fromJson(
                        ResourceLocation.parse("tcth:cooking_device"), json, false));
    }

    // ---- tcth:automated ----

    @Test
    void automatedMatchesValueAndInverts() {
        ActionData automated = dishData(DishTier.COMMON, DishQuality.UNKNOWN, CookingDevice.FURNACE, true);
        ActionData manual = dishData(DishTier.COMMON, DishQuality.UNKNOWN, CookingDevice.FURNACE, false);

        assertTrue(new AutomatedCondition(false, true).isMet(automated));
        assertFalse(new AutomatedCondition(false, false).isMet(automated));
        assertTrue(new AutomatedCondition(false, false).isMet(manual));
        assertTrue(new AutomatedCondition(true, false).isMet(automated), "inverted must flip");
    }

    @Test
    void automatedJsonParsesValue() {
        JsonObject json = new JsonObject();
        json.addProperty("value", false);
        assertFalse(new AutomatedCondition.Serializer().fromJson(
                ResourceLocation.parse("tcth:automated"), json, false).value());
    }
}
