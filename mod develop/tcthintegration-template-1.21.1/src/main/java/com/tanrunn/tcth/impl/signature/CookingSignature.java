package com.tanrunn.tcth.impl.signature;

import java.util.Objects;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * Immutable chef signature attached to a finished dish as the
 * {@code tcth:cooking_signature} data component.
 *
 * <p>This is <em>presentation and provenance</em> data, not a trusted economic
 * credential: creative mode, admin commands or third-party mods can construct
 * items carrying any component. Future gold/experience/order settlement must
 * never trust the signature on an {@link net.minecraft.world.item.ItemStack}
 * alone — rewards stay keyed to real server-side
 * {@code DishCookedEvent}s, order state and idempotent records.
 *
 * <p>Deliberately minimal:
 * <ul>
 *   <li>{@code chefId} — the authoritative identity ({@link UUID} of the
 *       player who took the dish out);</li>
 *   <li>{@code chefName} — a name <em>snapshot</em> taken at signing time for
 *       client-side display; renames do not rewrite old signatures (historical
 *       signature snapshots are expected to stack separately).</li>
 * </ul>
 * No cook time, event id, device, quality, count or position is stored.
 *
 * <p>Name safety: the constructor sanitizes the name (strips {@code §} format
 * codes and control characters, truncates to {@value #MAX_NAME_LENGTH} chars)
 * so decoding from any source can never inject tooltip codes or unbounded
 * strings. {@code chefId} must not be null.
 */
public record CookingSignature(UUID chefId, String chefName) {

    /** Maximum display-name length stored in a signature. */
    public static final int MAX_NAME_LENGTH = 32;

    /** UUID codec (NBT) — standard Minecraft UUID codec. */
    private static final Codec<UUID> UUID_CODEC = UUIDUtil.CODEC;

    public static final Codec<CookingSignature> CODEC = RecordCodecBuilder.create(instance -> instance
            .group(UUID_CODEC.fieldOf("chefId").forGetter(CookingSignature::chefId),
                    Codec.STRING.fieldOf("chefName").forGetter(CookingSignature::chefName))
            .apply(instance, CookingSignature::new));

    /**
     * Network codec: UUID as fixed 16 bytes ({@link UUIDUtil#STREAM_CODEC});
     * name bounded to {@value #MAX_NAME_LENGTH} chars via
     * {@code stringUtf8(MAX_NAME_LENGTH)} (never unbounded).
     */
    public static final StreamCodec<ByteBuf, CookingSignature> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, CookingSignature::chefId,
            ByteBufCodecs.stringUtf8(CookingSignature.MAX_NAME_LENGTH), CookingSignature::chefName,
            CookingSignature::new);

    public CookingSignature {
        Objects.requireNonNull(chefId, "chefId");
        chefName = sanitize(chefName);
    }

    /**
     * Sanitizes a name for safe display: strips {@code §} (formatting codes),
     * strips ISO control characters, trims and truncates to
     * {@value #MAX_NAME_LENGTH} characters.
     *
     * @param raw the raw name
     * @return the safe name (never null, never empty after cleaning it may be
     *         empty if the input was only control characters)
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && sb.length() < MAX_NAME_LENGTH; i++) {
            char c = raw.charAt(i);
            if (c == '\u00a7') {
                // Skip the format code char itself (and the following format
                // letter, e.g. §a or §r) so no tooltip codes can leak through.
                i++;
                continue;
            }
            if (Character.isISOControl(c)) {
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }
}
