package com.vbuser.inf.light;

/**
 * Implemented by the {@code World} mixin so every world owns one extended light
 * engine and the chunk column code can reach it without a global lookup table.
 */
public interface ExtendedWorldLight {

    ExtendedLightEngine inf$lightEngine();
}
