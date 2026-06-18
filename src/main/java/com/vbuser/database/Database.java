package com.vbuser.database;

import com.vbuser.database.command.CommandHash;
import com.vbuser.database.command.CommandSql;
import com.vbuser.database.event.ServerEvents;
import com.vbuser.database.sql.DatabaseHelper;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;

@Mod(modid = "database", name = "Database", version = "release 1.0.04-rc")
public class Database {
    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        new ServerEvents().onServerStarting(event);
        event.registerServerCommand(new CommandHash());
        event.registerServerCommand(new CommandSql());
    }

    @Mod.EventHandler
    public void onServerStopping(FMLServerStoppingEvent event) {
        DatabaseHelper.shutdown();
    }
}
