package com.etcmc.regiongenerator.task;

import com.etcmc.regiongenerator.ETCRegionGenerator;
import com.etcmc.regiongenerator.util.MessageUtil;
import com.etcmc.regiongenerator.util.ProgressBar;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * Represents one active (or paused) region pre-generation job.
 *
 * <p><b>Threading model:</b>
 * <ul>
 *   <li>Feed loop runs on a Folia AsyncScheduler thread.</li>
 *   <li>Each chunk is requested via {@link World#getChunkAtAsync} — Paper's internal
 *       async chunk pipeline distributes work across all available worker threads,
 *       allowing chunks across ALL regions to generate simultaneously (unlike
 *       RegionScheduler which serialises work per-region-thread).</li>
 *   <li>On CompletableFuture completion the chunk is unloaded and saved to disk.</li>
 *   <li>A {@link Semaphore} caps in-flight requests to avoid overwhelming the server.</li>
 * </ul>
 */
public final class GenerationTask {

    private final UUID id = UUID.randomUUID();
    private final ETCRegionGenerator plugin;
    private final World world;
    private final ChunkArea area;
    private final String initiatorName;

    // --- State ---
    private final AtomicReference<TaskState> state = new AtomicReference<>(TaskState.QUEUED);
    private final AtomicInteger completed = new AtomicInteger(0);
    private final AtomicInteger failed    = new AtomicInteger(0);

    // Resume offset — chunks before this index are already done
    private volatile int startIndex = 0;

    // Folia scheduler handle for the feed loop
    private volatile ScheduledTask feedTask;

    // Caps how many getChunkAtAsync calls are in-flight simultaneously
    private final int maxConcurrent;
    private final Semaphore semaphore;

    private Instant startedAt;
    private Instant finishedAt;

    public GenerationTask(ETCRegionGenerator plugin, World world, ChunkArea area,
                          String initiatorName) {
        this.plugin        = plugin;
        this.world         = world;
        this.area          = area;
        this.initiatorName = initiatorName;

        this.maxConcurrent = plugin.getConfig().getInt("generation.max-concurrent-chunks", 24);
        this.semaphore     = new Semaphore(maxConcurrent);
    }

    // -------------------------------------------------------------------------
    //  Lifecycle
    // -------------------------------------------------------------------------

    /** Start (or resume) generation on the Folia async scheduler. */
    public void start() {
        if (!state.compareAndSet(TaskState.QUEUED, TaskState.RUNNING) &&
            !state.compareAndSet(TaskState.PAUSED, TaskState.RUNNING)) {
            return;
        }

        startedAt = (startedAt == null) ? Instant.now() : startedAt;

        boolean skipExisting = plugin.getConfig().getBoolean("generation.skip-existing", true);
        int tickDelayMs      = plugin.getConfig().getInt("generation.tick-delay-ms", 0);

        List<ChunkArea.ChunkCoord> chunks = area.getChunks();
        int total = chunks.size();

        feedTask = plugin.getServer().getAsyncScheduler().runNow(plugin, asyncTask -> {
            int index = startIndex;

            while (index < total && state.get() == TaskState.RUNNING) {
                ChunkArea.ChunkCoord coord = chunks.get(index);
                index++;

                // Fast path: skip chunks already saved to disk
                if (skipExisting && world.isChunkGenerated(coord.x(), coord.z())) {
                    completed.incrementAndGet();
                    continue;
                }

                // Block until a concurrency slot is free
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    state.set(TaskState.CANCELLED);
                    return;
                }

                if (state.get() != TaskState.RUNNING) {
                    semaphore.release();
                    break;
                }

                // -----------------------------------------------------------
                // getChunkAtAsync feeds Paper's internal chunk-generation pool
                // directly. Multiple chunks across ALL regions are generated
                // concurrently — unlike RegionScheduler which serialises work
                // to one thread per region.
                // -----------------------------------------------------------
                world.getChunkAtAsync(coord.x(), coord.z())
                     .thenAccept(chunk -> {
                         try {
                             if (chunk == null) { failed.incrementAndGet(); return; }
                             chunk.unload(true); // save to .mca, evict from RAM
                             int done = completed.incrementAndGet();
                             broadcastProgressIfDue(done, total);
                         } catch (Exception ex) {
                             failed.incrementAndGet();
                             plugin.getLogger().log(Level.WARNING,
                                 "[ETCRegionGenerator] Failed chunk ("
                                 + coord.x() + "," + coord.z() + "): " + ex.getMessage());
                         } finally {
                             semaphore.release();
                         }
                     })
                     .exceptionally(ex -> {
                         failed.incrementAndGet();
                         semaphore.release();
                         plugin.getLogger().log(Level.WARNING,
                             "[ETCRegionGenerator] Async exception chunk ("
                             + coord.x() + "," + coord.z() + "): " + ex.getMessage());
                         return null;
                     });

                // Optional fine-grained throttle (0 = off)
                if (tickDelayMs > 0) {
                    try { Thread.sleep(tickDelayMs); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            }

            // Drain all in-flight completions before declaring done
            try {
                semaphore.acquire(maxConcurrent);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            if (state.get() == TaskState.RUNNING) {
                finishedAt = Instant.now();
                state.set(TaskState.DONE);
                onFinished();
            }
        });
    }

    /** Flag to pause; feed loop checks state every batch. */
    public void pause() {
        if (state.compareAndSet(TaskState.RUNNING, TaskState.PAUSED)) {
            startIndex = completed.get();   // approximate resume point
            plugin.getLogger().info("[ETCRegionGenerator] Task paused — " + getSummaryLine());
        }
    }

    /** Resume from where we left off. */
    public void resume() {
        start(); // state guard inside start() handles PAUSED → RUNNING
    }

    /** Hard stop — marks cancelled and interrupts feed loop. */
    public void cancel() {
        state.set(TaskState.CANCELLED);
        if (feedTask != null) feedTask.cancel();
    }

    // -------------------------------------------------------------------------
    //  Progress & finish
    // -------------------------------------------------------------------------

    private volatile long lastBroadcastEpoch = 0;

    private void broadcastProgressIfDue(int done, int total) {
        int intervalSec = plugin.getConfig().getInt("messages.progress-broadcast-interval", 60);
        if (intervalSec <= 0) return;

        long now = Instant.now().getEpochSecond();
        if (now - lastBroadcastEpoch >= intervalSec) {
            lastBroadcastEpoch = now;
            String line = buildProgressLine(done, total);
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                plugin.getServer().broadcast(
                    plugin.getMessageUtil().parse(
                        plugin.getMessageUtil().getPrefix() + line
                    )
                )
            );
        }
    }

    private void onFinished() {
        int total = area.getTotalChunks();
        plugin.getLogger()
              .info("[ETCRegionGenerator] DONE " + world.getName()
                    + " — " + completed.get() + "/" + total
                    + " chunks  |  " + failed.get() + " failed"
                    + "  |  elapsed: " + getElapsedSeconds() + "s");

        String line = "<green>✔ Finished pre-generating <white>" + world.getName()
                + "<green>! " + completed.get() + "/" + total + " chunks"
                + (failed.get() > 0 ? " <red>(" + failed.get() + " failed)" : "");

        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
            plugin.getServer().broadcast(plugin.getMessageUtil().parse(
                plugin.getMessageUtil().getPrefix() + line
            ))
        );

        // Remove from manager
        plugin.getGenerationManager().removeTask(world.getName());
    }

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    private String buildProgressLine(int done, int total) {
        double pct = total == 0 ? 100.0 : done * 100.0 / total;
        return "<yellow>" + world.getName()
                + " <white>" + ProgressBar.build(pct, 20)
                + " <aqua>" + String.format("%.1f%%", pct)
                + " <gray>(" + done + "/" + total + ")"
                + "  ETA: <white>" + estimateETA(done, total);
    }

    private String estimateETA(int done, int total) {
        if (done == 0 || startedAt == null) return "?";
        long elapsed = Instant.now().getEpochSecond() - startedAt.getEpochSecond();
        long remaining = (long) (elapsed * (total - done) / (double) done);
        if (remaining < 60)  return remaining + "s";
        if (remaining < 3600) return (remaining / 60) + "m " + (remaining % 60) + "s";
        return (remaining / 3600) + "h " + ((remaining % 3600) / 60) + "m";
    }

    public String getSummaryLine() {
        int done  = completed.get();
        int total = area.getTotalChunks();
        double pct = total == 0 ? 100.0 : done * 100.0 / total;
        return String.format("[%s] %s %.1f%% (%d/%d) state=%s",
                id.toString().substring(0, 8), world.getName(), pct, done, total, state.get());
    }

    public void sendStatusTo(CommandSender sender) {
        int done  = completed.get();
        int total = area.getTotalChunks();
        MessageUtil msg = plugin.getMessageUtil();
        String prefix = msg.getPrefix();
        sender.sendMessage(msg.parse(prefix + "<yellow>World   : <white>" + world.getName()));
        sender.sendMessage(msg.parse(prefix + "<yellow>State   : <white>" + state.get()));
        sender.sendMessage(msg.parse(prefix + "<yellow>Progress: <white>" + buildProgressLine(done, total)));
        sender.sendMessage(msg.parse(prefix + "<yellow>Failed  : <white>" + failed.get()));
        sender.sendMessage(msg.parse(prefix + "<yellow>Elapsed : <white>" + getElapsedSeconds() + "s"));
    }

    private long getElapsedSeconds() {
        if (startedAt == null) return 0;
        Instant end = (finishedAt != null) ? finishedAt : Instant.now();
        return end.getEpochSecond() - startedAt.getEpochSecond();
    }

    // -------------------------------------------------------------------------
    //  Getters
    // -------------------------------------------------------------------------

    public UUID getId()               { return id; }
    public World getWorld()           { return world; }
    public ChunkArea getArea()        { return area; }
    public TaskState getState()       { return state.get(); }
    public int getCompleted()         { return completed.get(); }
    public int getFailed()            { return failed.get(); }
    public int getStartIndex()        { return startIndex; }
    public String getInitiatorName()  { return initiatorName; }
}
