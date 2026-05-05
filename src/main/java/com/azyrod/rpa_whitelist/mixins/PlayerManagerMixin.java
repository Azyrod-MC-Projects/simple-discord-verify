package com.azyrod.rpa_whitelist.mixins;

import com.azyrod.rpa_whitelist.RPAWhitelist;
import com.azyrod.rpa_whitelist.config.DiscordUserCache;
import com.azyrod.rpa_whitelist.mixins.invokers.ServerConfigListInvoker;
import discord4j.common.util.Snowflake;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.*;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;
import java.util.List;
import java.util.UUID;

@Mixin(value = PlayerManager.class, priority = Integer.MIN_VALUE)
public abstract class PlayerManagerMixin {
    @Final
    @Shadow
    private OperatorList ops;
    @Final
    @Shadow
    private Whitelist whitelist;
    @Final
    @Shadow
    private MinecraftServer server;

    @Shadow
    public abstract boolean isWhitelistEnabled();

    @Shadow @Final private List<ServerPlayerEntity> players;

    @Inject(method = "checkCanJoin(Ljava/net/SocketAddress;Lnet/minecraft/server/PlayerConfigEntry;)Lnet/minecraft/text/Text;", at = @At("HEAD"), cancellable = true)
    public void checkCanJoin(SocketAddress socketAddress, PlayerConfigEntry playerConfigEntry, CallbackInfoReturnable<Text> cir) {
        if (this.isModDisabled() || shouldBypassLogic(playerConfigEntry)) {
            return; // Let MC use the default whitelist logic
        }

        RPAWhitelist rpa = RPAWhitelist.INSTANCE;
        UUID uuid = playerConfigEntry.id();
        Snowflake id = rpa.usercache.get(uuid);
        Text disconnectReason = this.checkPlayerHasAccess(playerConfigEntry, id);

        if (disconnectReason != null) {
            cir.setReturnValue(disconnectReason);
            this.whitelist.remove(playerConfigEntry);
            if (id != null) {
                rpa.enqueueRoleUpdate(uuid, id).subscribe();
            }
            return;
        }

        this.whitelist.add(new WhitelistEntry(playerConfigEntry));

        rpa.enqueueRoleUpdate(uuid, id, (discordUserRecord -> {
            if (!rpa.userHasDiscordRole(discordUserRecord)) {
                this.server.submitAndJoin(() -> {
                    this.whitelist.remove(playerConfigEntry);

                    this.players.stream().filter(player_entity -> player_entity.getUuid() == uuid)
                            .findFirst().ifPresent(serverPlayerEntity -> {
                                serverPlayerEntity.networkHandler.disconnect(rpa.config.values.messages.minecraft.getMissingRoleText());
                            });
                });
            }
        })).subscribe();
    }

    @Inject(method = "onPlayerConnect", at = @At("TAIL"), cancellable = true)
    public void onPlayerConnect(ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
        PlayerConfigEntry playerConfigEntry = player.getPlayerConfigEntry();
        if (this.isModDisabled() || shouldBypassLogic(playerConfigEntry)) {
            return;
        }

        RPAWhitelist rpa = RPAWhitelist.INSTANCE;
        Snowflake id = rpa.usercache.get(player.getUuid());
        Text disconnectReason = this.checkPlayerHasAccess(playerConfigEntry, id);

        if (disconnectReason != null) {
            connection.disconnect(disconnectReason);
            ci.cancel();
        }
    }

    @Unique
    private Text checkPlayerHasAccess(PlayerConfigEntry playerConfigEntry, Snowflake id) {
        RPAWhitelist rpa = RPAWhitelist.INSTANCE;

        if (id == null) {
            return rpa.makeNotVerifiedMessage(playerConfigEntry);
        }
        UUID uuid = playerConfigEntry.id();
        DiscordUserCache.DiscordUserRecord discordUser = rpa.usercache.getDiscordUser(uuid);
        if (discordUser == null || discordUser.roles() == null) {
            return rpa.config.values.messages.minecraft.getPendingVerificationText();
        }

        if (rpa.userHasDiscordRole(discordUser)) {
            return null; // No error message, means Success ! Player has access
        } else {
            return rpa.config.values.messages.minecraft.getMissingRoleText();
        }
    }

    @Unique
    private boolean isModDisabled() {
        RPAWhitelist rpa = RPAWhitelist.INSTANCE;

        // Missing required config values. Mod is essentially Disabled.
        return rpa == null || rpa.config.isIncomplete() || rpa.isDisabled();
    }

    @Unique
    private boolean shouldBypassLogic(PlayerConfigEntry playerConfigEntry) {
        // Without a whitelist anyone is allowed. OPs are always allowed
        return !this.isWhitelistEnabled() || ((ServerConfigListInvoker) this.ops).callContains(playerConfigEntry);
    }
}