package com.azyrod.rpa_whitelist;

import com.azyrod.rpa_whitelist.Discord.CommandRegistrar;
import com.azyrod.rpa_whitelist.config.DiscordUserCache;
import com.azyrod.rpa_whitelist.config.ModConfig;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.interaction.ChatInputInteractionEvent;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.User;
import discord4j.core.spec.InteractionFollowupCreateMono;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.OldUsersConverter;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.*;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.NotNull;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class RPAWhitelist implements DedicatedServerModInitializer {
    public static final String MOD_ID = "simple-discord-verify";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static RPAWhitelist INSTANCE;

    public final ModConfig config = new ModConfig();
    public final DiscordUserCache usercache = new DiscordUserCache();
    private final Map<UUID, LoginCode> loginCodeMap = new HashMap<>();

    public MinecraftServer minecraftServer;

    // TODO: Move to config file
    public final long THREE_MONTHS_MS = 3L * 30 * 24 * 60 * 60 * 1000;
    public final Snowflake SMP_ACTIVE_ROLE = Snowflake.of(1423011656367083654L);
    public final Snowflake SMP_INACTIVE_ROLE = Snowflake.of(1423012296896155648L);

    private final Object lock = new Object();
    public DiscordClient client;
    public GatewayDiscordClient gateway;
    public Guild guild;

    public static final Identifier DISCORD_VERIFY_ENABLED_IDENTIFIER = Identifier.fromNamespaceAndPath("simple_discord_verify", "discord_verify_enabled");
    public static final GameRule<Boolean> DISCORD_VERIFY_ENABLED = GameRuleBuilder.forBoolean(true).category(GameRuleCategory.PLAYER).buildAndRegister(DISCORD_VERIFY_ENABLED_IDENTIFIER);

    @Override
    public void onInitializeServer() {
        INSTANCE = this;
        ServerLifecycleEvents.SERVER_STARTING.register(server -> this.minecraftServer = server);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        refresh();
    }

    public boolean isDisabled() {
        return !minecraftServer.getGameRules().get(DISCORD_VERIFY_ENABLED);
    }

    public synchronized void refresh() {
        try {
            usercache.load();
            config.load();
        } catch (IOException e) {
            LOGGER.error("Failed to load config - DiscordBot will NOT work", e);
            return;
        }

        if (config.isIncomplete()) {
            LOGGER.error("Config is missing required parameters ! DiscordBot will NOT work. Please edit the mod config.");
            return;
        }

        onRefresh();
    }

    private void onServerTick(MinecraftServer server) {
        if (config.values.inactive_role_logic && server.getTickCount() % (20 * 60 * 30) == 0) {
            checkPlayersLastPlayedAt();
        }
    }

    private void checkPlayersLastPlayedAt() {
        if (isDisabled() || config.isIncomplete())
            return;
        try (var stream = Files.list(this.minecraftServer.getWorldPath(LevelResource.PLAYER_DATA_DIR))) {
            long epoch = Util.getEpochMillis();

            stream.forEach(path -> {
                Path filename = path.getFileName();
                String str = filename.toString();

                if (Files.isRegularFile(path) && str.endsWith(".dat") && !str.endsWith("-.dat")) {
                    try {
                        FileTime time = Files.getLastModifiedTime(path);
                        String uuid_str = str.split("\\.")[0];
                        UUID uuid = UUID.fromString(uuid_str);
                        Snowflake player_id = this.usercache.get(uuid);
                        Snowflake remove_role;
                        Snowflake add_role;

                        if (player_id == null) return;

                        if (time.toMillis() + THREE_MONTHS_MS <= epoch) {
                            remove_role = SMP_ACTIVE_ROLE;
                            add_role = SMP_INACTIVE_ROLE;
                        } else {
                            remove_role = SMP_INACTIVE_ROLE;
                            add_role = SMP_ACTIVE_ROLE;
                        }

                        this.guild.getMemberById(player_id).map((Member member) -> {
                            return Mono.when(member.removeRole(remove_role), member.addRole(add_role)).subscribe();
                        }).subscribe();
                    } catch (IOException e) {
                        LOGGER.error("Failed to read modified time of '{}': ", path, e);
                    }
                }
            });
        } catch (IOException ex) {
            LOGGER.error("Failed to access PlayerData files: ", ex);
        }
    }

    private void onRefresh() {
        synchronized (lock) {
            if (this.gateway != null) {
                this.gateway.logout().block();
            }
            this.client = null;
            this.gateway = null;
            this.guild = null;
        }

        if (this.config.isIncomplete()) {
            return;
        }

        this.client = DiscordClient.create(this.config.values.discord_bot_token);

        try {
            CommandRegistrar.registerCommands(this.client, this.config);
        } catch (Exception e) {
            LOGGER.error("Failed to register Discord Commands", e);
        }

        this.client.withGateway(gateway -> {
            synchronized (lock) {
                if (this.gateway != null) {
                    this.gateway.logout().block();
                }
                this.gateway = gateway;
            }

            Publisher<?> chat_input_hook = gateway.on(ChatInputInteractionEvent.class, event -> {
                Optional<Snowflake> guild_id = event.getInteraction().getGuildId();
                String server_name = event.getOptionAsString("mc_server").orElse(null);
                boolean invalid_server_name = !Objects.equals(server_name, config.values.server_config.server_name);

                // TODO: Make server_name config optional if there is only 1 server name ?
                if (guild_id.isEmpty() || guild_id.get().asLong() != config.values.discord_server_id || invalid_server_name) {
                    return Mono.empty(); // Not our event, Ignore it
                }

                return event.deferReply().withEphemeral(true).then(
                        Mono.fromCallable(() -> onDiscordSlashCommand(event).withEphemeral(true))
                ).flatMap(m -> m);
            });
            this.guild = gateway.getGuildById(Snowflake.of(this.config.values.discord_server_id)).block();

            List<Mono<Member>> monos = new ArrayList<>();

            this.usercache.forEachDiscordUser((uuid, discordUserRecord) -> {
               if (discordUserRecord.roles() == null) {
                   monos.add(this.enqueueRoleUpdate(uuid, discordUserRecord.id()).onErrorResume(e -> {
                       LOGGER.error("Failed to enqueue Discord Role Update", e);

                       return Mono.empty();
                   }));
               }
            });

            return Mono.when(chat_input_hook).and(Flux.concat(monos));
        }).subscribe();
    }

    public Mono<Member> enqueueRoleUpdate(@NotNull UUID uuid, @NotNull Snowflake id, Consumer<DiscordUserCache.DiscordUserRecord> consumer) {
        return this.guild.getMemberById(id).map(member -> {
            DiscordUserCache.DiscordUserRecord record = usercache.put(uuid, member);
            if (consumer != null) consumer.accept(record);
            return member;
        }).onErrorResume(error -> {
            if (error instanceof discord4j.rest.http.client.ClientException clientException) {
                if (clientException.getStatus().code() == 404) {
                    usercache.remove(uuid);
                }
            }
            return Mono.empty();
        });
    }

    public Mono<Member> enqueueRoleUpdate(@NotNull UUID uuid, @NotNull Snowflake id) {
        return enqueueRoleUpdate(uuid, id, null);
    }

    public boolean userHasDiscordRole(@NotNull DiscordUserCache.DiscordUserRecord discordUser) {
        ModConfig.WhitelistConfig whitelist_config = config.values.whitelist_config;
        List<Long> roles = discordUser.roles().stream().map(Snowflake::asLong).toList();

        return whitelist_config.disallowed_discord_roles.stream().noneMatch(roles::contains) &&
                whitelist_config.allowed_discord_roles.stream().anyMatch(roles::contains);
    }

    public Component makeNotVerifiedMessage(@NotNull NameAndId profile) {
        UUID uuid = profile.id();
        LoginCode loginCode = loginCodeMap.get(uuid);

        if (loginCode == null) {
            byte[] b = new byte[6];
            new Random().nextBytes(b);

            int code = 0;
            for (byte value : b) {
                code = (code * 10) + Math.abs(value % 10);
            }

            loginCode = new LoginCode(code, 0);
            loginCodeMap.put(uuid, loginCode);
        }
        String command = "/%s %s %s %s".formatted(CommandRegistrar.makeCommandName("verify", config), config.values.server_config.server_name, profile.name(), loginCode.code);

        MutableComponent text = config.values.messages.minecraft.getNotVerifiedText().copy();
        text.getSiblings().replaceAll((sibling) -> {
            String content = sibling.getContents().visit(Optional::of).orElse(null);
            if (content == null || !content.contains("#{VERIFY_COMMAND_TEXT}")) {
                return sibling; // no modifications
            }
            MutableComponent tmp = Component.literal(content.replace("#{VERIFY_COMMAND_TEXT}", command)).setStyle(sibling.getStyle());
            sibling.getSiblings().forEach(tmp::append);
            return tmp;
        });
        return text;
    }

    public InteractionFollowupCreateMono onDiscordSlashCommand(@NotNull ChatInputInteractionEvent event) {
        return switch (CommandRegistrar.stripPrefixFromCommand(event.getCommandName(), config)) {
            case "verify" -> verifyCommand(event);
            case "unlink" -> unlinkCommand(event);
            default ->
                    event.createFollowup("Unknown command '" + event.getCommandName() + "'. Not sure how you managed that... Congrats I guess ? (Please report this. This is a probably a bug)");
        };
    }

    public InteractionFollowupCreateMono unlinkCommand(@NotNull ChatInputInteractionEvent event) {
        UUID uuid = this.usercache.get(event.getUser().getId());
        ModConfig.DiscordMessagesUnlink messages = config.values.messages.discord.unlink;

        if (this.usercache.remove(uuid)) {
            return event.createFollowup(messages.success);
        } else {
            return event.createFollowup(messages.not_linked);
        }
    }

    public InteractionFollowupCreateMono verifyCommand(@NotNull ChatInputInteractionEvent event) {
        ModConfig.DiscordMessagesVerify messages = config.values.messages.discord.verify;
        Member member = event.getInteraction().getMember().orElse(null);

        if (this.minecraftServer == null) {
            return event.createFollowup(messages.restarting);
        }

        String username = event.getOptionAsString("mc_username").orElse(null);
        Long code = event.getOptionAsLong("code").orElse(null);

        AtomicReference<UUID> uuidRef = new AtomicReference<>();
        this.minecraftServer.executeBlocking(() -> uuidRef.set(OldUsersConverter.convertMobOwnerIfNecessary(this.minecraftServer, username)));
        UUID uuid = uuidRef.get();
        if (usercache.get(uuid) != null) {
            return verifyCommandResponse(event, member);
        }

        LoginCode loginCode = loginCodeMap.get(uuid);

        if (loginCode == null || code == null) {
            return event.createFollowup(messages.login_missing);
        }
        if (loginCode.code != code) {
            loginCode.failedAttempts++;
            if (loginCode.failedAttempts >= 5) {
                loginCodeMap.remove(uuid);
                return event.createFollowup(messages.too_many_invalid_login);
            }
            return event.createFollowup(messages.invalid_login);
        }
        if (member != null) {
            usercache.put(uuid, member);
        } else {
            User user = event.getUser();
            usercache.put(uuid, user);
            enqueueRoleUpdate(uuid, user.getId()).subscribe();
        }

        return verifyCommandResponse(event, member);
    }

    private InteractionFollowupCreateMono verifyCommandResponse(@NotNull ChatInputInteractionEvent event, Member member) {
        if (member == null) {
            return event.createFollowup("Somehow failed to check your Discord roles... Please report this error.\n\nYou can try to login on the Minecraft Server and see if it works");
        }

        UUID uuid = this.usercache.get(member.getId());
        DiscordUserCache.DiscordUserRecord discordUser = this.usercache.getDiscordUser(uuid);
        ModConfig.DiscordMessagesVerify messages = config.values.messages.discord.verify;

        if (discordUser == null || discordUser.roles() == null) {
            discordUser = usercache.put(uuid, member);
        }

        if (userHasDiscordRole(discordUser)) {
            return event.createFollowup(messages.success);
        } else {
            return event.createFollowup(messages.missing_role);
        }
    }

    private static class LoginCode {
        LoginCode(int code, int failedAttempts) {
            this.code = code;
            this.failedAttempts = failedAttempts;
        }

        int code;
        int failedAttempts;
    }
}
