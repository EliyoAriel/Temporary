package com.temporary;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AreaManager {

    public record Area(int inactivityDelay, int rollbackBuffer) {}

    private final List<WorldArea> worldAreas = new ArrayList<>();
    private final List<RangeArea> rangeAreas = new ArrayList<>();
    private final List<ChunkArea> chunkAreas = new ArrayList<>();

    private int defaultInactivityDelay = 300;
    private int defaultRollbackBuffer = 5;

    public void load(FileConfiguration cfg) {
        worldAreas.clear();
        rangeAreas.clear();
        chunkAreas.clear();

        defaultInactivityDelay = cfg.getInt("inactivity-delay", 300);
        defaultRollbackBuffer = cfg.getInt("rollback-time-buffer", 5);

        for (String world : cfg.getStringList("world-modes")) {
            if (world != null && !world.isBlank()) {
                worldAreas.add(new WorldArea(world, new Area(defaultInactivityDelay, defaultRollbackBuffer)));
            }
        }

        for (Map<?, ?> map : cfg.getMapList("chunk-ranges")) {
            String world = (String) map.get("world");
            if (world == null) continue;
            int minX = intOr(map, "min-chunk-x", 0);
            int maxX = intOr(map, "max-chunk-x", 0);
            int minZ = intOr(map, "min-chunk-z", 0);
            int maxZ = intOr(map, "max-chunk-z", 0);
            if (maxX < minX) maxX = minX;
            if (maxZ < minZ) maxZ = minZ;
            rangeAreas.add(new RangeArea(world, minX, maxX, minZ, maxZ,
                    new Area(intOr(map, "inactivity-delay", defaultInactivityDelay),
                            intOr(map, "rollback-time-buffer", defaultRollbackBuffer))));
        }

        for (Map<?, ?> map : cfg.getMapList("specific-chunks")) {
            String world = (String) map.get("world");
            Object raw = map.get("chunks");
            if (world == null || !(raw instanceof List<?> chunks)) continue;
            Set<String> coords = new HashSet<>();
            for (Object entry : chunks) {
                if (entry instanceof String s && s.matches("-?\\d+,-?\\d+")) {
                    coords.add(s);
                }
            }
            if (!coords.isEmpty()) {
                chunkAreas.add(new ChunkArea(world, coords,
                        new Area(intOr(map, "inactivity-delay", defaultInactivityDelay),
                                intOr(map, "rollback-time-buffer", defaultRollbackBuffer))));
            }
        }
    }

    public Area find(String worldName, int chunkX, int chunkZ) {
        for (WorldArea area : worldAreas) {
            if (area.world().equals(worldName)) return area.area();
        }
        for (RangeArea area : rangeAreas) {
            if (area.world().equals(worldName)
                    && chunkX >= area.minChunkX() && chunkX <= area.maxChunkX()
                    && chunkZ >= area.minChunkZ() && chunkZ <= area.maxChunkZ()) {
                return area.area();
            }
        }
        String coord = chunkX + "," + chunkZ;
        for (ChunkArea area : chunkAreas) {
            if (area.world().equals(worldName) && area.chunks().contains(coord)) return area.area();
        }
        return null;
    }

    private static int intOr(Map<?, ?> map, String key, int def) {
        Object value = map.get(key);
        return value instanceof Number n ? n.intValue() : def;
    }

    private record WorldArea(String world, Area area) {}
    private record RangeArea(String world, int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, Area area) {}
    private record ChunkArea(String world, Set<String> chunks, Area area) {}
}
