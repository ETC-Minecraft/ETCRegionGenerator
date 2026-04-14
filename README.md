# ETCRegionGenerator

**Folia-native region pre-generator** for Minecraft 1.21+.  
Generates and writes `.mca` region files directly to disk so chunks are ready before players ever visit them — no lag spikes, no on-demand generation.

> 📖 **Full documentation:** [https://etc-minecraft.github.io/ETC-Minecraft-Docs/](https://etc-minecraft.github.io/ETC-Minecraft-Docs/)

---

## Features

- **Folia-native** — built on the Folia API; all chunk work runs on the correct region thread.
- **Concurrent generation** — configurable in-flight chunk limit to saturate your CPU without overwhelming the server.
- **Pause / Resume** — stop and continue generation at any time without losing progress.
- **Auto-save progress** — task state is persisted to `tasks.yml` so generation survives server restarts.
- **Skip existing chunks** — skips already-generated chunks by default (optional).
- **Live status & progress bar** — broadcast progress to the console and online operators on a configurable interval.

---

## Requirements

| Requirement | Version |
|---|---|
| Java | 21+ |
| Server software | [Folia](https://github.com/PaperMC/Folia) |
| Minecraft | 1.21.x |

> **Paper / Spigot are not supported.** This plugin targets the Folia API exclusively.

---

## Installation

1. Download the latest `ETCRegionGenerator-x.x.x.jar` from the [Releases](https://github.com/ETC-Minecraft/ETCRegionGenerator/releases) page.
2. Place the jar in your server's `plugins/` folder.
3. Restart the server.
4. Edit `plugins/ETCRegionGenerator/config.yml` to your liking (see [Configuration](#configuration)).

---

## Commands

All commands require the `etcgen.use` permission (default: `op`).

| Command | Description |
|---|---|
| `/etcgen start <world> <centerX> <centerZ> <radiusBlocks>` | Start pre-generating a circular area around the given block coordinates. |
| `/etcgen pause <world>` | Pause a running generation task. |
| `/etcgen resume <world>` | Resume a paused task. |
| `/etcgen status [world]` | Show current progress for one or all worlds. |
| `/etcgen cancel <world>` | Cancel and remove a task (alias: `stop`). |
| `/etcgen reload` | Reload `config.yml` without restarting. |

**Aliases:** `/regiongen`, `/rgen`

### Example

```
/etcgen start world 0 0 5000
```

Generates all chunks within a 5 000-block radius of 0, 0 in the `world` world.

---

## Permissions

| Permission | Description | Default |
|---|---|---|
| `etcgen.use` | Access to all ETCRegionGenerator commands | `op` |
| `etcgen.admin` | Admin override — cancel others' tasks, reload config | `op` |

---

## Configuration

```yaml
# plugins/ETCRegionGenerator/config.yml

generation:
  # Maximum number of chunk generation requests in flight at once.
  # Tuning guide:
  #   low-end (2-4 cores):  16 – 32
  #   mid-range (6-8 cores): 32 – 64   ← default
  #   high-end (12+ cores): 64 – 128
  max-concurrent-chunks: 64

  # Optional delay (ms) between each chunk submission. 0 = disabled (recommended).
  tick-delay-ms: 0

  # Skip chunks that already exist in a region file.
  skip-existing: true

  # Persist progress to disk so generation resumes after a restart.
  auto-save-progress: true

  # How often (seconds) progress is written to disk.
  auto-save-interval: 30

messages:
  prefix: "<dark_gray>[<gradient:#00e0ff:#00ff99>ETCRGen</gradient>]</dark_gray> "
  # Seconds between automatic progress broadcasts (0 = disabled).
  progress-broadcast-interval: 60
```

---

## Building from Source

```bash
git clone https://github.com/ETC-Minecraft/ETCRegionGenerator.git
cd ETCRegionGenerator
mvn package
```

The compiled jar will be at `target/ETCRegionGenerator-<version>.jar`.

**Requirements:** JDK 21, Maven 3.8+.

---

## Contributing

Pull requests are welcome! Please open an issue first to discuss larger changes.

---

## License

See [LICENSE](LICENSE) for details.

---

*Made with ❤️ by [ETCMC](https://github.com/ETC-Minecraft)*
