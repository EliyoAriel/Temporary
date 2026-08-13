package com.temporary;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class TemporaryPlugin extends JavaPlugin implements Listener {

    private final Map<String, ChunkSession> sessions = new HashMap<>();
    private final Map<String, Integer> retries = new HashMap<>();
    private final AreaManager areaManager = new AreaManager();
    private final CoreProtectRollback rollback = new CoreProtectRollback(this);

    private BukkitTask schedulerTask;
    private File sessionsFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        sessionsFile = new File(getDataFolder(), "sessions.yml");
        reload();
        loadSessions();
        getServer().getPluginManager().registerEvents(this, this);
        var command = getCommand("temporary");
        if (command != null) {
            var handler = new com.temporary.commands.TemporaryCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        schedulerTask = getServer().getScheduler().runTaskTimer(this, this::tick, 0L, 20L);
    }

    @Override
    public void onDisable() {
        if (schedulerTask != null) schedulerTask.cancel();
        saveSessions();
    }

    public void reload() {
        reloadConfig();
        areaManager.load(getConfig());
        rollback.check();
        if (!rollback.isAvailable()) {
            getLogger().warning("CoreProtect not found; automatic rollbacks are disabled (sessions still tracked)");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        recordActivity(event.getBlock().getWorld().getName(), event.getBlock().getX() >> 4, event.getBlock().getZ() >> 4);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        recordActivity(event.getBlock().getWorld().getName(), event.getBlock().getX() >> 4, event.getBlock().getZ() >> 4);
    }

    void recordActivity(String worldName, int chunkX, int chunkZ) {
        AreaManager.Area area = areaManager.find(worldName, chunkX, chunkZ);
        if (area == null) return;
        long now = System.currentTimeMillis() / 1000;
        String key = ChunkSession.key(worldName, chunkX, chunkZ);
        retries.remove(key);
        ChunkSession session = sessions.get(key);
        if (session == null) {
            sessions.put(key, new ChunkSession(worldName, chunkX, chunkZ, now, now,
                    area.inactivityDelay(), area.rollbackBuffer()));
        } else {
            sessions.put(key, session.touch(now));
        }
    }

    private void tick() {
        long now = System.currentTimeMillis() / 1000;
        for (ChunkSession session : new HashMap<>(sessions).values()) {
            if (!session.expired(now)) continue;
            World world = Bukkit.getWorld(session.worldName());
            if (world == null) continue;
            String key = ChunkSession.key(session.worldName(), session.chunkX(), session.chunkZ());
            Location center = chunkCenter(world, session.chunkX(), session.chunkZ());
            long duration = session.duration(now);
            getServer().getScheduler().runTaskAsynchronously(this, () -> {
                boolean ok = rollback.rollback(center, duration);
                getServer().getScheduler().runTask(this, () -> finishRollback(session, key, ok));
            });
        }
    }

    private void finishRollback(ChunkSession session, String key, boolean ok) {
        if (ok) {
            sessions.remove(key, session);
            retries.remove(key);
            saveSessions();
            getLogger().info("Rolled back " + session.worldName() + " chunk "
                    + session.chunkX() + "," + session.chunkZ());
            return;
        }
        int n = retries.merge(key, 1, Integer::sum);
        if (n == 1 || n % 10 == 0) {
            getLogger().warning("Rollback for " + session.worldName() + " chunk "
                    + session.chunkX() + "," + session.chunkZ()
                    + (n == 1 ? " is busy or failed (will retry)"
                              : " still failing after " + n + " attempts"));
        }
    }

    private Location chunkCenter(World world, int chunkX, int chunkZ) {
        return new Location(world, chunkX * 16 + 8, 0, chunkZ * 16 + 8);
    }

    public boolean forceRollback(String worldName, int chunkX, int chunkZ) {
        String key = ChunkSession.key(worldName, chunkX, chunkZ);
        ChunkSession session = sessions.get(key);
        if (session == null) return false;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return false;
        Location center = chunkCenter(world, chunkX, chunkZ);
        long duration = session.duration(System.currentTimeMillis() / 1000);
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            boolean ok = rollback.rollback(center, duration);
            getServer().getScheduler().runTask(this, () -> finishRollback(session, key, ok));
        });
        return true;
    }

    public String listSessions() {
        if (sessions.isEmpty()) return "No temporary chunks are currently tracked.";
        StringBuilder sb = new StringBuilder();
        for (ChunkSession session : sessions.values()) {
            sb.append(session.worldName()).append(" (").append(session.chunkX()).append(",")
                    .append(session.chunkZ()).append(") - inactive after ")
                    .append(session.inactivityDelay()).append("s\n");
        }
        return sb.toString().trim();
    }

    private void loadSessions() {
        if (!sessionsFile.exists()) return;
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(sessionsFile);
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            if (section == null) continue;
            sessions.put(key, new ChunkSession(
                    section.getString("world", ""),
                    section.getInt("chunkX"),
                    section.getInt("chunkZ"),
                    section.getLong("sessionStart"),
                    section.getLong("lastActivity"),
                    section.getLong("inactivityDelay"),
                    section.getLong("rollbackBuffer")
            ));
        }
        getLogger().info("Loaded " + sessions.size() + " pending session(s)");
    }

    private void saveSessions() {
        FileConfiguration yaml = new YamlConfiguration();
        for (ChunkSession session : sessions.values()) {
            String key = ChunkSession.key(session.worldName(), session.chunkX(), session.chunkZ());
            yaml.set(key + ".world", session.worldName());
            yaml.set(key + ".chunkX", session.chunkX());
            yaml.set(key + ".chunkZ", session.chunkZ());
            yaml.set(key + ".sessionStart", session.sessionStart());
            yaml.set(key + ".lastActivity", session.lastActivity());
            yaml.set(key + ".inactivityDelay", session.inactivityDelay());
            yaml.set(key + ".rollbackBuffer", session.rollbackBuffer());
        }
        try {
            yaml.save(sessionsFile);
        } catch (IOException e) {
            getLogger().severe("Could not save sessions.yml: " + e.getMessage());
        }
    }
}
