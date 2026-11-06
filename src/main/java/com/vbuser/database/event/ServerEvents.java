package com.vbuser.database.event;

import com.vbuser.database.sql.SqlApi;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

import java.io.File;

public class ServerEvents {

    public void onServerStarting(FMLServerStartingEvent event) {
        File worldDir = event.getServer().getEntityWorld().getSaveHandler().getWorldDirectory();
        File databaseFile = new File(new File(worldDir, "database"), "database");
        SqlApi.initialize(databaseFile);
    }
}
