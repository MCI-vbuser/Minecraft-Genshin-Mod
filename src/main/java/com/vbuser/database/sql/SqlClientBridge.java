package com.vbuser.database.sql;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Keeps client-only classes out of {@link SqlApi}.
 *
 * <p>{@code SqlApi} is a common class, so it must not reference {@code Minecraft}
 * directly; this holder is stripped from dedicated server builds.</p>
 */
@SideOnly(Side.CLIENT)
final class SqlClientBridge {

    private SqlClientBridge() {
    }

    static boolean isConnected() {
        return Minecraft.getMinecraft().getConnection() != null;
    }
}
