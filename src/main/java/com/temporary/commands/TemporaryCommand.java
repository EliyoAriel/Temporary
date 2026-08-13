package com.temporary.commands;

import com.temporary.TemporaryPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public class TemporaryCommand implements CommandExecutor, TabCompleter {

    private final TemporaryPlugin plugin;

    public TemporaryCommand(TemporaryPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /temporary <reload|list|rollback <world> <chunkX,chunkZ>>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> {
                if (!sender.hasPermission("temporary.reload")) {
                    sender.sendMessage("You don't have permission to do that.");
                    return true;
                }
                plugin.reload();
                sender.sendMessage("Temporary config reloaded.");
            }
            case "list" -> {
                if (!sender.hasPermission("temporary.list")) {
                    sender.sendMessage("You don't have permission to do that.");
                    return true;
                }
                sender.spigot().sendMessage(plugin.listSessions());
            }
            case "rollback" -> {
                if (!sender.hasPermission("temporary.rollback")) {
                    sender.sendMessage("You don't have permission to do that.");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage("Usage: /temporary rollback <world> <chunkX,chunkZ>");
                    return true;
                }
                int[] coords = parseChunkCoords(args[2]);
                if (coords == null) {
                    sender.sendMessage("Invalid chunk coordinates. Use format: X,Z");
                    return true;
                }
                boolean ok = plugin.forceRollback(args[1], coords[0], coords[1]);
                sender.sendMessage(ok ? "Rollback dispatched for " + args[1] + " chunk " + args[2] + "."
                        : "No active session for " + args[1] + " chunk " + args[2] + ".");
            }
            case "tp" -> {
                if (!sender.hasPermission("temporary.tp")) {
                    sender.sendMessage("You don't have permission to do that.");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Only players can teleport.");
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage("Usage: /temporary tp <world> <chunkX,chunkZ>");
                    return true;
                }
                World world = Bukkit.getWorld(args[1]);
                if (world == null) {
                    sender.sendMessage("World not found: " + args[1]);
                    return true;
                }
                int[] coords = parseChunkCoords(args[2]);
                if (coords == null) {
                    sender.sendMessage("Invalid chunk coordinates. Use format: X,Z");
                    return true;
                }
                int bx = coords[0] * 16 + 8, bz = coords[1] * 16 + 8;
                player.teleport(new Location(world, bx + 0.5, world.getHighestBlockYAt(bx, bz), bz + 0.5));
                sender.sendMessage(ChatColor.GREEN + "Teleported to " + world.getName() + " chunk "
                        + ChatColor.AQUA + args[2] + ChatColor.GREEN + ".");
            }
            default -> sender.sendMessage("Usage: /temporary <reload|list|rollback <world> <chunkX,chunkZ>>");
        }
        return true;
    }

    private int[] parseChunkCoords(String input) {
        String[] parts = input.split("[,\\s]");
        if (parts.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String lower = args[0].toLowerCase();
            return List.of("reload", "list", "rollback", "tp").stream().filter(s -> s.startsWith(lower)).toList();
        }
        return List.of();
    }
}
