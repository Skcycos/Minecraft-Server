package com.tanrunn.tcth.impl.signature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;

/**
 * Unit tests for {@link CookingSignature}: codec/streamcodec round-trips,
 * name sanitisation and equality semantics.
 */
class CookingSignatureTest {

    private static final UUID CHEF_A = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID CHEF_B = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @BeforeAll
    static void bootstrap() {
        SignatureTestRegistry.ensureRegistered();
    }

    @Test
    void codecRoundTripPreservesFields() {
        CookingSignature sig = new CookingSignature(CHEF_A, "Tanrunn");
        CookingSignature decoded = CookingSignature.CODEC
                .parse(JsonOps.INSTANCE, CookingSignature.CODEC.encodeStart(JsonOps.INSTANCE, sig).result().orElseThrow())
                .result().orElseThrow();
        assertEquals(CHEF_A, decoded.chefId());
        assertEquals("Tanrunn", decoded.chefName());
    }

    @Test
    void streamCodecRoundTripPreservesFields() {
        CookingSignature sig = new CookingSignature(CHEF_A, "Tanrunn");
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        CookingSignature.STREAM_CODEC.encode(buf, sig);
        CookingSignature decoded = CookingSignature.STREAM_CODEC.decode(buf);
        assertEquals(CHEF_A, decoded.chefId());
        assertEquals("Tanrunn", decoded.chefName());
        assertEquals(0, buf.readableBytes(), "no trailing bytes after decode");
    }

    @Test
    void streamCodecUuidIsFixedSixteenBytes() {
        // UUIDUtil.STREAM_CODEC is a fixed 16-byte encoding (UUID_BYTES).
        FriendlyByteBuf uuidBuf = new FriendlyByteBuf(Unpooled.buffer());
        UUIDUtil.STREAM_CODEC.encode(uuidBuf, CHEF_A);
        assertEquals(16, uuidBuf.readableBytes(), "UUID must be encoded as fixed 16 bytes");

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        CookingSignature.STREAM_CODEC.encode(buf, new CookingSignature(CHEF_A, "Tanrunn"));
        // total = 16 (UUID) + varint length prefix (1) + name chars (7)
        assertEquals(16 + 1 + "Tanrunn".length(), buf.readableBytes(),
                "signature wire size must be UUID(16) + bounded string");
    }

    @Test
    void streamCodecAcceptsMaxLengthName() {
        CookingSignature sig = new CookingSignature(CHEF_A, "x".repeat(CookingSignature.MAX_NAME_LENGTH));
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        CookingSignature.STREAM_CODEC.encode(buf, sig);
        assertEquals("x".repeat(CookingSignature.MAX_NAME_LENGTH), CookingSignature.STREAM_CODEC.decode(buf).chefName());
    }

    @Test
    void streamCodecRejectsOverlongNameOnDecode() {
        // Encode an over-long (33-char) name with an UNBOUNDED codec, then the
        // bounded decoder must reject it instead of reading unbounded data.
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        ByteBufCodecs.STRING_UTF8.encode(buf, "y".repeat(CookingSignature.MAX_NAME_LENGTH + 1));
        assertThrows(DecoderException.class,
                () -> CookingSignature.STREAM_CODEC.decode(buf),
                "a name longer than MAX_NAME_LENGTH must be rejected on the wire");
    }

    @Test
    void codecDecodeStillSanitizesName() {
        CookingSignature decoded = CookingSignature.CODEC
                .parse(JsonOps.INSTANCE, CookingSignature.CODEC.encodeStart(JsonOps.INSTANCE,
                        new CookingSignature(CHEF_A, "\u00a7aBad")).result().orElseThrow())
                .result().orElseThrow();
        assertEquals("Bad", decoded.chefName(), "NBT/JSON decode must still run sanitize");
    }

    @Test
    void blankNameIsEmptyAndNeverRendered() {
        CookingSignature sig = new CookingSignature(CHEF_A, "   ");
        assertEquals("", sig.chefName());
    }

    @Test
    void uuidAndNameArePreserved() {
        CookingSignature sig = new CookingSignature(CHEF_A, "Chef-Name-1");
        assertEquals(CHEF_A, sig.chefId());
        assertEquals("Chef-Name-1", sig.chefName());
    }

    @Test
    void overlongNameIsTruncatedToMaxLength() {
        CookingSignature sig = new CookingSignature(CHEF_A, "x".repeat(100));
        assertEquals(CookingSignature.MAX_NAME_LENGTH, sig.chefName().length());
    }

    @Test
    void formattingCodesAndControlCharsAreStripped() {
        CookingSignature sig = new CookingSignature(CHEF_A, "\u00a7aColored\u00a7r\u0001\u0002Plain");
        assertEquals("ColoredPlain", sig.chefName());
    }

    @Test
    void nullNameBecomesEmpty() {
        CookingSignature sig = new CookingSignature(CHEF_A, null);
        assertEquals("", sig.chefName());
    }

    @Test
    void sameChefSignaturesAreEqual() {
        assertEquals(new CookingSignature(CHEF_A, "Tanrunn"), new CookingSignature(CHEF_A, "Tanrunn"));
    }

    @Test
    void differentChefSignaturesAreNotEqual() {
        assertNotEquals(new CookingSignature(CHEF_A, "Tanrunn"), new CookingSignature(CHEF_B, "Tanrunn"));
        assertNotEquals(new CookingSignature(CHEF_A, "Tanrunn"), new CookingSignature(CHEF_A, "Other"));
    }

    @Test
    void sanitizeIsIdempotent() {
        String clean = CookingSignature.sanitize("\u00a7c" + "abc".repeat(20));
        assertEquals(clean, CookingSignature.sanitize(clean));
        assertTrue(clean.length() <= CookingSignature.MAX_NAME_LENGTH);
    }
}
