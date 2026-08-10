package com.tanrunn.tcth.impl.detector.farming;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

/**
 * Phase 4C: the farmer tilling-route mixin contract — the mixin config is
 * registered in neoforge.mods.toml, gated on jobsplus, its @Inject target
 * matches the NeoForge 21.1 runtime durability overload (the LivingEntity
 * variant that Arc 9.0.0 misses). Routing mutual exclusion is tested in
 * {@code FarmerTillingRoutingTest}.
 */
class FarmerTillingMixinContractTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void mixinConfigIsRegisteredAndGatedOnJobsplus() throws Exception {
        String toml = Files.readString(Path.of("src/main/templates/META-INF/neoforge.mods.toml"),
                StandardCharsets.UTF_8);
        assertTrue(toml.contains("tcth_farmer_abilities.mixins.json"),
                "neoforge.mods.toml must register tcth_farmer_abilities.mixins.json");
        assertTrue(toml.contains("requiredMods=[\"jobsplus\"]"),
                "tilling mixin config must be gated on jobsplus");
    }

    @Test
    void mixinTargetsTheLivingEntityDurabilityOverload() throws Exception {
        String mixin = Files.readString(Path.of("src/main/java/com/tanrunn/tcth/mixin/ItemStackDurabilityMixin.java"),
                StandardCharsets.UTF_8);
        assertTrue(mixin.contains("hurtAndBreak(ILnet/minecraft/server/level/ServerLevel;"),
                "mixin must target the ServerLevel overload");
        assertTrue(mixin.contains("Lnet/minecraft/world/entity/LivingEntity;"),
                "mixin must target the LivingEntity (NeoForge runtime) variant");
        assertTrue(mixin.contains("cancellable = true"),
                "mixin must be cancellable to skip the durability loss");
        assertTrue(mixin.contains("FarmerAbilityModule.shouldSkipHoeDurability"),
                "mixin must delegate to the tilling logic");
        assertTrue(mixin.contains("ChefAbilityModule.shouldSkipKnifeDurability"),
                "mixin must also delegate to the chef knife logic");
        assertTrue(mixin.contains("shouldSkipDurability(player, (ItemStack) (Object) this)"),
                "mixin must route through the single classification entry point");
    }

    @Test
    void configListsTheMixin() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/tcth_farmer_abilities.mixins.json"),
                StandardCharsets.UTF_8);
        assertTrue(config.contains("ItemStackDurabilityMixin"), "mixin config must list the mixin");
        assertTrue(config.contains("\"jobsplus\""), "mixin config must require jobsplus");
    }
}
