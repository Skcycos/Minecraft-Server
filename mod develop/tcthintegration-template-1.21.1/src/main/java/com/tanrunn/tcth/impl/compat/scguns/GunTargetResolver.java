package com.tanrunn.tcth.impl.compat.scguns;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.tanrunn.tcth.TCTHIntegration;
import com.tanrunn.tcth.api.guncombat.GunTargetTier;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Resolves the {@link GunTargetTier} of a killed entity from data tags.
 *
 * <p>Resolution order (highest priority first):
 * <ol>
 *   <li>{@code #tcth:gunner_targets/excluded} — excluded targets never produce
 *       an event (returns {@code null});</li>
 *   <li>{@code #tcth:gunner_targets/boss} — boss-tier;</li>
 *   <li>{@code #tcth:gunner_targets/heavy} — heavy-tier;</li>
 *   <li>{@code #tcth:gunner_targets/elite} — elite-tier;</li>
 *   <li>{@code #tcth:gunner_targets/common} — common-tier.</li>
 * </ol>
 *
 * <p>Tags are loaded lazily and cached by reference. A target that does not
 * match any allowed tier returns {@code null} (no event). The default
 * fail-closed: {@code MobCategory#MISC} and non-hostile mobs are not
 * automatically included.
 */
public final class GunTargetResolver {

    private static final TagKey<EntityType<?>> EXCLUDED =
            tag("gunner_targets/excluded");
    private static final TagKey<EntityType<?>> BOSS =
            tag("gunner_targets/boss");
    private static final TagKey<EntityType<?>> HEAVY =
            tag("gunner_targets/heavy");
    private static final TagKey<EntityType<?>> ELITE =
            tag("gunner_targets/elite");
    private static final TagKey<EntityType<?>> COMMON =
            tag("gunner_targets/common");

    private GunTargetResolver() {
    }

    /**
     * Resolves the tier of the given entity.
     *
     * @param entity the killed entity
     * @return the resolved tier, or {@code null} if the entity is excluded or
     *         not classified
     */
    @Nullable
    public static GunTargetTier resolve(Entity entity) {
        if (entity == null) {
            return null;
        }
        EntityType<?> type = entity.getType();
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);

        // excluded takes priority
        if (type.is(EXCLUDED)) {
            return null;
        }
        // boss > heavy > elite > common
        if (type.is(BOSS)) {
            return GunTargetTier.BOSS;
        }
        if (type.is(HEAVY)) {
            return GunTargetTier.HEAVY;
        }
        if (type.is(ELITE)) {
            return GunTargetTier.ELITE;
        }
        if (type.is(COMMON)) {
            return GunTargetTier.COMMON;
        }
        // Fail-closed: unknown target = no event.
        TCTHIntegration.LOGGER.debug("[TCTH] GunTargetResolver: target {} not classified (no event)", id);
        return null;
    }

    private static TagKey<EntityType<?>> tag(String path) {
        return TagKey.create(BuiltInRegistries.ENTITY_TYPE.key(),
                ResourceLocation.fromNamespaceAndPath("tcth", path));
    }
}
