package com.vbuser.inf;

public final class HeightConstants {

    public static final int VANILLA_MIN_Y = 0;
    public static final int VANILLA_MAX_Y = 255;

    public static final int MIN_Y = Integer.MIN_VALUE;
    public static final int MAX_Y = Integer.MAX_VALUE;

    public static boolean isValidY(int ignored) {
        return true;
    }

    public static boolean isVanillaY(int y) {
        return y >= VANILLA_MIN_Y && y <= VANILLA_MAX_Y;
    }

    public static boolean isExtendedY(int y) {
        return !isVanillaY(y);
    }

    private HeightConstants() {}
}