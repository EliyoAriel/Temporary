# Temporary

A Minecraft Paper plugin that marks areas as **temporary**: players can break/place blocks freely, and once the area sits idle for a configurable delay, the plugin automatically rolls the chunk back to its pre-session state via [CoreProtect](https://www.coreprotect.net/).

## Requirements

- **Paper** 1.21+
- **Java** 21+
- **CoreProtect** (soft-depend — rollback is skipped, not fatal, when missing)

## Features

- **Three config modes** — whole worlds, rectangular chunk ranges, or individual chunks
- **Per-area timing** — each area has its own inactivity delay and rollback buffer
- **Absolute rollback window** — CoreProtect restores based on the full session duration (`now - sessionStart + buffer`), so long sessions never leave early blocks behind
- **Chunk-isolated** — each 16x16 chunk is rolled back independently; activity in one chunk never restarts another
- **Per-chunk sessions** — every chunk tracks its own `sessionStart` / `lastActivity`
- **Retry on failure** — failed rollbacks are retried every second (warns on the 1st and every 10th attempt)
- **Crash-safe** — pending sessions persist to `sessions.yml` and reload on startup
- **Manual rollback** — `/temporary rollback <world> <chunkX,chunkZ>` forces a rollback now

## Installation

1. Build or download `Temporary-1.0.0.jar`
2. Place in your server's `plugins/` folder
3. Restart the server
4. Edit `plugins/Temporary/config.yml` as needed and run `/temporary reload`

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/temporary list` | List tracked chunks and their inactivity delays | `temporary.list` |
| `/temporary rollback <world> <chunkX,chunkZ>` | Force a rollback of one tracked chunk (no delay wait) | `temporary.rollback` |
| `/temporary reload` | Reload config | `temporary.reload` |

Chunk coordinates accept either `"X,Z"` or `"X Z"`.

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `temporary.list` | true | List tracked chunks |
| `temporary.reload` | op | Reload config |
| `temporary.rollback` | op | Force a chunk rollback |

## Configuration

```yaml
# Seconds of no activity before a tracked chunk is rolled back.
inactivity-delay: 300
# Extra seconds added to the CoreProtect rollback window so no block is missed.
rollback-time-buffer: 5

# Mode 1: entire worlds are temporary.
world-modes:
  - world_minigame

# Mode 2: box areas defined by min/max chunk coordinates. Optional per-entry
# time overrides fall back to the global values when omitted.
chunk-ranges:
  - world: arena_world
    min-chunk-x: -5
    max-chunk-x: 5
    min-chunk-z: -5
    max-chunk-z: 5
    # inactivity-delay: 120
    # rollback-time-buffer: 3

# Mode 3: individual chunks of interest, format "X,Z".
specific-chunks:
  - world: spawn_world
    chunks:
      - "10,20"
      - "-3,7"
```

| Setting | Default | Description |
|---------|---------|-------------|
| `inactivity-delay` | 300 | Seconds of no block activity before auto-rollback |
| `rollback-time-buffer` | 5 | Extra seconds added to the CoreProtect time window |
| `world-modes` | — | Worlds where every chunk is temporary |
| `chunk-ranges` | — | Rectangular areas (min/max chunk coords) |
| `specific-chunks` | — | Individual chunk list per world |

Only block **break** and **place** events count as activity.

## How It Works

### 1. Tracking

On every block break/place, the plugin checks whether that chunk belongs to a temporary area. If it does, a `ChunkSession` is created (or refreshed) with:
- `sessionStart` — set once on first activity, never reset
- `lastActivity` — reset on every new block change

### 2. Rollback Trigger

A 1-second scheduler walks the active sessions. When `now - lastActivity >= inactivity-delay`, the session is rolled back:
- duration = `(now - sessionStart) + rollback-time-buffer`
- center = `chunkX * 16 + 8, chunkZ * 16 + 8`
- radius = 8 (CoreProtect's `performRollback` box is inclusive at both ends, so an exact 16-block chunk is impossible — the box spills 1 block into the +X/+Z neighbor chunk)

The rollback runs **async** via `CoreProtectAPI.performRollback`, which CoreProtect refuses to run on the main thread.

### 3. Completion

- Success: session removed from tracking and saved.
- Failure: kept and retried every second, logging on the 1st and every 10th attempt.

### 4. Persistence

Sessions are written to `sessions.yml` on disable and loaded on enable, so a server crash mid-session still rolls the chunk back later.

## Building

```bash
mvn clean package
```

Output: `target/Temporary-1.0.0.jar`. CoreProtect is resolved from the local Maven repo as `net.coreprotect:coreprotect:24.0` (provided scope).

## Notes

- Uses the **CoreProtect API** (`CoreProtect.getInstance().getAPI()`, soft-depend), not console commands.
- Requires CoreProtect Community Edition 24.0+ behavior: that fork **mutates** the list arguments passed to `performRollback`, so this plugin always passes mutable `ArrayList`s. Passing immutable `List.of()` throws `UnsupportedOperationException` inside CoreProtect.

## License

[GPL-3.0](LICENSE)
