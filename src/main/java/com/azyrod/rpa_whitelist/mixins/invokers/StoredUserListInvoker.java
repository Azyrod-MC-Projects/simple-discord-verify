package com.azyrod.rpa_whitelist.mixins.invokers;

import net.minecraft.server.players.StoredUserList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(StoredUserList.class)
public interface StoredUserListInvoker {
    @Invoker
    boolean callContains(Object object);
}
