package de.backup;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupPlugin extends JavaPlugin implements TabExecutor {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<World, Boolean> savedAutoSave = new HashMap<>();
    private BukkitTask task;

    private record Result(long skipped, Exception error) {}

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getCommand("backup").setExecutor(this);
        schedule();
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        restoreAutoSave();
    }

    private void schedule() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        long minutes = getConfig().getLong("interval-minutes", 60);
        if (minutes <= 0) {
            return;
        }
        long ticks = minutes * 60L * 20L;
        task = getServer().getScheduler().runTaskTimer(this,
                () -> startBackup(getServer().getConsoleSender()), ticks, ticks);
    }

    // ---------- Befehle ----------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length == 0 ? "now" : args[0].toLowerCase();
        switch (sub) {
            case "now" -> startBackup(sender);
            case "list" -> listBackups(sender);
            case "reload" -> {
                reloadConfig();
                schedule();
                sender.sendMessage(Component.text("Konfiguration neu geladen.", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Benutzung: /backup <now|list|reload>", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("now", "list", "reload");
        }
        return List.of();
    }

    // ---------- Backup ----------

    private void startBackup(CommandSender who) {
        if (!running.compareAndSet(false, true)) {
            who.sendMessage(Component.text("Es läuft bereits ein Backup.", NamedTextColor.RED));
            return;
        }

        List<World> worlds = selectWorlds();
        if (worlds.isEmpty()) {
            running.set(false);
            who.sendMessage(Component.text("Keine Welten zum Sichern gefunden.", NamedTextColor.RED));
            return;
        }

        announce("Backup startet ...", NamedTextColor.YELLOW);

        // Auf dem Hauptthread: speichern und Auto-Save pausieren, damit die Dateien stabil sind
        Map<String, Path> folders = new LinkedHashMap<>();
        for (World w : worlds) {
            w.save();
            savedAutoSave.put(w, w.isAutoSave());
            w.setAutoSave(false);
            folders.put(w.getName(), w.getWorldFolder().toPath());
        }

        Path dir = backupDir().toPath();
        Path target = dir.resolve("backup-" + FORMAT.format(LocalDateTime.now(ZoneId.systemDefault())) + ".zip");
        int level = Math.max(0, Math.min(9, getConfig().getInt("compression-level", 5)));

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            Result result;
            try {
                Files.createDirectories(dir);
                long skipped = zip(folders, target, level);
                result = new Result(skipped, null);
            } catch (Exception e) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException ignored) {
                    // egal
                }
                result = new Result(0, e);
            }
            final Result finalResult = result;
            if (!isEnabled()) {
                return;
            }
            getServer().getScheduler().runTask(this, () -> finish(who, target, finalResult));
        });
    }

    private long zip(Map<String, Path> folders, Path target, int level) throws IOException {
        long[] skipped = {0};
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target)))) {
            zos.setLevel(level);
            for (Map.Entry<String, Path> e : folders.entrySet()) {
                Path root = e.getValue();
                String prefix = e.getKey() + "/";
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.getFileName().toString().equals("session.lock")) {
                            return FileVisitResult.CONTINUE;
                        }
                        String entry = prefix + root.relativize(file).toString().replace('\\', '/');
                        try {
                            zos.putNextEntry(new ZipEntry(entry));
                            Files.copy(file, zos);
                            zos.closeEntry();
                        } catch (IOException ex) {
                            skipped[0]++;
                            try {
                                zos.closeEntry();
                            } catch (IOException ignored) {
                                // egal
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        skipped[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                });
            }
        }
        return skipped[0];
    }

    private void finish(CommandSender who, Path target, Result result) {
        restoreAutoSave();
        running.set(false);

        if (result.error() != null) {
            getLogger().severe("Backup fehlgeschlagen: " + result.error());
            announce("Backup fehlgeschlagen! Details in der Konsole.", NamedTextColor.RED);
            if (!(who instanceof org.bukkit.command.ConsoleCommandSender)) {
                who.sendMessage(Component.text("Fehler: " + result.error().getMessage(), NamedTextColor.RED));
            }
            return;
        }

        int deleted = cleanup();
        String size;
        try {
            size = humanSize(Files.size(target));
        } catch (IOException e) {
            size = "?";
        }
        String msg = "Backup fertig: " + target.getFileName() + " (" + size + ")";
        if (result.skipped() > 0) {
            msg += ", " + result.skipped() + " Datei(en) übersprungen";
        }
        if (deleted > 0) {
            msg += ", " + deleted + " altes Backup gelöscht";
        }
        getLogger().info(msg);
        announce(msg, NamedTextColor.GREEN);
    }

    private int cleanup() {
        int max = getConfig().getInt("max-backups", 10);
        if (max <= 0) {
            return 0;
        }
        File[] files = backupFiles();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        int deleted = 0;
        for (int i = 0; i < files.length - max; i++) {
            if (files[i].delete()) {
                deleted++;
            }
        }
        return deleted;
    }

    private void listBackups(CommandSender sender) {
        File[] files = backupFiles();
        if (files.length == 0) {
            sender.sendMessage(Component.text("Noch keine Backups vorhanden.", NamedTextColor.YELLOW));
            return;
        }
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        sender.sendMessage(Component.text("Backups (" + files.length + "), neueste zuerst:", NamedTextColor.AQUA));
        for (int i = 0; i < Math.min(10, files.length); i++) {
            sender.sendMessage(Component.text(" - " + files[i].getName() + " (" + humanSize(files[i].length()) + ")",
                    NamedTextColor.GRAY));
        }
    }

    // ---------- Hilfsfunktionen ----------

    private List<World> selectWorlds() {
        List<String> names = getConfig().getStringList("worlds");
        if (names.isEmpty()) {
            return new ArrayList<>(getServer().getWorlds());
        }
        List<World> out = new ArrayList<>();
        for (String n : names) {
            World w = getServer().getWorld(n);
            if (w != null) {
                out.add(w);
            } else {
                getLogger().warning("Welt nicht gefunden: " + n);
            }
        }
        return out;
    }

    private File backupDir() {
        return new File(getServer().getWorldContainer(), getConfig().getString("backup-folder", "backups"));
    }

    private File[] backupFiles() {
        File[] files = backupDir().listFiles((d, n) -> n.startsWith("backup-") && n.endsWith(".zip"));
        return files == null ? new File[0] : files;
    }

    private void restoreAutoSave() {
        for (Map.Entry<World, Boolean> e : savedAutoSave.entrySet()) {
            e.getKey().setAutoSave(e.getValue());
        }
        savedAutoSave.clear();
    }

    private void announce(String text, NamedTextColor color) {
        if (getConfig().getBoolean("announce", true)) {
            getServer().broadcast(Component.text("[Backup] " + text, color));
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes / 1024.0;
        String[] units = {"KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.1f %s", v, units[i]);
    }
}
