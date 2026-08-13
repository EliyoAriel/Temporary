package com.temporary.commands;

import com.temporary.TemporaryPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

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
                sender.sendMessage(plugin.listSessions());
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
            return List.of("reload", "list", "rollback").stream().filter(s -> s.startsWith(lower)).toList();
        }
        return List.of();
    }
}
