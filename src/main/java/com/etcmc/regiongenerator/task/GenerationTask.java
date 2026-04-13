package com.etcmc.regiongenerator.task;

import com.etcmc.regiongenerator.ETCRegionGenerator;
import com.etcmc.regiongenerator.util.MessageUtil;
import com.etcmc.regiongenerator.util.ProgressBar;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Chunk;
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
 * <p><b>Threading model (Folia):</b>
 * <ul>
 *   <li>The feed loop runs on an async thread (AsyncScheduler).</li>
 *   <li>Each chunk is loaded/generated on its own region thread (RegionScheduler).</li>
 *   <li>After loading, the chunk is force-unloaded (saved to disk) on the same region thread.</li>
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

    // Semaphore to cap concurrent region-thread requests
    private final Semaphore semaphore;

    private Instant startedAt;
    private Instant finishedAt;

    public GenerationTask(ETCRegionGenerator plugin, World world, ChunkArea area,
                          String initiatorName) {
        this.plugin        = plugin;
        this.world         = world;
        this.area          = area;
        this.initiatorName = initiatorName;

        int maxConcurrent = plugin.getConfig().getInt("generation.max-concurrent-chunks", 8);
        this.semaphore = new Semaphore(maxConcurrent);
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

        int chunksPerTick = plugin.getConfig().getInt("generation.chunks-per-tick", 4);
        boolean skipExisting = plugin.getConfig().getBoolean("generation.skip-existing", true);

        List<ChunkArea.ChunkCoord> chunks = area.getChunks();
        int total = chunks.size();

        feedTask = plugin.getServer().getAsyncScheduler().runNow(plugin, asyncTask -> {
            int index = startIndex;

            while (index < total && state.get() == TaskState.RUNNING) {

                // Rate-limit: advance `chunksPerTick` slots, then sleep one tick (~50 ms)
                int batchEnd = Math.min(index + chunksPerTick, total);
                int scheduled = 0;

                while (index < batchEnd) {
                    ChunkArea.ChunkCoord coord = chunks.get(index);
                    index++;

                    // Skip already-existing chunks (fast path: check file on async thread)
                    if (skipExisting && world.isChunkGenerated(coord.x(), coord.z())) {
                        completed.incrementAndGet();
                        continue;
                    }

                    try {
                        semaphore.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        state.set(TaskState.CANCELLED);
                        return;
                    }

                    scheduled++;
                    final int finalIndex = index;

                    // Schedule generation on the correct region thread
                    plugin.getServer().getRegionScheduler().execute(
                        plugin, world, coord.x(), coord.z(),
                        () -> generateChunk(coord, finalIndex, total)
                    );
                }

                // If we scheduled any chunks this batch, sleep ~1 tick before next batch
                if (scheduled > 0) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            // Feed loop ended — wait for all in-flight chunks to finish
            try {
                semaphore.acquire(plugin.getConfig().getInt("generation.max-concurrent-chunks", 8));
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
    //  Chunk generation (runs on region thread)
    // -------------------------------------------------------------------------

    private void generateChunk(ChunkArea.ChunkCoord coord, int idx, int total) {
        try {
            if (state.get() == TaskState.CANCELLED) {
                semaphore.release();
                return;
            }

            // Load / generate the chunk synchronously on the region thread
            Chunk chunk = world.getChunkAt(coord.x(), coord.z());

            // Force-save and unload to free memory
            // (true = save before unload)
            chunk.unload(true);

            int done = completed.incrementAndGet();

            // Broadcast progress at configured interval
            broadcastProgressIfDue(done, total);

        } catch (Exception ex) {
            failed.incrementAndGet();
            plugin.getLogger().log(Level.WARNING,
                "[ETCRegionGenerator] Failed chunk (" + coord.x() + "," + coord.z() + "): "
                + ex.getMessage());
        } finally {
            semaphore.release();
        }
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
