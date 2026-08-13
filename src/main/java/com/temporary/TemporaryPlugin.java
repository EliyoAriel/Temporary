package com.temporary;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TemporaryPlugin extends JavaPlugin implements Listener {

    private final Map<String, ChunkSession> sessions = new HashMap<>();
    private final Map<String, Integer> retries = new HashMap<>();
    private final AreaManager areaManager = new AreaManager();
    private final CoreProtectRollback rollback = new CoreProtectRollback(this);

    private BukkitTask schedulerTask;
    private final SessionDatabase db = new SessionDatabase(this);
    private SupplyDropListener supplyDropListener;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reload();
        db.init(getDataFolder());
        sessions.putAll(db.loadAll());
        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().getPlugin("SupplyDrop") != null) {
            supplyDropListener = new SupplyDropListener(this);
            getServer().getPluginManager().registerEvents(supplyDropListener, this);
            supplyDropListener.seedActiveCrates();
        }
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
        if (supplyDropListener != null) supplyDropListener.disable();
        if (schedulerTask != null) schedulerTask.cancel();
        db.shutdown();
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
        recordActivity(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        recordActivity(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        recordActivity(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        recordActivity(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        recordActivity(event.blockList());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        recordActivity(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        recordActivity(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        recordActivity(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        recordActivity(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        recordActivity(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        recordActivity(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        recordActivity(event.getBlocks());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        recordActivity(event.getBlocks());
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        recordActivity(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        recordActivity(event.getBlock());
    }

    private void recordActivity(Block block) {
        recordActivity(block.getWorld().getName(), block.getX() >> 4, block.getZ() >> 4);
    }

    private void recordActivity(Collection<Block> blocks) {
        for (Block block : blocks) {
            recordActivity(block);
        }
    }

    void recordActivity(String worldName, int chunkX, int chunkZ) {
        AreaManager.Area area = areaManager.find(worldName, chunkX, chunkZ);
        if (area == null) return;
        long now = System.currentTimeMillis() / 1000;
        String key = ChunkSession.key(worldName, chunkX, chunkZ);
        retries.remove(key);
        ChunkSession session = sessions.get(key);
        if (session == null) {
            session = new ChunkSession(worldName, chunkX, chunkZ, now, now,
                    area.inactivityDelay(), area.rollbackBuffer());
            sessions.put(key, session);
        } else {
            session = session.touch(now);
            sessions.put(key, session);
        }
        db.save(session);
    }

    private void tick() {
        long now = System.currentTimeMillis() / 1000;
        for (ChunkSession session : new HashMap<>(sessions).values()) {
            if (!session.expired(now)) continue;
            String key = ChunkSession.key(session.worldName(), session.chunkX(), session.chunkZ());
            if (supplyDropListener != null && supplyDropListener.isProtected(key)) continue;
            World world = Bukkit.getWorld(session.worldName());
            if (world == null) continue;
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
            db.remove(session.worldName(), session.chunkX(), session.chunkZ());
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

    // Clamps the session start to the crate's landing so the eventual rollback
    // restores the pre-crate state, not pre-session builds. No-op outside areas.
    void onCrateLanded(String worldName, int chunkX, int chunkZ, long landTimeMs) {
        AreaManager.Area area = areaManager.find(worldName, chunkX, chunkZ);
        if (area == null) return;
        long landSec = landTimeMs / 1000;
        long now = System.currentTimeMillis() / 1000;
        String key = ChunkSession.key(worldName, chunkX, chunkZ);
        retries.remove(key);
        ChunkSession session = sessions.get(key);
        long start = session == null ? landSec : Math.min(session.sessionStart(), landSec);
        sessions.put(key, new ChunkSession(worldName, chunkX, chunkZ, start, now,
                area.inactivityDelay(), area.rollbackBuffer()));
        db.save(sessions.get(key));
    }

    public BaseComponent[] listSessions() {
        if (sessions.isEmpty()) {
            return new BaseComponent[]{new TextComponent(ChatColor.GRAY + "No temporary chunks are currently tracked.")};
        }
        long now = System.currentTimeMillis() / 1000;
        List<BaseComponent> lines = new ArrayList<>();
        for (ChunkSession session : sessions.values()) {
            String key = ChunkSession.key(session.worldName(), session.chunkX(), session.chunkZ());
            String status;
            if (supplyDropListener != null && supplyDropListener.isProtected(key)) {
                status = ChatColor.YELLOW + "Pending (crate active)";
            } else {
                long left = Math.max(0, session.inactivityDelay() - (now - session.lastActivity()));
                status = ChatColor.GREEN + formatTime(left) + ChatColor.WHITE + " left";
            }
            TextComponent line = new TextComponent(ChatColor.GRAY + session.worldName() + ChatColor.WHITE + " ("
                    + ChatColor.AQUA + session.chunkX() + "," + session.chunkZ() + ChatColor.WHITE + ") - " + status);
            line.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/temporary tp " + session.worldName() + " " + session.chunkX() + "," + session.chunkZ()));
            line.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder(ChatColor.GREEN + "Click to teleport to this chunk").create()));
            lines.add(line);
        }
        return lines.toArray(new BaseComponent[0]);
    }

    private static String formatTime(long totalSec) {
        long h = totalSec / 3600, m = (totalSec % 3600) / 60, s = totalSec % 60;
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }
}
