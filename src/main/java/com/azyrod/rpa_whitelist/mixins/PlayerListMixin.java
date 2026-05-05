package com.azyrod.rpa_whitelist.mixins;

import com.azyrod.rpa_whitelist.RPAWhitelist;
import com.azyrod.rpa_whitelist.config.DiscordUserCache;
import com.azyrod.rpa_whitelist.mixins.invokers.StoredUserListInvoker;
import discord4j.common.util.Snowflake;
import net.minecraft.network.Connection;
import net.minecraft.server.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.players.ServerOpList;
import net.minecraft.server.players.UserWhiteList;
import net.minecraft.server.players.UserWhiteListEntry;
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

@Mixin(value = PlayerList.class, priority = Integer.MIN_VALUE)
public abstract class PlayerListMixin {
    @Final
    @Shadow
    private ServerOpList ops;
    @Final
    @Shadow
    private UserWhiteList whitelist;
    @Final
    @Shadow
    private MinecraftServer server;

    @Shadow
    public abstract boolean isUsingWhitelist();

    @Shadow @Final private List<ServerPlayer> players;

    @Inject(method = "canPlayerLogin(Ljava/net/SocketAddress;Lnet/minecraft/server/players/NameAndId;)Lnet/minecraft/network/chat/Component;", at = @At("HEAD"), cancellable = true)
    public void checkCanJoin(SocketAddress socketAddress, NameAndId playerConfigEntry, CallbackInfoReturnable<Component> cir) {
        if (this.isModDisabled() || shouldBypassLogic(playerConfigEntry)) {
            return; // Let MC use the default whitelist logic
        }

        RPAWhitelist rpa = RPAWhitelist.INSTANCE;
        UUID uuid = playerConfigEntry.id();
        Snowflake id = rpa.usercache.get(uuid);
        Component disconnectReason = this.checkPlayerHasAccess(playerConfigEntry, id);

        if (disconnectReason != null) {
            cir.setReturnValue(disconnectReason);
            this.whitelist.remove(playerConfigEntry);
            if (id != null) {
                rpa.enqueueRoleUpdate(uuid, id).subscribe();
            }
            return;
        }

        this.whitelist.add(new UserWhiteListEntry(playerConfigEntry));

        rpa.enqueueRoleUpdate(uuid, id, (discordUserRecord -> {
            if (!rpa.userHasDiscordRole(discordUserRecord)) {
                this.server.executeBlocking(() -> {
                    this.whitelist.remove(playerConfigEntry);

                    this.players.stream().filter(player_entity -> player_entity.getUUID() == uuid)
                            .findFirst().ifPresent(serverPlayerEntity -> {
                                serverPlayerEntity.connection.disconnect(rpa.config.values.messages.minecraft.getMissingRoleText());
                            });
                });
            }
        })).subscribe();
    }

    @Inject(method = "placeNewPlayer", at = @At("TAIL"), cancellable = true)
    public void onPlayerConnect(Connection connection, ServerPlayer player, CommonListenerCookie clientData, CallbackInfo ci) {
        NameAndId playerConfigEntry = player.nameAndId();
        if (this.isModDisabled() || shouldBypassLogic(playerConfigEntry)) {
            return;
        }

        RPAWhitelist rpa = RPAWhitelist.INSTANCE;
        Snowflake id = rpa.usercache.get(player.getUUID());
        Component disconnectReason = this.checkPlayerHasAccess(playerConfigEntry, id);

        if (disconnectReason != null) {
            connection.disconnect(disconnectReason);
            ci.cancel();
        }
    }

    @Unique
    private Component checkPlayerHasAccess(NameAndId playerConfigEntry, Snowflake id) {
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
    private boolean shouldBypassLogic(NameAndId playerConfigEntry) {
        // Without a whitelist anyone is allowed. OPs are always allowed
        return !this.isUsingWhitelist() || ((StoredUserListInvoker) this.ops).callContains(playerConfigEntry);
    }
}