package com.tanrunn.tcth.impl.compat.scguns;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.test.MinecraftTestBootstrap;

import com.tanrunn.tcth.api.guncombat.GunTargetTier;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Unit tests for {@link GunTargetResolver} (phase 5A).
 *
 * <p>Covers: null entity, unclassified entity (fail-closed).
 */
class GunTargetResolverTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.bootStrap();
    }

    @Test
    void nullEntityReturnsNull() {
        assertNull(GunTargetResolver.resolve(null));
    }

    @Test
    void unclassifiedEntityReturnsNull() {
        // An entity type that is in no tag resolves to null (fail-closed).
        // Without a live datapack the tag lookups all return false, so a real
        // vanilla entity type is "unclassified". The entity itself is mocked
        // because constructing a real Entity requires the NeoForge registries
        // that are absent in a bare JUnit JVM.
        Entity mockEntity = mock(Entity.class);
        org.mockito.Mockito.doReturn((net.minecraft.world.entity.EntityType<?>) (Object) EntityType.ZOMBIE)
                .when(mockEntity).getType();
        GunTargetTier result = GunTargetResolver.resolve(mockEntity);
        assertNull(result);
    }
}
