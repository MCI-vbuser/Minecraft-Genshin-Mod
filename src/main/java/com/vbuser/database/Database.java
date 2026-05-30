package com.vbuser.database;

import com.vbuser.database.command.CommandHash;
import com.vbuser.database.command.CommandSql;
import com.vbuser.database.event.ServerEvents;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

@Mod(modid = "database", name = "Database", version = "release 1.0.04-rc")
public class Database {
    @Mod.EventHandler
    public void onServerStarting(FMLServerStartingEvent event) {
        new ServerEvents().onServerStarting(event);
        event.registerServerCommand(new CommandHash());
        event.registerServerCommand(new CommandSql());
    }
}
