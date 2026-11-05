package com.vbuser.inf.chunk;

/**
 * Implemented by the {@code Chunk} mixin so each chunk owns its extended
 * column directly instead of requiring a global synchronized map lookup on
 * every block access.
 */
public interface ExtendedColumnHolder {

    ExtendedColumn inf$getExtendedColumn(boolean create);
}
