package com.lastpoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LastPointStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Object saveLock = new Object();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    private Path path;
    private ScheduledExecutorService executor;
    private String signText = "[last]";
    private StoredLocation defaultLocation;
    private final Map<String, StoredLocation> locations = new LinkedHashMap<>();

    public static LastPointStorage load() {
        LastPointStorage storage = new LastPointStorage();
        Path path = FabricLoader.getInstance().getConfigDir().resolve("lastpoint.json");
        storage.path = path;
        if (Files.exists(path)) {
            try {
                DiskData data = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), DiskData.class);
                if (data != null) {
                    if (data.signText != null && data.signText.isBlank() == false) {
                        storage.signText = data.signText;
                    }
                    storage.defaultLocation = data.defaultLocation;
                    if (data.locations != null) {
                        storage.locations.putAll(data.locations);
                    }
                }
            } catch (Exception e) {
                LastPointMod.LOGGER.error("[LastPoint] Failed to read {}", path, e);
            }
        }
        storage.startAutoSave();
        return storage;
    }

    private void startAutoSave() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "LastPoint-Save");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::flushIfDirty, 15, 15, TimeUnit.SECONDS);
    }

    public String signText() {
        return signText;
    }

    public void record(UUID uuid, ServerPlayer player) {
        StoredLocation location = StoredLocation.from(player);
        synchronized (locations) {
            locations.put(uuid.toString(), location);
        }
        dirty.set(true);
    }

    public StoredLocation get(UUID uuid) {
        synchronized (locations) {
            return locations.get(uuid.toString());
        }
    }

    public void setDefault(ServerPlayer player) {
        defaultLocation = StoredLocation.from(player);
        dirty.set(true);
    }

    public StoredLocation getDefault() {
        return defaultLocation;
    }

    public int playerCount() {
        synchronized (locations) {
            return locations.size();
        }
    }

    private void flushIfDirty() {
        if (dirty.get() == false) {
            return;
        }
        dirty.set(false);
        writeSnapshot();
    }

    public void saveNow() {
        dirty.set(false);
        writeSnapshot();
    }

    public void shutdown() {
        saveNow();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void writeSnapshot() {
        synchronized (saveLock) {
            DiskData data = new DiskData();
            data.signText = signText;
            data.defaultLocation = defaultLocation;
            synchronized (locations) {
                data.locations = new LinkedHashMap<>(locations);
            }
            try {
                Path parent = path.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path temp = path.resolveSibling(path.getFileName() + ".tmp");
                Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                dirty.set(true);
                LastPointMod.LOGGER.error("[LastPoint] Failed to write {}", path, e);
            }
        }
    }

    private static final class DiskData {
        private String signText;
        private StoredLocation defaultLocation;
        private Map<String, StoredLocation> locations;
    }

    public static final class StoredLocation {
        public String world;
        public double x;
        public double y;
        public double z;
        public float yaw;
        public float pitch;

        private static StoredLocation from(ServerPlayer player) {
            StoredLocation location = new StoredLocation();
            location.world = player.level().dimension().identifier().toString();
            location.x = player.getX();
            location.y = player.getY();
            location.z = player.getZ();
            location.yaw = player.getYRot();
            location.pitch = player.getXRot();
            return location;
        }
    }
}
