package com.temporary;

import com.supplydrop.Crate;
import com.supplydrop.events.CrateLandedEvent;
import com.supplydrop.events.CrateRemovedEvent;
import com.supplydrop.helpers.CrateManager;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class SupplyDropListener implements Listener {

    private record CrateInfo(String chunkKey, long landTimeMs) {}

    private final TemporaryPlugin plugin;
    private final Map<String, CrateInfo> crates = new HashMap<>();   // blockKey -> crate info
    private final Set<String> protectedChunks = new HashSet<>();     // chunk keys with an active crate
    private boolean disabling = false;

    public SupplyDropListener(TemporaryPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onCrateLanded(CrateLandedEvent event) {
        if (disabling) return;
        Location loc = event.getLandedLocation();
        if (loc == null || loc.getWorld() == null) return;
        trackCrate(loc, event.getLandTimeMs());
    }

    @EventHandler
    public void onCrateRemoved(CrateRemovedEvent event) {
        if (disabling) return;
        Location loc = event.getLandedLocation();
        if (loc == null || loc.getWorld() == null) return;
        crates.remove(blockKey(loc));
        String chunkKey = ChunkSession.key(loc.getWorld().getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        for (CrateInfo info : crates.values()) {
            if (info.chunkKey().equals(chunkKey)) {
                return; // another crate still active in this chunk
            }
        }
        protectedChunks.remove(chunkKey);
    }

    // Covers crates restored from SupplyDrop's DB at startup (their landed event
    // fired before this plugin's listener was registered).
    public void seedActiveCrates() {
        for (Crate crate : CrateManager.getActiveCrates()) {
            Location loc = crate.getLandedLocation();
            if (loc == null || loc.getWorld() == null) continue;
            trackCrate(loc, crate.getLandedAtMs());
        }
    }

    public boolean isProtected(String chunkKey) {
        return protectedChunks.contains(chunkKey);
    }

    public void disable() {
        disabling = true;
        crates.clear();
        protectedChunks.clear();
    }

    private void trackCrate(Location loc, long landTimeMs) {
        String chunkKey = ChunkSession.key(loc.getWorld().getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
        crates.put(blockKey(loc), new CrateInfo(chunkKey, landTimeMs));
        protectedChunks.add(chunkKey);
        plugin.onCrateLanded(loc.getWorld().getName(), loc.getBlockX() >> 4, loc.getBlockZ() >> 4, landTimeMs);
    }

    private static String blockKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }
}
