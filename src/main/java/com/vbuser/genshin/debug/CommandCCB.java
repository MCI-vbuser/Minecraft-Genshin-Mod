package com.vbuser.genshin.debug;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.FMLCommonHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

public class CommandCCB extends CommandBase {

    private static boolean clientDebugEnabled = false;
    private static String currentEntityFilter = "";

    @Override
    public String getName() {
        return "ccb";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "ccb <server|client|code> [args...]";
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length == 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "用法: " + getUsage(sender)));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "server":
                handleServerCommand(server, sender, Arrays.copyOfRange(args, 1, args.length));
                break;
            case "client":
                handleClientCommand(server, sender, Arrays.copyOfRange(args, 1, args.length));
                break;
            case "code":
                handleCodeCommand(server, sender, Arrays.copyOfRange(args, 1, args.length));
                break;
            default:
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "未知的子命令: " + args[0]));
        }
    }

    private void handleServerCommand(MinecraftServer server, ICommandSender sender, String[] args) {

        if (args.length == 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "server 子命令用法: entity|count|end|list"));
            return;
        }

        switch (args[0].toLowerCase()) {
            case "entity":
                if (args.length < 2) {
                    sender.sendMessage(new TextComponentString(TextFormatting.RED + "需要提供实体UUID"));
                    return;
                }
                handleEntityInfo(sender, args[1]);
                break;
            case "count":
                if (args.length < 2) {
                    sender.sendMessage(new TextComponentString(TextFormatting.RED + "需要提供实体名称"));
                    return;
                }
                handleEntityCount(server, sender, args[1]);
                break;
            case "end":
                handleEndDebug(sender);
                break;
            case "list":
                String entityName = args.length > 1 ? args[1] : "";
                handleEntityList(server, sender, entityName);
                break;
            default:
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "未知的server子命令: " + args[0]));
        }
    }

    private void handleClientCommand(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length == 0 || !args[0].equalsIgnoreCase("count")) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "client 子命令目前只支持: count <实体名称>"));
            return;
        }

        if (args.length < 2) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "需要提供实体名称"));
            return;
        }

        clientDebugEnabled = true;
        currentEntityFilter = args[1];
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "客户端调试已启用，显示实体: " + args[1]));

        NetworkHandler.sendToAllClients(new ClientDebugMessage(true, args[1]));
    }

    private void handleCodeCommand(MinecraftServer server, ICommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "code 子命令用法: map <类名> <哈希表名称>"));
            return;
        }

        if (args[0].equalsIgnoreCase("map")) {
            if (args.length < 2) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "需要提供类名"));
                return;
            }

            if (args.length < 3) {
                listClassMaps(args[1]);
            } else {
                printSpecificMap(args[1], args[2]);
            }
        } else {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "未知的code子命令: " + args[0]));
        }
    }

    private void listClassMaps(String className) {
        System.out.println("=== 类 " + className + " 中的Map字段列表 ===");

        try {
            Class<?> clazz = Class.forName(className);
            System.out.println("成功加载类: " + clazz.getName());

            List<Field> mapFields = new ArrayList<>();
            for (Field field : clazz.getDeclaredFields()) {
                if (Map.class.isAssignableFrom(field.getType())) {
                    mapFields.add(field);
                }
            }

            if (mapFields.isEmpty()) {
                System.out.println("未找到Map类型的字段");
                return;
            }

            System.out.println("找到 " + mapFields.size() + " 个Map字段:");
            for (Field field : mapFields) {
                String modifiers = Modifier.toString(field.getModifiers());
                System.out.println("  " + modifiers + " " + field.getType().getSimpleName() + " " + field.getName());
            }

        } catch (ClassNotFoundException e) {
            System.out.println("未找到类: " + className);
        }
    }

    private void printSpecificMap(String className, String mapName) {
        System.out.println("=== 在类 " + className + " 中搜索Map: " + mapName + " ===");

        try {
            Class<?> clazz = Class.forName(className);

            Field targetField = null;
            for (Field field : clazz.getDeclaredFields()) {
                if (field.getName().equals(mapName) && Map.class.isAssignableFrom(field.getType())) {
                    targetField = field;
                    break;
                }
            }

            if (targetField == null) {
                System.out.println("未找到名为 '" + mapName + "' 的Map字段");
                System.out.println("尝试模糊匹配...");
                for (Field field : clazz.getDeclaredFields()) {
                    if (field.getName().toLowerCase().contains(mapName.toLowerCase()) &&
                            Map.class.isAssignableFrom(field.getType())) {
                        System.out.println("找到相似字段: " + field.getName());
                    }
                }
                return;
            }

            targetField.setAccessible(true);
            System.out.println("找到Map字段: " + targetField.getName());
            System.out.println("类型: " + targetField.getType().getName());
            System.out.println("修饰符: " + Modifier.toString(targetField.getModifiers()));

            Object fieldValue = null;
            if (Modifier.isStatic(targetField.getModifiers())) {
                fieldValue = targetField.get(null);
            } else {
                Object instance = getClassInstance(clazz);
                if (instance != null) {
                    fieldValue = targetField.get(instance);
                } else {
                    System.out.println("无法获取非静态字段的实例");
                    return;
                }
            }

            if (fieldValue instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) fieldValue;
                System.out.println("Map大小: " + map.size());
                System.out.println("内容:");

                if (map.isEmpty()) {
                    System.out.println("  (空)");
                } else {
                    int count = 0;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (count++ < 20) {
                            System.out.println("  " + entry.getKey() + " -> " + entry.getValue());
                        } else {
                            System.out.println("  ... (还有 " + (map.size() - 20) + " 个条目)");
                            break;
                        }
                    }
                }
            } else {
                System.out.println("字段值为null或不是Map实例");
            }

        } catch (ClassNotFoundException e) {
            System.out.println("未找到类: " + className);
        } catch (Exception e) {
            System.out.println("处理过程中出错: " + e.getMessage());
        }
    }

    private void handleEntityInfo(ICommandSender sender, String uuidStr) {
        try {
            UUID uuid = UUID.fromString(uuidStr);
            World world = sender.getEntityWorld();

            Entity entity = null;
            for (Entity e : world.loadedEntityList) {
                if (e.getUniqueID().equals(uuid)) {
                    entity = e;
                    break;
                }
            }

            if (entity != null) {
                sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "实体信息: " + entity.toString()));
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "位置: " +
                        String.format("(%.2f, %.2f, %.2f)", entity.posX, entity.posY, entity.posZ)));
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "维度: " + entity.dimension));
                sender.sendMessage(new TextComponentString(TextFormatting.YELLOW + "类型: " + EntityList.getKey(entity)));
            } else {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "未找到UUID为 " + uuidStr + " 的实体"));
            }
        } catch (IllegalArgumentException e) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "无效的UUID格式: " + uuidStr));
        }
    }

    private void handleEntityCount(MinecraftServer server, ICommandSender sender, String entityName) {
        int totalCount = 0;
        StringBuilder result = new StringBuilder();

        ResourceLocation entityResource = null;
        try {
            entityResource = new ResourceLocation(entityName);
        } catch (Exception e) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "无效的实体名称格式，使用 minecraft:entity_name 格式"));
            return;
        }

        for (World world : server.worlds) {
            int worldCount = 0;
            for (Entity entity : world.loadedEntityList) {
                ResourceLocation entityKey = EntityList.getKey(entity);
                if (entityKey != null && entityKey.equals(entityResource)) {
                    worldCount++;
                }
            }

            totalCount += worldCount;

            if (worldCount > 0) {
                result.append(String.format("%s维度 %d: %d 个实体\n",
                        TextFormatting.AQUA, world.provider.getDimension(), worldCount));
            }
        }

        sender.sendMessage(new TextComponentString(TextFormatting.GREEN +
                "实体 '" + entityName + "' 总数: " + totalCount));
        if (result.length() > 0) {
            sender.sendMessage(new TextComponentString(result.toString()));
        }
    }

    private void handleEndDebug(ICommandSender sender) {
        clientDebugEnabled = false;
        currentEntityFilter = "";
        sender.sendMessage(new TextComponentString(TextFormatting.GREEN + "客户端调试已关闭"));
        NetworkHandler.sendToAllClients(new ClientDebugMessage(false, ""));
    }

    private void handleEntityList(MinecraftServer server, ICommandSender sender, String entityName) {
        List<Entity> allEntities = new ArrayList<>();
        ResourceLocation filterResource = null;

        if (!entityName.isEmpty()) {
            try {
                filterResource = new ResourceLocation(entityName);
            } catch (Exception e) {
                sender.sendMessage(new TextComponentString(TextFormatting.RED + "无效的实体名称格式"));
                return;
            }
        }

        for (World world : server.worlds) {
            for (Entity entity : world.loadedEntityList) {
                if (filterResource == null) {
                    allEntities.add(entity);
                } else {
                    ResourceLocation entityKey = EntityList.getKey(entity);
                    if (entityKey != null && entityKey.equals(filterResource)) {
                        allEntities.add(entity);
                    }
                }
            }
        }

        sender.sendMessage(new TextComponentString(TextFormatting.GREEN +
                "找到 " + allEntities.size() + " 个实体" +
                (entityName.isEmpty() ? "" : " (过滤: " + entityName + ")")));

        for (Entity entity : allEntities) {
            ResourceLocation entityKey = EntityList.getKey(entity);
            String info = String.format("%sUUID: %s, 类型: %s, 位置: (%.1f, %.1f, %.1f), 维度: %d",
                    TextFormatting.WHITE,
                    entity.getUniqueID(),
                    entityKey != null ? entityKey.toString() : "未知",
                    entity.posX, entity.posY, entity.posZ,
                    entity.dimension);
            sender.sendMessage(new TextComponentString(info));
        }
    }

    private Object getClassInstance(Class<?> clazz) {
        try {
            if (clazz == MinecraftServer.class) {
                return FMLCommonHandler.instance().getMinecraftServerInstance();
            } else if (clazz.isAssignableFrom(World.class)) {
                MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
                if (server != null) {
                    return server.getWorld(0);
                }
            }

            for (Field field : clazz.getDeclaredFields()) {
                if (field.getType() == clazz && Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    Object instance = field.get(null);
                    if (instance != null) {
                        return instance;
                    }
                }
            }

            try {
                Field instanceField = clazz.getDeclaredField("instance");
                if (Modifier.isStatic(instanceField.getModifiers())) {
                    instanceField.setAccessible(true);
                    return instanceField.get(null);
                }
            } catch (NoSuchFieldException e) {
                //
            }
        } catch (Exception e) {
            System.out.println("获取类实例时出错: " + e.getMessage());
        }

        return null;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return sender.canUseCommand(4, getName());
    }
}