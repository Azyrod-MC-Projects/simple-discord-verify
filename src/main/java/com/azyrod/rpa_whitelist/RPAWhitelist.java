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
import discord4j.core.retriever.EntityRetrievalStrategy;
import discord4j.core.spec.InteractionFollowupCreateMono;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.ServerConfigHandler;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.*;
import net.minecraft.world.rule.GameRule;
import net.minecraft.world.rule.GameRuleCategory;
import org.jetbrains.annotations.NotNull;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class RPAWhitelist implements DedicatedServerModInitializer {
    public static final String MOD_ID = "rpa-whitelist";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static RPAWhitelist INSTANCE;

    public final ModConfig config = new ModConfig();
    public final Map<UUID, Integer> loginCodeMap = new HashMap<>();
    public final DiscordUserCache usercache = new DiscordUserCache();

    public MinecraftServer minecraftServer;

    public final long THREE_MONTHS_MS = 3L * 30 * 24 * 60 * 60 * 1000;
    public final Snowflake SMP_ACTIVE_ROLE = Snowflake.of(1423011656367083654L);
    public final Snowflake SMP_INACTIVE_ROLE = Snowflake.of(1423012296896155648L);

    private final Object lock = new Object();
    public DiscordClient client;
    public GatewayDiscordClient gateway;
    public Guild guild;

    public static final Identifier DISCORD_VERIFY_ENABLED_IDENTIFIER = Identifier.of("rpa_verify", "discord_verify_enabled");
    public static final GameRule<Boolean> DISCORD_VERIFY_ENABLED = GameRuleBuilder.forBoolean(true).category(GameRuleCategory.PLAYER).buildAndRegister(DISCORD_VERIFY_ENABLED_IDENTIFIER);

    @Override
    public void onInitializeServer() {
        INSTANCE = this;
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            this.minecraftServer = server;
        });
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);

        refresh();
    }

    public boolean isDisabled() {
        return !minecraftServer.getSaveProperties().getGameRules().getValue(DISCORD_VERIFY_ENABLED);
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
        if (server.getTicks() % (20 * 60 * 30) == 0) {
            checkPlayersLastPlayedAt();
        }
    }

    private void checkPlayersLastPlayedAt() {
        if (isDisabled() || config.isIncomplete() || !"SMP".equals(config.values.server_config().server_name()))
            return;
        try (var stream = Files.list(this.minecraftServer.getSavePath(WorldSavePath.PLAYERDATA))) {
            long epoch = Util.getEpochTimeMs();

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

        this.client = DiscordClient.create(this.config.values.discord_bot_token());

        try {
            new CommandRegistrar(this.client).registerCommands(this.config);
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
                boolean invalid_server_name = !Objects.equals(server_name, config.values.server_config().server_name());

                // TODO: Make server_name config optional if there is only 1 server name ?
                if (guild_id.isEmpty() || guild_id.get().asLong() != config.values.discord_server_id() || invalid_server_name) {
                    return Mono.empty(); // Not our event, Ignore it
                }

                return event.deferReply().withEphemeral(true).then(
                        Mono.fromCallable(() -> onDiscordSlashCommand(event).withEphemeral(true))
                ).flatMap(m -> m);
            });
            this.guild = gateway.getGuildById(Snowflake.of(this.config.values.discord_server_id())).block();

            return Mono.when(chat_input_hook);
        }).subscribe();
    }

    public boolean userHasDiscordRole(Snowflake id) {
        return Boolean.TRUE.equals(guild.getMemberById(id).map(this::userHasDiscordRole).block());
    }

    public boolean userHasDiscordRole(Member member) {
        return Boolean.TRUE.equals(member.getRoles(EntityRetrievalStrategy.REST).any(role -> {
            long role_id = role.getId().asLong();
            var whitelist_config = config.values.whitelist_config();

            return whitelist_config.allowed_discord_roles().stream().anyMatch(id -> id == role_id) &&
                    whitelist_config.disallowed_discord_roles().stream().noneMatch(id -> id == role_id);
        }).block());
    }

    public Text makeNotVerifiedMessage(@NotNull PlayerConfigEntry profile) {
        UUID uuid = profile.id();
        Integer code = loginCodeMap.get(uuid);

        if (code == null) {
            byte[] b = new byte[6];
            new Random().nextBytes(b);

            code = 0;
            for (byte value : b) {
                code = (code * 10) + Math.abs(value % 10);
            }

            loginCodeMap.put(uuid, code);
        }

        MutableText text = Text.empty();
        Text title = Text.literal("Your Discord Account has not be verified yet !").formatted(Formatting.WHITE, Formatting.BOLD);
        Text separator = Text.literal("\n\n");
        Text body = Text.literal("""
                    Before you can join our Minecraft Server, we need to verify you have been granted access.
                    Access is given on Discord, so we will need to verify your Discord account.
                    Please use the following Discord command for the bot to verify your account.
                """
        ).styled(style -> style.withFormatting(Formatting.RESET));
        String command = "/rpa_verify %s %s %s".formatted(config.values.server_config().server_name(), profile.name(), code);
        Text link = Text.literal(command).styled((style) -> style.withFormatting(Formatting.BLUE));

        MutableText channel = Text.literal("Please use this command in ")
                .append((Text) Text.literal("#squire-bot-commands").styled(style -> style.withColor(Colors.CYAN)))
                .append(" channel");

        return text.append(title).append(separator).append(body).append(separator).append(link).append(separator).append(channel);
    }

    public Text makeMissingRoleMessage() {
        MutableText text = Text.empty();
        Text title = Text.literal("You do not have the required role to join the Minecraft Server !").formatted(Formatting.DARK_RED, Formatting.BOLD);
        Text separator = Text.literal("\n\n");
        Text body = Text.literal(
                "In order to play on our Minecraft Server, you need to be given access via Discord.\n" +
                        "Please refer to the How To Apply channel for more information."
        ).styled(style -> style.withFormatting(Formatting.RESET));
        return text.append(title).append(separator).append(body);
    }

    public InteractionFollowupCreateMono onDiscordSlashCommand(@NotNull ChatInputInteractionEvent event) {
        return switch (event.getCommandName()) {
            case "rpa_verify" -> verifyCommand(event);
            case "rpa_unlink" -> unlinkCommand(event);
            default ->
                    event.createFollowup("Unknown command '" + event.getCommandName() + "'. Not sure how you managed that... Congrats I guess ?");
        };
    }

    public InteractionFollowupCreateMono unlinkCommand(@NotNull ChatInputInteractionEvent event) {
        if (this.usercache.remove(event.getUser().getId())) {
            return event.createFollowup("Your Discord account has been unlinked from your Minecraft account successfully.");
        } else {
            return event.createFollowup("Your Discord account was not linked to any Minecraft account.");
        }
    }

    public InteractionFollowupCreateMono verifyCommand(@NotNull ChatInputInteractionEvent event) {
        if (this.minecraftServer == null) {
            return event.createFollowup("Minecraft Server is restarting - please wait a couple minutes");
        }

        String username = event.getOptionAsString("mc_username").orElse(null);
        Long code = event.getOptionAsLong("code").orElse(null);

        AtomicReference<UUID> uuid = new AtomicReference<>();
        this.minecraftServer.submitAndJoin(() -> {
            uuid.set(ServerConfigHandler.getPlayerUuidByName(this.minecraftServer, username));
        });
        if (usercache.get(uuid.get()) != null) {
            return verifyCommandResponse(event);
        }

        Integer expected_code = loginCodeMap.get(uuid.get());

        if (expected_code == null || code == null) {
            return event.createFollowup("Please login on the Minecraft Server once to generate a login code.");
        }
        // TODO: After a couple failed attempts, re-generate the code so users can't brute-force
        if (expected_code.longValue() != code) {
            return event.createFollowup("Invalid login code. Please login on the Minecraft Server to receive your personal code.");
        }
        usercache.put(uuid.get(), event.getUser().getId());

        return verifyCommandResponse(event);
    }

    private InteractionFollowupCreateMono verifyCommandResponse(@NotNull ChatInputInteractionEvent event) {
        Member member = event.getInteraction().getMember().orElse(null);

        if (member == null) {
            return event.createFollowup("Somehow failed to check your Discord roles... Please report this error.\n\nYou can try to login on the Minecraft Server and see if it works");
        }

        if (userHasDiscordRole(member)) {
            return event.createFollowup("Access to the Minecraft Server granted !");
        } else {
            return event.createFollowup("You do not have the required role to join the Minecraft Server.\nPlease refer to the How To Apply channel for more information.");
        }
    }
}
