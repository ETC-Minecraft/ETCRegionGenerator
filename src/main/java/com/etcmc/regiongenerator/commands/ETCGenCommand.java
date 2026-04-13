package com.etcmc.regiongenerator.commands;

import com.etcmc.regiongenerator.ETCRegionGenerator;
import com.etcmc.regiongenerator.manager.GenerationManager;
import com.etcmc.regiongenerator.task.GenerationTask;
import com.etcmc.regiongenerator.task.TaskState;
import com.etcmc.regiongenerator.util.MessageUtil;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles the {@code /etcgen} command and all sub-commands.
 *
 * <pre>
 * /etcgen start  &lt;world&gt; &lt;centerX&gt; &lt;centerZ&gt; &lt;radiusBlocks&gt;
 * /etcgen stop   &lt;world&gt;                  (alias for cancel)
 * /etcgen pause  &lt;world&gt;
 * /etcgen resume &lt;world&gt;
 * /etcgen status [world]
 * /etcgen cancel &lt;world&gt;
 * /etcgen reload
 * </pre>
 */
public final class ETCGenCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUB_COMMANDS = List.of(
        "start", "stop", "pause", "resume", "status", "cancel", "reload"
    );

    private final ETCRegionGenerator plugin;
    private final MessageUtil msg;

    public ETCGenCommand(ETCRegionGenerator plugin) {
        this.plugin = plugin;
        this.msg    = plugin.getMessageUtil();
    }

    // -------------------------------------------------------------------------
    //  Executor
    // -------------------------------------------------------------------------

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("etcgen.use")) {
            sender.sendMessage(msg.parse(msg.getPrefix() + "<red>You don't have permission."));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase();
        GenerationManager mgr = plugin.getGenerationManager();

        switch (sub) {
            case "start"  -> handleStart(sender, args, mgr);
            case "stop",
                 "cancel" -> handleCancel(sender, args, mgr);
            case "pause"  -> handlePause(sender, args, mgr);
            case "resume" -> handleResume(sender, args, mgr);
            case "status" -> handleStatus(sender, args, mgr);
            case "reload" -> handleReload(sender);
            default       -> sendHelp(sender, label);
        }
        return true;
    }

    // -------------------------------------------------------------------------
    //  Sub-command handlers
    // -------------------------------------------------------------------------

    private void handleStart(CommandSender sender, String[] args, GenerationManager mgr) {
        // /etcgen start <world> <x> <z> <radius>
        if (args.length < 5) {
            sender.sendMessage(msg.parse(msg.getPrefix()
                + "<red>Usage: <white>/etcgen start <world> <centerX> <centerZ> <radiusBlocks>"));
            return;
        }

        World world = plugin.getServer().getWorld(args[1]);
        if (world == null) {
            sender.sendMessage(msg.parse(msg.getPrefix() + "<red>World not found: <white>" + msg.escape(args[1])));
            return;
        }

        int x, z, radius;
        try {
            x      = Integer.parseInt(args[2]);
            z      = Integer.parseInt(args[3]);
            radius = Integer.parseInt(args[4]);
        } catch (NumberFormatException e) {
            sender.sendMessage(msg.parse(msg.getPrefix() + "<red>centerX, centerZ and radius must be integers."));
            return;
        }

        if (radius <= 0) {
            sender.sendMessage(msg.parse(msg.getPrefix() + "<red>Radius must be > 0."));
            return;
        }

        boolean started = mgr.startTask(sender, world, x, z, radius);
        if (!started) {
            sender.sendMessage(msg.parse(msg.getPrefix()
                + "<red>A task is already running for <white>" + world.getName()
                + "<red>. Use <white>/etcgen status " + world.getName() + "<red> to check it."));
            return;
        }

        // Quick chunk-count feedback
        GenerationTask task = mgr.getTask(world.getName());
        int total = (task != null) ? task.getArea().getTotalChunks() : -1;

        sender.sendMessage(msg.parse(msg.getPrefix()
            + "<green>Started pre-generation for <white>" + world.getName()
            + " <green>| Total chunks: <white>" + total
            + " <green>| Radius: <white>" + radius + " blocks"));
    }

    private void handleCancel(CommandSender sender, String[] args, GenerationManager mgr) {
        String worldName = resolveWorldArg(sender, args);
        if (worldName == null) return;

        boolean ok = mgr.cancelTask(worldName);
        if (ok) {
            sender.sendMessage(msg.parse(msg.getPrefix()
                + "<yellow>Cancelled task for <white>" + worldName));
        } else {
            sender.sendMessage(msg.parse(msg.getPrefix()
                + "<red>No active task for <white>" + worldName));
        }
    }

    private void handlePause(CommandSender sender, String[] args, GenerationManager mgr) {
        String worldName = resolveWorldArg(sender, args);
        if (worldName == null) return;

        boolean ok = mgr.pauseTask(worldName);
        if (ok) {
            sender.sendMessage(msg.parse(msg.getPrefix()
                + "<yellow>Paused task for <white>" + worldName
                + " <gray>— progress saved."));
        } else {
            sender.sendMessage(msg.parse(msg.getPrefix()
                + "<red>No running task for <white>" + worldName));
        }
    }

    private void handleResume(CommandSender sender, String[] args, GenerationManager mgr) {
        String worldName = resolveWorldArg(sender, args);
        if (worldName == null) return;

        boolean ok = mgr.resumeTask(worldName);
        if (ok) {
            sender.sendMessage(msg.parse(msg.getPrefix()
                + "<green>Resumed task for <white>" + worldName));
        } else {
            sender.sendMessage(msg.parse(msg.getPrefix()
                + "<red>No paused task for <white>" + worldName
                + " <gray>(already running or not found)"));
        }
    }

    private void handleStatus(CommandSender sender, String[] args, GenerationManager mgr) {
        String worldName = (args.length >= 2) ? args[1] : null;
        mgr.sendStatus(sender, worldName);
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("etcgen.admin")) {
            sender.sendMessage(msg.parse(msg.getPrefix() + "<red>No permission."));
            return;
        }
        plugin.reloadConfig();
        sender.sendMessage(msg.parse(msg.getPrefix() + "<green>Config reloaded."));
    }

    // -------------------------------------------------------------------------
    //  Tab completer
    // -------------------------------------------------------------------------

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String alias,
                                                @NotNull String[] args) {
        if (!sender.hasPermission("etcgen.use")) return List.of();

        if (args.length == 1) {
            return filter(SUB_COMMANDS, args[0]);
        }

        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            // World argument
            if (sub.equals("start") || sub.equals("pause") || sub.equals("resume")
                    || sub.equals("stop") || sub.equals("cancel") || sub.equals("status")) {
                List<String> worlds = plugin.getServer().getWorlds().stream()
                    .map(World::getName)
                    .collect(Collectors.toList());
                return filter(worlds, args[1]);
            }
        }

        if (sub.equals("start") && args.length == 3) {
            // centerX: suggest player's current X if player
            if (sender instanceof Player p) {
                return List.of(String.valueOf((int) p.getLocation().getX()));
            }
            return List.of("0");
        }

        if (sub.equals("start") && args.length == 4) {
            if (sender instanceof Player p) {
                return List.of(String.valueOf((int) p.getLocation().getZ()));
            }
            return List.of("0");
        }

        if (sub.equals("start") && args.length == 5) {
            return List.of("1000", "5000", "10000", "25000");
        }

        return List.of();
    }

    // -------------------------------------------------------------------------
    //  Helpers
    // -------------------------------------------------------------------------

    private @Nullable String resolveWorldArg(CommandSender sender, String[] args) {
        if (args.length < 2) {
            // If player, default to current world
            if (sender instanceof Player p) return p.getWorld().getName();
            sender.sendMessage(msg.parse(msg.getPrefix() + "<red>Please specify a world name."));
            return null;
        }
        return args[1];
    }

    private List<String> filter(List<String> options, String partial) {
        String low = partial.toLowerCase();
        return options.stream()
            .filter(s -> s.toLowerCase().startsWith(low))
            .collect(Collectors.toList());
    }

    private void sendHelp(CommandSender sender, String label) {
        String p = msg.getPrefix();
        sender.sendMessage(msg.parse(p + "<yellow>--- ETCRegionGenerator Help ---"));
        sender.sendMessage(msg.parse(p + "<white>/<label> start <world> <x> <z> <radius>".replace("<label>", label)));
        sender.sendMessage(msg.parse(p + "<gray>  Start pre-generating chunks (circle, radius in blocks)"));
        sender.sendMessage(msg.parse(p + "<white>/<label> pause <world>".replace("<label>", label)));
        sender.sendMessage(msg.parse(p + "<gray>  Pause and save progress"));
        sender.sendMessage(msg.parse(p + "<white>/<label> resume <world>".replace("<label>", label)));
        sender.sendMessage(msg.parse(p + "<gray>  Resume a paused task"));
        sender.sendMessage(msg.parse(p + "<white>/<label> status [world]".replace("<label>", label)));
        sender.sendMessage(msg.parse(p + "<gray>  Show progress of all or one task"));
        sender.sendMessage(msg.parse(p + "<white>/<label> cancel <world>".replace("<label>", label)));
        sender.sendMessage(msg.parse(p + "<gray>  Cancel and discard a task"));
        sender.sendMessage(msg.parse(p + "<white>/<label> reload".replace("<label>", label)));
        sender.sendMessage(msg.parse(p + "<gray>  Reload config.yml"));
    }

    // Store label for help message
    private String lastLabel = "etcgen";
}
