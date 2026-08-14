package com.tanrunn.tcth.impl.shadow;

import java.util.function.Supplier;

import com.tanrunn.tcth.TCTHIntegration;

import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * TCTH entity attachments (8D.1 §2).
 *
 * <p>{@code tcth:shadow_loot_state} is the authoritative per-entity
 * "may this entity be looted again" marker. It is serializable (persisted
 * with the entity NBT via the NeoForge save/load chain) and copied across
 * dimension travel via {@code restoreFrom}. Registered on the mod event bus.
 */
public final class TcthShadowAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, TCTHIntegration.MODID);

    public static final Supplier<AttachmentType<ShadowLootState>> SHADOW_LOOT_STATE =
            ATTACHMENT_TYPES.register("shadow_loot_state",
                    () -> AttachmentType.builder(ShadowLootState::available)
                            .serialize(new ShadowLootStateSerializer())
                            .build());

    private TcthShadowAttachments() {
    }
}
