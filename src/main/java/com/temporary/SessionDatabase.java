package com.temporary;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class SessionDatabase {

    private static final String TABLE = "sessions";

    private final JavaPlugin plugin;
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();

    private Connection connection;
    private Thread writer;
    private volatile boolean running;

    public SessionDatabase(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void init(File dataFolder) {
        try {
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + new File(dataFolder, "sessions.db").getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                        + "world TEXT NOT NULL, chunk_x INTEGER NOT NULL, chunk_z INTEGER NOT NULL, "
                        + "session_start INTEGER NOT NULL, last_activity INTEGER NOT NULL, "
                        + "inactivity_delay INTEGER NOT NULL, rollback_buffer INTEGER NOT NULL, "
                        + "PRIMARY KEY (world, chunk_x, chunk_z))");
            }
            running = true;
            writer = new Thread(this::drainLoop, "Temporary-DB-Writer");
            writer.setDaemon(true);
            writer.start();
        } catch (Exception e) {
            plugin.getLogger().warning("SQLite init failed; sessions will not persist: " + e.getMessage());
        }
    }

    public void save(ChunkSession s) {
        enqueue(() -> {
            String sql = "INSERT INTO " + TABLE
                    + " (world, chunk_x, chunk_z, session_start, last_activity, inactivity_delay, rollback_buffer) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(world, chunk_x, chunk_z) DO UPDATE SET "
                    + "session_start = excluded.session_start, last_activity = excluded.last_activity, "
                    + "inactivity_delay = excluded.inactivity_delay, rollback_buffer = excluded.rollback_buffer";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, s.worldName());
                ps.setInt(2, s.chunkX());
                ps.setInt(3, s.chunkZ());
                ps.setLong(4, s.sessionStart());
                ps.setLong(5, s.lastActivity());
                ps.setLong(6, s.inactivityDelay());
                ps.setLong(7, s.rollbackBuffer());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not save session to DB: " + e.getMessage());
            }
        });
    }

    public void remove(String worldName, int chunkX, int chunkZ) {
        enqueue(() -> {
            String sql = "DELETE FROM " + TABLE + " WHERE world = ? AND chunk_x = ? AND chunk_z = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, worldName);
                ps.setInt(2, chunkX);
                ps.setInt(3, chunkZ);
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().warning("Could not delete session from DB: " + e.getMessage());
            }
        });
    }

    public Map<String, ChunkSession> loadAll() {
        Map<String, ChunkSession> result = new HashMap<>();
        if (connection == null) return result;
        String sql = "SELECT world, chunk_x, chunk_z, session_start, last_activity, inactivity_delay, rollback_buffer FROM " + TABLE;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                ChunkSession s = new ChunkSession(
                        rs.getString("world"),
                        rs.getInt("chunk_x"),
                        rs.getInt("chunk_z"),
                        rs.getLong("session_start"),
                        rs.getLong("last_activity"),
                        rs.getLong("inactivity_delay"),
                        rs.getLong("rollback_buffer"));
                result.put(ChunkSession.key(s.worldName(), s.chunkX(), s.chunkZ()), s);
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Could not load sessions from DB: " + e.getMessage());
        }
        return result;
    }

    public void shutdown() {
        running = false;
        if (writer != null) {
            writer.interrupt();
            try {
                writer.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void enqueue(Runnable task) {
        if (!running) return;
        queue.offer(task);
    }

    private void drainLoop() {
        while (running) {
            try {
                Runnable task = queue.poll(1, TimeUnit.SECONDS);
                if (task != null) task.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                plugin.getLogger().warning("Session write failed: " + e.getMessage());
            }
        }
        Runnable task;
        while ((task = queue.poll()) != null) {
            try {
                task.run();
            } catch (Exception ignored) {
            }
        }
        try {
            if (connection != null) connection.close();
        } catch (SQLException ignored) {
        }
    }
}
