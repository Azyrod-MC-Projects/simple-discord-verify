package com.azyrod.rpa_whitelist.mixins;

import com.azyrod.rpa_whitelist.RPAWhitelist;
import com.google.common.collect.ImmutableMap;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Member;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.reactivestreams.Publisher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import reactor.core.publisher.Mono;

import java.util.Map;

@Mixin(Entity.class)
public abstract class EntityMixin {
    @Unique
    private static final Map<String, Snowflake> tag_role_map = new ImmutableMap.Builder<String, Snowflake>()
 //           .put("test", Snowflake.of(1086077236211359915L))
            .build();

    @Unique
    private void handleTag(String tag, boolean remove) {
        RPAWhitelist rpa = RPAWhitelist.INSTANCE;

        if ((Object)this instanceof PlayerEntity player) {
            Snowflake player_id = rpa.usercache.get(player.getUuid());
            if (player_id == null) {
                RPAWhitelist.LOGGER.error("Couldn't get Discord ID for Player '{}' - NOT SUPPOSED TO HAPPEN (Or player is OP and didn't verify)", player.getUuid());
                return;
            }

            Snowflake role_id = tag_role_map.get(tag);
            if (role_id == null) {
                return;
            }

            rpa.guild.getMemberById(player_id).map((Member member) -> {
                Publisher<?> change_role = remove ? member.removeRole(role_id) : member.addRole(role_id);

                return Mono.when(change_role).subscribe();
            }).subscribe();
        }
    }

    @Inject(method = "addCommandTag(Ljava/lang/String;)Z", at = @At("HEAD"))
    public void onAddCommandTag(String tag, CallbackInfoReturnable<Boolean> cir) {
        handleTag(tag, false);
    }

    @Inject(method = "removeCommandTag(Ljava/lang/String;)Z", at = @At("HEAD"))
    public void onRemoveCommandTag(String tag, CallbackInfoReturnable<Boolean> cir) {
        handleTag(tag, true);
    }
}
