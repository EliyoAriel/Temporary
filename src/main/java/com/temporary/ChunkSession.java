package com.temporary;

public record ChunkSession(
        String worldName,
        int chunkX,
        int chunkZ,
        long sessionStart,
        long lastActivity,
        long inactivityDelay,
        long rollbackBuffer
) {
    public static String key(String world, int cx, int cz) {
        return world + ";" + cx + ";" + cz;
    }

    public ChunkSession touch(long now) {
        return new ChunkSession(worldName, chunkX, chunkZ, sessionStart, now, inactivityDelay, rollbackBuffer);
    }

    public boolean expired(long now) {
        return now - lastActivity >= inactivityDelay;
    }

    public long duration(long now) {
        return (now - sessionStart) + rollbackBuffer;
    }
}
