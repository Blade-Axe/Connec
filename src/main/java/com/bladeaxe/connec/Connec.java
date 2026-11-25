package com.bladeaxe.connec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Stream;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class Connec extends JavaPlugin implements Listener {

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private String endpoint;
    private String secret;
    private String statsEndpoint;

    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}");
    private final Deque<UUID> skinQueue = new ConcurrentLinkedDeque<>();
    private final Set<UUID> skinQueued = ConcurrentHashMap.newKeySet();
    private String skinsEndpoint;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        endpoint = getConfig().getString("endpoint", "");
        secret = getConfig().getString("secret", "");
        long ticks = getConfig().getLong("heartbeat-seconds", 30) * 20L;

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> push(false), 20L, ticks);

        statsEndpoint = getConfig().getString("stats-endpoint", "");
        long statsTicks = Math.max(1L, getConfig().getLong("stats-interval-minutes", 5)) * 60L * 20L;
        getServer().getScheduler().runTaskTimer(this, this::refreshStats, 200L, statsTicks);

        skinsEndpoint = getConfig().getString("skins-endpoint", "");
        // One lookup every 2 seconds, so Mojang does not refuse the requests.
        getServer().getScheduler().runTaskTimer(this, this::processSkinQueue, 100L, 40L);
    }

    @Override
    public void onDisable() {
        send(endpoint, "{\"online\":false}", true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        schedulePush();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        schedulePush();
    }

    // Wait one tick. Then the player list is correct for a leaving player.
    private void schedulePush() {
        getServer().getScheduler().runTask(this, () -> push(true));
    }

    private void push(boolean includeSkins) {
        List<String> players = getServer().getOnlinePlayers().stream()
                .map(p -> "{\"name\":" + quote(p.getName())
                        + ",\"uuid\":" + quote(p.getUniqueId().toString()) + "}")
                .toList();
        String motd = legacy.serialize(getServer().motd());

        StringBuilder skins = new StringBuilder();
        if (includeSkins) {
            for (Player player : getServer().getOnlinePlayers()) {
                for (ProfileProperty property : player.getPlayerProfile().getProperties()) {
                    if (property.getName().equals("textures")) {
                        if (!skins.isEmpty()) {
                            skins.append(',');
                        }
                        skins.append(quote(player.getUniqueId().toString()))
                                .append(':')
                                .append(quote(property.getValue()));
                    }
                }
            }
        }

        String json = "{"
                + "\"motd\":" + quote(motd) + ","
                + "\"version\":" + quote(getServer().getMinecraftVersion()) + ","
                + "\"max\":" + getServer().getMaxPlayers() + ","
                + "\"skins\":{" + skins + "},"
                + "\"players\":[" + String.join(",", players) + "]"
                + "}";
        send(endpoint, json, false);
    }

    private void refreshStats() {
        Path dir = findStatsDir();
        if (dir == null) {
            getLogger().warning("Stats folder not found.");
            return;
        }
        // Save the player files first. Then the files hold the newest values.
        getServer().savePlayers();
        getServer().getScheduler().runTaskLaterAsynchronously(this, () -> pushStats(dir), 40L);
    }

    private Path findStatsDir() {
        Path world = getServer().getWorldContainer().toPath()
                .resolve(getServer().getWorlds().get(0).getName());
        for (Path candidate : List.of(
                world.resolve("players").resolve("stats"),
                world.resolve("stats"))) {
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void pushStats(Path dir) {
        List<String> batch = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String fileName = file.getFileName().toString();
                if (!fileName.endsWith(".json")) {
                    continue;
                }
                String id = fileName.substring(0, fileName.length() - 5);
                try {
                    UUID uuid = UUID.fromString(id);
                    String name = getServer().getOfflinePlayer(uuid).getName();
                    if (name == null) {
                        name = id.substring(0, 8);
                    }
                    batch.add("{\"uuid\":" + quote(id)
                            + ",\"name\":" + quote(name)
                            + ",\"stats\":" + quote(Files.readString(file)) + "}");
                } catch (IllegalArgumentException | IOException ex) {
                    getLogger().warning("Skipped " + fileName + ": " + ex.getMessage());
                }
                if (batch.size() >= 50) {
                    sendBatch(batch);
                }
            }
        } catch (IOException ex) {
            getLogger().warning("Could not read stats folder: " + ex.getMessage());
        }
        sendBatch(batch);
    }

    private void sendBatch(List<String> batch) {
        if (batch.isEmpty()) {
            return;
        }
        sendStats("{\"players\":[" + String.join(",", batch) + "]}");
        batch.clear();
    }

    // Like send(), but it reads the answer. The answer lists the players without a skin.
    private void sendStats(String json) {
        if (statsEndpoint == null || statsEndpoint.isBlank()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(statsEndpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + secret)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(res -> queueMissingSkins(res.body()))
                    .exceptionally(ex -> {
                        getLogger().warning("Push failed: " + ex.getMessage());
                        return null;
                    });
        } catch (Exception ex) {
            getLogger().warning("Push failed: " + ex.getMessage());
        }
    }

    private void queueMissingSkins(String body) {
        Matcher matcher = UUID_PATTERN.matcher(body);
        while (matcher.find()) {
            UUID uuid = UUID.fromString(matcher.group());
            // A player is queued once at a time.
            if (skinQueued.add(uuid)) {
                skinQueue.add(uuid);
            }
        }
    }

    private void processSkinQueue() {
        UUID uuid = skinQueue.poll();
        if (uuid == null) {
            return;
        }
        getServer().createProfile(uuid).update()
                .thenAccept(profile -> {
                    boolean found = false;
                    for (ProfileProperty property : profile.getProperties()) {
                        if (property.getName().equals("textures")) {
                            found = true;
                            send(skinsEndpoint,
                                    "{\"skins\":{" + quote(uuid.toString()) + ":" + quote(property.getValue()) + "}}",
                                    false);
                        }
                    }
                    // No skin came back. Allow a new try after the next stats push.
                    if (!found) {
                        skinQueued.remove(uuid);
                    }
                })
                .exceptionally(ex -> {
                    getLogger().warning("Skin lookup failed for " + uuid + ": " + ex.getMessage());
                    skinQueued.remove(uuid);
                    return null;
                });
    }

    private void send(String url, String json, boolean blocking) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + secret)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            if (blocking) {
                http.send(request, HttpResponse.BodyHandlers.discarding());
            } else {
                http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                        .exceptionally(ex -> {
                            getLogger().warning("Push failed: " + ex.getMessage());
                            return null;
                        });
            }
        } catch (Exception ex) {
            getLogger().warning("Push failed: " + ex.getMessage());
        }
    }

    private String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
