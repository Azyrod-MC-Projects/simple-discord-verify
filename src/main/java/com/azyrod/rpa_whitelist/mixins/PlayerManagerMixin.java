package com.azyrod.rpa_whitelist.mixins;

import com.azyrod.rpa_whitelist.RPAWhitelist;
import com.azyrod.rpa_whitelist.mixins.invokers.ServerConfigListInvoker;
import com.mojang.authlib.GameProfile;
import discord4j.common.util.Snowflake;
import net.minecraft.server.*;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerManager.class)
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

    @Inject(method = "checkCanJoin(Ljava/net/SocketAddress;Lcom/mojang/authlib/GameProfile;)Lnet/minecraft/text/Text;", at = @At("HEAD"), cancellable = true)
    public void checkCanJoin(SocketAddress socketAddress, GameProfile gameProfile, CallbackInfoReturnable<Text> cir) {
        RPAWhitelist rpa = RPAWhitelist.INSTANCE;

        if (rpa == null || rpa.config.isIncomplete() || rpa.isDisabled()) {
            return; // Missing required config values. Mod is essentially Disabled. Let MC use the default whitelist logic
        }

        if (!this.isWhitelistEnabled() || ((ServerConfigListInvoker)this.ops).callContains(gameProfile)) {
            return; // Without a whitelist anyone is allowed. OPs are always allowed
        }

        Snowflake id = rpa.usercache.get(gameProfile.getId());
        if (id == null) {
            cir.setReturnValue(rpa.makeNotVerifiedMessage(gameProfile));
            return;
        }

        if (rpa.userHasDiscordRole(id)) {
            this.whitelist.add(new WhitelistEntry(gameProfile));
        } else {
            this.whitelist.remove(gameProfile);
            cir.setReturnValue(rpa.makeMissingRoleMessage());
        }
    }
}
