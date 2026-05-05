package com.azyrod.rpa_whitelist.config;

import com.azyrod.rpa_whitelist.RPAWhitelist;
import com.azyrod.rpa_whitelist.config.json.SnowflakeDeserializer;
import com.azyrod.rpa_whitelist.config.json.SnowflakeSerializer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.User;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

public class DiscordUserCache {
    public static final Path FILE_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("rpa_whitelist").resolve("discord_user_cache.json").normalize();

    private final BiMap<UUID, Snowflake> cache = HashBiMap.create();
    private final Map<UUID, DiscordUserRecord> discordUserCache = new HashMap<>();
    private final File file = new File(FILE_PATH.toString());
    private final ObjectMapper mapper;

    public DiscordUserCache() {
        mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addSerializer(Snowflake.class, new SnowflakeSerializer());
        module.addDeserializer(Snowflake.class, new SnowflakeDeserializer());
        mapper.registerModule(module);
    }

    public synchronized UUID get(Snowflake id) { return cache.inverse().get(id); }
    public synchronized Snowflake get(UUID uuid) { return cache.get(uuid); }
    public synchronized DiscordUserRecord getDiscordUser(UUID uuid) { return discordUserCache.get(uuid); }

    public void forEachSnowflake(BiConsumer<UUID, Snowflake> consumer) {
        this.cache.forEach(consumer);
    }

    public void forEachDiscordUser(BiConsumer<UUID, DiscordUserRecord> consumer) {
        this.discordUserCache.forEach(consumer);
    }

    public synchronized boolean remove(UUID uuid) {
        boolean result = discordUserCache.remove(uuid) != null;

        cache.remove(uuid);
        trySave();
        return result;
    }

    public synchronized void put(UUID uuid, User discord_user) {
        cache.put(uuid, discord_user.getId());
        discordUserCache.put(uuid, new DiscordUserRecord(discord_user.getId(), null));
        trySave();
    }

    public synchronized DiscordUserRecord put(UUID uuid, Member discord_user) {
        DiscordUserRecord record = new DiscordUserRecord(discord_user);
        cache.put(uuid, discord_user.getId());
        discordUserCache.put(uuid, record);

        trySave();
        return record;
    }

    public synchronized void load() throws IOException {
        Files.createDirectories(FILE_PATH.getParent());

        if (file.createNewFile()) {
            try (FileWriter writer = new FileWriter(file)) {
                writer.write("{}");
            }
            this.clear();
        }

        boolean loadedOldSchema = tryLoadOldSchema();

        if (!loadedOldSchema) {
            TypeReference<Map<UUID, DiscordUserRecord>> typeRef = new TypeReference<>() {};
            Map<UUID, DiscordUserRecord> map = mapper.readValue(file, typeRef);

            this.clear();
            discordUserCache.putAll(map);
            discordUserCache.forEach((UUID uuid, DiscordUserRecord record) -> cache.put(uuid, record.id));
        }
    }

    private boolean tryLoadOldSchema() {
        Map<UUID, Snowflake> map;
        try {
            TypeReference<Map<UUID, Snowflake>> typeRef = new TypeReference<>() {};
            map = mapper.readValue(file, typeRef);
            RPAWhitelist.LOGGER.warn("Loading and upgrading old schema...");
        } catch (IOException e) {
            return false;
        }
        this.clear();
        cache.putAll(map);
        cache.forEach((uuid, snowflake) -> discordUserCache.put(uuid, new DiscordUserRecord(snowflake, null)));

        trySave(); // Save new schema
        return true;
    }

    private synchronized void clear() {
        cache.clear();
        discordUserCache.clear();
    }

    private void trySave() {
        try {
            mapper.writeValue(file, discordUserCache);
        } catch (IOException e) {
            RPAWhitelist.LOGGER.error("Failed to save Discord User cache", e);
        }
    }

    public record DiscordUserRecord(Snowflake id, List<Snowflake> roles) {
        DiscordUserRecord(Member user) {
            this(user.getId(), user.getRoleIds().stream().toList());
        }
    }
}
