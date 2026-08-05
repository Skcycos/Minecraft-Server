package com.tanrunn.tcth.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.tanrunn.tcth.impl.signature.CookingSignature;

/**
 * Tests for the client tooltip render gate {@link TooltipEvents#shouldRender}.
 * The render decision is pure logic, testable without a running client.
 */
class TooltipRenderTest {

    @Test
    void nullSignatureNeverRenders() {
        assertFalse(TooltipEvents.shouldRender(null));
    }

    @Test
    void validSignatureRenders() {
        assertTrue(TooltipEvents.shouldRender(new CookingSignature(UUID.randomUUID(), "Tanrunn")));
    }

    @Test
    void blankNameNeverRenders() {
        assertFalse(TooltipEvents.shouldRender(new CookingSignature(UUID.randomUUID(), "   ")));
        assertFalse(TooltipEvents.shouldRender(new CookingSignature(UUID.randomUUID(), "")));
        assertFalse(TooltipEvents.shouldRender(new CookingSignature(UUID.randomUUID(), null)));
    }

    @Test
    void sanitizedFormatCodeNameRendersCleaned() {
        CookingSignature sig = new CookingSignature(UUID.randomUUID(), "\u00a7aTanrunn");
        assertTrue(TooltipEvents.shouldRender(sig));
        assertFalse(sig.chefName().contains("\u00a7"), "rendered name must be sanitized");
    }
}
