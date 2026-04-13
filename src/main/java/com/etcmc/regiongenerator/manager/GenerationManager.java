package com.etcmc.regiongenerator.manager;

import com.etcmc.regiongenerator.ETCRegionGenerator;
import com.etcmc.regiongenerator.task.ChunkArea;
import com.etcmc.regiongenerator.task.GenerationTask;
import com.etcmc.regiongenerator.task.TaskState;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Manages the lifecycle of all {@link GenerationTask} instances.
 *
 * <p>One task per world at a time. Progress is persisted to
 * {@code plugins/ETCRegionGenerator/tasks.yml} so generation can
 * resume after a server restart.</p>
 */
public final class GenerationManager {

    private final ETCRegionGenerator plugin;

    /** Keyed by world name. One active task per world. */
    private final Map<String, GenerationTask> tasks = new ConcurrentHashMap<>();

    private final File saveFile;

    public GenerationManager(ETCRegionGenerator plugin) {
        this.plugin   = plugin;
        this.saveFile = new File(plugin.getDataFolder(), "tasks.yml");
    }

    // -------------------------------------------------------------------------
    //  Public API
    // -------------------------------------------------------------------------

    /**
     * Start pre-generation for a world.
     *
     * @param sender     who issued the command (receives feedback)
     * @param world      target world
     * @param centerX    block X of the centre
     * @param centerZ    block Z of the centre
     * @param radiusBlocks radius in blocks
     * @return {@code true} if started, {@code false} if a task already exists for that world
     */
    public boolean startTask(CommandSender sender, World world, int centerX, int centerZ,
                              int radiusBlocks) {
        if (tasks.containsKey(world.getName())) {
            return false;
        }

        ChunkArea area = new ChunkArea(centerX, centerZ, radiusBlocks);
        GenerationTask task = new GenerationTask(plugin, world, area, sender.getName());
        tasks.put(world.getName(), task);

        plugin.getLogger().info("[GenerationManager] Starting task for " + world.getName()
                + " — " + area.getTotalChunks() + " chunks");

        task.start();
        return true;
    }

    /** Pause an active task. */
    public boolean pauseTask(String worldName) {
        GenerationTask t = tasks.get(worldName);
        if (t == null || t.getState() != TaskState.RUNNING) return false;
        t.pause();
        saveAllTasks();
        return true;
    }

    /** Resume a paused task. */
    public boolean resumeTask(String worldName) {
        GenerationTask t = tasks.get(worldName);
        if (t == null || t.getState() != TaskState.PAUSED) return false;
        t.resume();
        return true;
    }

    /** Cancel and remove a task. */
    public boolean cancelTask(String worldName) {
        GenerationTask t = tasks.remove(worldName);
        if (t == null) return false;
        t.cancel();
        deleteSavedTask(worldName);
        return true;
    }

    /** Pause all running tasks (used on plugin disable). */
    public void pauseAll() {
        tasks.values().stream()
             .filter(t -> t.getState() == TaskState.RUNNING)
             .forEach(GenerationTask::pause);
    }

    /** Send status of all tasks (or a specific world) to sender. */
    public void sendStatus(CommandSender sender, String worldName) {
        if (tasks.isEmpty()) {
            sender.sendMessage(plugin.getMessageUtil().parse(
                plugin.getMessageUtil().getPrefix() + "<gray>No active generation tasks.</gray>"
            ));
            return;
        }

        if (worldName != null) {
            GenerationTask t = tasks.get(worldName);
            if (t == null) {
                sender.sendMessage(plugin.getMessageUtil().parse(
                    plugin.getMessageUtil().getPrefix() + "<red>No task for world: " + worldName
                ));
                return;
            }
            t.sendStatusTo(sender);
        } else {
            tasks.values().forEach(t -> t.sendStatusTo(sender));
        }
    }

    /** Called by GenerationTask when it finishes — cleans up the map. */
    public void removeTask(String worldName) {
        tasks.remove(worldName);
        deleteSavedTask(worldName);
    }

    public Collection<GenerationTask> getAllTasks() {
        return tasks.values();
    }

    public GenerationTask getTask(String worldName) {
        return tasks.get(worldName);
    }

    // -------------------------------------------------------------------------
    //  Persistence
    // -------------------------------------------------------------------------

    /** Persist all current tasks to disk. */
    public void saveAllTasks() {
        YamlConfiguration cfg = new YamlConfiguration();

        for (Map.Entry<String, GenerationTask> entry : tasks.entrySet()) {
            GenerationTask t = entry.getValue();
            String key = "tasks." + entry.getKey();

            cfg.set(key + ".worldName",      t.getWorld().getName());
            cfg.set(key + ".centerChunkX",   t.getArea().getCenterChunkX());
            cfg.set(key + ".centerChunkZ",   t.getArea().getCenterChunkZ());
            cfg.set(key + ".radiusChunks",   t.getArea().getRadiusInChunks());
            cfg.set(key + ".completed",      t.getCompleted());
            cfg.set(key + ".state",          t.getState().name());
            cfg.set(key + ".initiator",      t.getInitiatorName());
        }

        try {
            plugin.getDataFolder().mkdirs();
            cfg.save(saveFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save tasks.yml", e);
        }
    }

    /** Load saved tasks from disk and queue them as PAUSED, ready to resume. */
    public void loadSavedTasks() {
        if (!saveFile.exists()) return;

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(saveFile);
        var section = cfg.getConfigurationSection("tasks");
        if (section == null) return;

        int loaded = 0;
        for (String worldName : section.getKeys(false)) {
            String key = "tasks." + worldName;

            String storedWorldName = cfg.getString(key + ".worldName", worldName);
            int ccx    = cfg.getInt(key + ".centerChunkX");
            int ccz    = cfg.getInt(key + ".centerChunkZ");
            int radius = cfg.getInt(key + ".radiusChunks");
            int done   = cfg.getInt(key + ".completed", 0);
            String stateName = cfg.getString(key + ".state", "PAUSED");
            String initiator = cfg.getString(key + ".initiator", "Server");

            World world = plugin.getServer().getWorld(storedWorldName);
            if (world == null) {
                plugin.getLogger().warning("[GenerationManager] World '" + storedWorldName
                        + "' not found — skipping saved task.");
                continue;
            }

            // Only restore tasks that were running or paused
            if (!stateName.equals("RUNNING") && !stateName.equals("PAUSED")) continue;

            ChunkArea area = ChunkArea.fromChunks(ccx, ccz, radius);
            GenerationTask task = new GenerationTask(plugin, world, area, initiator);
            // Advance the resume offset so already-done chunks are skipped
            // The task starts in PAUSED state; user must /etcgen resume, or we auto-resume
            tasks.put(worldName, task);

            plugin.getLogger().info("[GenerationManager] Restored task for " + worldName
                    + " (" + done + "/" + area.getTotalChunks() + " done) — use /etcgen resume "
                    + worldName + " to continue.");
            loaded++;
        }

        if (loaded > 0) {
            plugin.getLogger().info("[GenerationManager] " + loaded + " task(s) restored from disk.");
        }
    }

    private void deleteSavedTask(String worldName) {
        if (!saveFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(saveFile);
        cfg.set("tasks." + worldName, null);
        try {
            cfg.save(saveFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not update tasks.yml", e);
        }
    }
}
