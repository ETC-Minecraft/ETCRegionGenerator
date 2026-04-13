package com.etcmc.regiongenerator;

import com.etcmc.regiongenerator.commands.ETCGenCommand;
import com.etcmc.regiongenerator.manager.GenerationManager;
import com.etcmc.regiongenerator.util.MessageUtil;
import org.bukkit.plugin.java.JavaPlugin;

public final class ETCRegionGenerator extends JavaPlugin {

    private static ETCRegionGenerator instance;
    private GenerationManager generationManager;
    private MessageUtil messageUtil;

    @Override
    public void onEnable() {
        instance = this;

        // Save default config
        saveDefaultConfig();

        // Init utilities
        messageUtil = new MessageUtil(this);
        generationManager = new GenerationManager(this);

        // Restore any tasks that were saved before last shutdown
        generationManager.loadSavedTasks();

        // Register command
        ETCGenCommand cmd = new ETCGenCommand(this);
        var cmdObj = getCommand("etcgen");
        if (cmdObj != null) {
            cmdObj.setExecutor(cmd);
            cmdObj.setTabCompleter(cmd);
        }

        getLogger().info("ETCRegionGenerator enabled — Folia edition");
        getLogger().info("Max concurrent chunks : " + getConfig().getInt("generation.max-concurrent-chunks", 8));
        getLogger().info("Chunks per tick        : " + getConfig().getInt("generation.chunks-per-tick", 4));
    }

    @Override
    public void onDisable() {
        if (generationManager != null) {
            generationManager.pauseAll();        // graceful pause + save progress
            generationManager.saveAllTasks();
        }
        getLogger().info("ETCRegionGenerator disabled — all tasks saved.");
    }

    // -------------------------------------------------------------------------

    public static ETCRegionGenerator getInstance() {
        return instance;
    }

    public GenerationManager getGenerationManager() {
        return generationManager;
    }

    public MessageUtil getMessageUtil() {
        return messageUtil;
    }
}
