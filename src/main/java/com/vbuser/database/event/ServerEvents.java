package com.vbuser.database.event;

import com.vbuser.database.sql.DatabaseHelper;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

import java.io.File;

public class ServerEvents {

    boolean ignored;

    public void onServerStarting(FMLServerStartingEvent event) {
        File worldDir = event.getServer().getEntityWorld().getSaveHandler().getWorldDirectory();
        File dbDir = new File(worldDir, "database");
        if (!dbDir.exists()) {
            ignored = dbDir.mkdirs();
        }

        String dbUrl = "jdbc:h2:" + new File(dbDir, "database").getAbsolutePath() +
                ";DB_CLOSE_DELAY=-1";
        DatabaseHelper.initialize(dbUrl);
    }
}