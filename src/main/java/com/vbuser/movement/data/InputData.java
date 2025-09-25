package com.vbuser.movement.data;

import net.minecraft.entity.player.EntityPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InputData {
    public static final class State {
        public float moveForward;
        public float moveStrafing;

        @Override
        public boolean equals(Object object) {
            return object instanceof State && (((State) object).moveForward == moveForward && ((State) object).moveStrafing == moveStrafing);
        }
    }

    private static final Map<UUID, State> MAP = new ConcurrentHashMap<>();

    public static State get(EntityPlayer p) {
        return MAP.computeIfAbsent(p.getUniqueID(), k -> new State());
    }

    public static void remove(EntityPlayer p){
        MAP.remove(p.getUniqueID());
    }
}
