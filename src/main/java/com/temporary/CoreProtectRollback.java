package com.temporary;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.java.JavaPlugin;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;

import java.util.ArrayList;
import java.util.List;

public class CoreProtectRollback {

    // ponytail: CoreProtect boxes are `center ± r`, inclusive on both sides, so an
    // exact 16-block chunk is impossible — radius 8 around the chunk's center block
    // covers the chunk plus 1 block into the +x/+z neighbor. Overlap beats
    // under-coverage; revisit only if boundary precision ever matters.
    private static final int RADIUS = 8;

    private final JavaPlugin plugin;
    private CoreProtectAPI api;

    public CoreProtectRollback(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void check() {
        CoreProtect coreProtect = (CoreProtect) Bukkit.getPluginManager().getPlugin("CoreProtect");
        api = coreProtect == null ? null : coreProtect.getAPI();
    }

    public boolean isAvailable() {
        return api != null;
    }

    // Must run off the primary thread: CoreProtect rejects main-thread rollbacks.
    // False means busy, disabled, or errored — the caller retries next tick.
    public boolean rollback(Location center, long durationSeconds) {
        if (api == null || Bukkit.isPrimaryThread()) return false;
        try {
            // CE 24.0 mutates the passed lists (auto-adds #global / block actions),
            // so they must be mutable — List.of() throws UnsupportedOperationException.
            List<String[]> result = api.performRollback(
                    (int) durationSeconds,
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                    RADIUS, center);
            return result != null;
        } catch (Exception e) {
            plugin.getLogger().severe("CoreProtect rollback threw an exception: " + e);
            for (StackTraceElement element : e.getStackTrace()) {
                plugin.getLogger().severe("  at " + element);
            }
            return false;
        }
    }
}
