package com.tanrunn.tcth.impl.shadow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.attachment.IAttachmentHolder;

/**
 * Tests for {@link ShadowLootState} and {@link ShadowLootStateSerializer}
 * (8D.1 §2): strict round-trip of all four states, missing attachment =
 * AVAILABLE, corrupt payloads → CORRUPT, and the serializer NEVER throws.
 */
class ShadowLootStateSerializerTest {

    private static final ResourceLocation ITEM = ResourceLocation.fromNamespaceAndPath("minecraft", "cobblestone");

    private static HolderLookup.Provider provider() {
        return HolderLookup.Provider.create(Stream.empty());
    }

    private static IAttachmentHolder holder() {
        return org.mockito.Mockito.mock(IAttachmentHolder.class);
    }

    private static ShadowLootStateSerializer serializer() {
        return new ShadowLootStateSerializer();
    }

    @Test
    void availableRoundTrips() {
        ShadowLootState state = ShadowLootState.available();
        CompoundTag tag = serializer().write(state, provider());
        assertEquals(ShadowLootState.State.AVAILABLE, serializer().read(holder(), tag, provider()).state());
    }

    @Test
    void pendingRoundTrips() {
        UUID eventId = UUID.randomUUID();
        UUID thief = UUID.randomUUID();
        ShadowLootState state = ShadowLootState.pending(eventId, thief, 1234L);
        ShadowLootState read = serializer().read(holder(), serializer().write(state, provider()), provider());
        assertEquals(ShadowLootState.State.PENDING, read.state());
        assertEquals(eventId, read.eventId());
        assertEquals(thief, read.thiefUuid());
        assertEquals(1234L, read.startedAt());
    }

    @Test
    void lootedRoundTrips() {
        UUID eventId = UUID.randomUUID();
        ShadowLootState state = ShadowLootState.looted(eventId, ITEM, 3, 5678L);
        ShadowLootState read = serializer().read(holder(), serializer().write(state, provider()), provider());
        assertEquals(ShadowLootState.State.LOOTED, read.state());
        assertEquals(eventId, read.eventId());
        assertEquals(ITEM, read.itemId());
        assertEquals(3, read.count());
        assertEquals(5678L, read.completedAt());
    }

    @Test
    void corruptRoundTripsAsCorrupt() {
        ShadowLootState state = ShadowLootState.corrupt();
        assertEquals(ShadowLootState.State.CORRUPT,
                serializer().read(holder(), serializer().write(state, provider()), provider()).state());
    }

    @Test
    void stateContractRejectsInvalidCombinations() {
        assertThrows(IllegalArgumentException.class,
                () -> ShadowLootState.pending(null, UUID.randomUUID(), 1L));
        assertThrows(IllegalArgumentException.class,
                () -> ShadowLootState.pending(UUID.randomUUID(), null, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> ShadowLootState.pending(UUID.randomUUID(), UUID.randomUUID(), -1L));
        assertThrows(IllegalArgumentException.class,
                () -> ShadowLootState.looted(null, ITEM, 1, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> ShadowLootState.looted(UUID.randomUUID(), ITEM, 0, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> ShadowLootState.looted(UUID.randomUUID(), ITEM, 65, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> ShadowLootState.looted(UUID.randomUUID(), ITEM, 1, -1L));
    }

    @Test
    void blocksTheftIsFalseOnlyForAvailable() {
        assertFalse(ShadowLootState.available().blocksTheft());
        assertTrue(ShadowLootState.pending(UUID.randomUUID(), UUID.randomUUID(), 1L).blocksTheft());
        assertTrue(ShadowLootState.looted(UUID.randomUUID(), ITEM, 1, 1L).blocksTheft());
        assertTrue(ShadowLootState.corrupt().blocksTheft());
    }

    // ---- corrupt payloads: every case returns CORRUPT, never throws ----

    @Test
    void corruptPayloadsNeverThrow() {
        assertCorrupt(tag -> {
        }); // empty tag
        assertCorrupt(tag -> tag.putInt("dataVersion", 99)); // future version
        assertCorrupt(tag -> tag.putString("dataVersion", "1")); // wrong type
        assertCorrupt(tag -> tag.putString("state", "HYPOTHETICAL")); // unknown state
        assertCorrupt(tag -> { // missing version
            tag.putString("state", "PENDING");
        });
        assertCorrupt(tag -> { // pending: bad eventId type
            tag.putInt("dataVersion", 1);
            tag.putString("state", "PENDING");
            tag.putString("eventId", "not-a-uuid");
            tag.putUUID("thief", UUID.randomUUID());
            tag.putLong("startedAt", 1L);
        });
        assertCorrupt(tag -> { // pending: missing startedAt
            tag.putInt("dataVersion", 1);
            tag.putString("state", "PENDING");
            tag.putUUID("eventId", UUID.randomUUID());
            tag.putUUID("thief", UUID.randomUUID());
        });
        assertCorrupt(tag -> { // pending: negative startedAt
            tag.putInt("dataVersion", 1);
            tag.putString("state", "PENDING");
            tag.putUUID("eventId", UUID.randomUUID());
            tag.putUUID("thief", UUID.randomUUID());
            tag.putLong("startedAt", -1L);
        });
        assertCorrupt(tag -> { // looted: bad item id
            tag.putInt("dataVersion", 1);
            tag.putString("state", "LOOTED");
            tag.putUUID("eventId", UUID.randomUUID());
            tag.putString("itemId", "minecraft:..\\evil");
            tag.putInt("count", 1);
            tag.putLong("completedAt", 1L);
        });
        assertCorrupt(tag -> { // looted: count 0
            tag.putInt("dataVersion", 1);
            tag.putString("state", "LOOTED");
            tag.putUUID("eventId", UUID.randomUUID());
            tag.putString("itemId", "minecraft:cobblestone");
            tag.putInt("count", 0);
            tag.putLong("completedAt", 1L);
        });
        assertCorrupt(tag -> { // looted: count wrong type
            tag.putInt("dataVersion", 1);
            tag.putString("state", "LOOTED");
            tag.putUUID("eventId", UUID.randomUUID());
            tag.putString("itemId", "minecraft:cobblestone");
            tag.putString("count", "3");
            tag.putLong("completedAt", 1L);
        });
        assertCorrupt(tag -> { // available with payload
            tag.putInt("dataVersion", 1);
            tag.putString("state", "AVAILABLE");
            tag.putUUID("eventId", UUID.randomUUID());
        });
    }

    private static void assertCorrupt(java.util.function.Consumer<CompoundTag> mutator) {
        CompoundTag tag = new CompoundTag();
        mutator.accept(tag);
        ShadowLootState read = serializer().read(holder(), tag, provider());
        assertEquals(ShadowLootState.State.CORRUPT, read.state(),
                "corrupt payloads must read as CORRUPT, never throw, never silently default");
    }
}
