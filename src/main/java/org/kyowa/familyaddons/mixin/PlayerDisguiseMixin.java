package org.kyowa.familyaddons.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.EnderDragonRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.animal.golem.SnowGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.model.Model;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.kyowa.familyaddons.EntityRefAccessor;
import org.kyowa.familyaddons.features.PlayerDisguise;
import org.kyowa.familyaddons.features.SharedDisguiseSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

@Mixin(LivingEntityRenderer.class)
public abstract class PlayerDisguiseMixin<T extends LivingEntity,
        S extends LivingEntityRenderState,
        M extends Model<?>> {

    // ── Caches for normal (LivingEntity) mobs ────────────────────────
    private static final Map<Class<?>, Method> createRenderStateCache = new HashMap<>();
    private static final Map<String, LivingEntity> cachedMobs = new HashMap<>();
    private static final Map<String, String> cachedMobIds = new HashMap<>();
    private static final Map<String, Boolean> cachedBabies = new HashMap<>();
    private static final Map<String, Boolean> cachedSheareds = new HashMap<>();
    private static final Map<String, LivingEntityRenderState> cachedMobStates = new HashMap<>();
    private static final Map<String, Class<?>> cachedRendererClasses = new HashMap<>();
    private static final Map<String, Integer> mobAge = new HashMap<>();

    // ── Caches for Ender Dragon (non-LivingEntityRenderer path) ──────
    private static final Map<String, EnderDragon> cachedDragons = new HashMap<>();
    private static final Map<String, EnderDragonRenderState> cachedDragonStates = new HashMap<>();
    private static final Map<String, Integer> dragonAge = new HashMap<>();

    @Inject(
            method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void onRender(S state, PoseStack matrixStack, SubmitNodeCollector queue, CameraRenderState cameraRenderState, CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState playerState)) return;

        Minecraft client = Minecraft.getInstance();
        LivingEntity entity = ((EntityRefAccessor) state).familyaddons$getEntity();
        if (!(entity instanceof Player player)) return;
        if (player.isInvisibleTo(client.player)) return;

        boolean isSelf = player == client.player;
        String username = player.getName().getString();

        String mobId = null;
        boolean baby = false;
        boolean sheared = false;
        float customScale = 1.0f;

        if (isSelf) {
            if (!PlayerDisguise.INSTANCE.isEnabled()) return;
            mobId = PlayerDisguise.INSTANCE.getMobId();
            baby = PlayerDisguise.INSTANCE.isBaby();
            sheared = PlayerDisguise.INSTANCE.isSheared();
            customScale = PlayerDisguise.INSTANCE.getCustomScale();
        } else {
            int scope = PlayerDisguise.INSTANCE.getScope();
            if (PlayerDisguise.INSTANCE.isEnabled() && scope == 1) {
                // "Everyone" scope — apply OUR settings to all players.
                mobId = PlayerDisguise.INSTANCE.getMobId();
                baby = PlayerDisguise.INSTANCE.isBaby();
                sheared = PlayerDisguise.INSTANCE.isSheared();
                customScale = PlayerDisguise.INSTANCE.getCustomScale();
            } else {
                SharedDisguiseSync.SyncedDisguise synced = SharedDisguiseSync.INSTANCE.getDisguise(username);
                if (synced == null) return;
                mobId = synced.getMobId();
                baby = synced.getBaby();
                sheared = synced.getSheared();
                customScale = synced.getCustomScale();
            }
        }

        // When custom scaling is active (i.e. the effective scale is anything other
        // than 1.0), the baby toggle is overridden — manual scale takes over, since
        // baby is just a built-in shrink. Sheared still applies normally.
        if (customScale != 1.0f) {
            baby = false;
        }

        if (mobId == null || mobId.isEmpty()) return;
        Identifier id = Identifier.tryParse(mobId);
        if (id == null) return;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
        if (type == null || type == EntityTypes.PLAYER) return;

        // ── Ender Dragon special path ─────────────────────────────────
        if (type == EntityTypes.ENDER_DRAGON) {
            renderAsEnderDragon(client, player, playerState, matrixStack, queue, cameraRenderState, username, customScale, ci);
            return;
        }
        // ─────────────────────────────────────────────────────────────

        // ── Normal LivingEntity path ──────────────────────────────────
        String cachedId = cachedMobIds.get(username);
        LivingEntity cachedMob = cachedMobs.get(username);
        boolean cachedBaby = Boolean.TRUE.equals(cachedBabies.get(username));
        boolean cachedSheared = Boolean.TRUE.equals(cachedSheareds.get(username));

        if (cachedMob == null || !mobId.equals(cachedId) || baby != cachedBaby || sheared != cachedSheared) {
            try {
                cachedMob = (LivingEntity) type.create(player.level(), EntitySpawnReason.COMMAND);
            } catch (Exception e) { cachedMobs.remove(username); return; }
            if (cachedMob == null) return;

            // Baby: works for animals, zombies, AND villagers
            if (baby) {
                if (cachedMob instanceof Villager villager) {
                    villager.setAge(-24000);
                } else if (cachedMob instanceof Animal animal) {
                    animal.setAge(-24000);
                } else if (cachedMob instanceof Zombie zombie) {
                    zombie.setBaby(true);
                }
            }

            // Sheared: sheep removes wool, snow golem removes pumpkin
            if (sheared) {
                if (cachedMob instanceof Sheep sheep) {
                    sheep.setSheared(true);
                } else if (cachedMob instanceof SnowGolem snowGolem) {
                    snowGolem.setPumpkin(false);
                }
            }

            cachedMobs.put(username, cachedMob);
            cachedMobIds.put(username, mobId);
            cachedBabies.put(username, baby);
            cachedSheareds.put(username, sheared);
            cachedMobStates.remove(username);
            cachedRendererClasses.remove(username);
            mobAge.remove(username);
        }

        LivingEntity mob = cachedMob;
        mob.setPos(player.getX(), player.getY(), player.getZ());
        mob.xo = player.xo;
        mob.yo = player.yo;
        mob.zo = player.zo;
        mob.setYRot(player.getYHeadRot());
        mob.yRotO = player.yRotO;
        mob.setXRot(player.getXRot());
        mob.xRotO = player.xRotO;
        mob.yBodyRot = player.yBodyRot;
        mob.yBodyRotO = player.yBodyRotO;
        mob.yHeadRot = player.yHeadRot;
        mob.yHeadRotO = player.yHeadRotO;

        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();

        @SuppressWarnings("unchecked")
        EntityRenderer<LivingEntity, LivingEntityRenderState> renderer =
                (EntityRenderer<LivingEntity, LivingEntityRenderState>) dispatcher.getRenderer(mob);
        if (renderer == null) return;

        Method createRenderState = createRenderStateCache.get(renderer.getClass());
        if (createRenderState == null) {
            Class<?> cls = renderer.getClass();
            outer:
            while (cls != null) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 &&
                            LivingEntityRenderState.class.isAssignableFrom(m.getReturnType())) {
                        m.setAccessible(true);
                        createRenderState = m;
                        break outer;
                    }
                }
                cls = cls.getSuperclass();
            }
            if (createRenderState == null) return;
            createRenderStateCache.put(renderer.getClass(), createRenderState);
        }

        LivingEntityRenderState mobState = cachedMobStates.get(username);
        Class<?> cachedRendererClass = cachedRendererClasses.get(username);
        if (mobState == null || renderer.getClass() != cachedRendererClass) {
            try {
                mobState = (LivingEntityRenderState) createRenderState.invoke(renderer);
                cachedMobStates.put(username, mobState);
                cachedRendererClasses.put(username, renderer.getClass());
            } catch (Exception e) { return; }
        }

        // Tick the mob so flying/animated mobs have correct animation state
        try {
            int age = mobAge.getOrDefault(username, 0) + 1;
            mobAge.put(username, age);
            mob.tickCount = age;
            mob.setDeltaMovement(0.0, 0.1, 0.0);
        } catch (Exception ignored) {}

        try { renderer.extractRenderState(mob, mobState, 1.0f); } catch (Exception e) { return; }

        mobState.bodyRot = playerState.bodyRot;
        mobState.yRot = playerState.yRot;
        mobState.xRot = playerState.xRot;
        mobState.walkAnimationPos = playerState.walkAnimationPos;
        mobState.walkAnimationSpeed = playerState.walkAnimationSpeed;
        mobState.ageInTicks = playerState.ageInTicks;
        mobState.isInvisible = false;
        mobState.lightCoords = playerState.lightCoords;
        mobState.x = playerState.x;
        mobState.y = playerState.y;
        mobState.z = playerState.z;
        // Show the player's name above the disguise instead of the mob's.
        mobState.nameTag = playerState.nameTag;
        mobState.nameTagAttachment = playerState.nameTagAttachment;
        mobState.distanceToCameraSq = playerState.distanceToCameraSq;

        // Apply custom scale around the renderer.render call only.
        // The nametag is submitted OUTSIDE the scale block so it stays at normal
        // player height (the scaled mob would otherwise drag the nametag with it).
        boolean scaled = customScale != 1.0f;
        if (scaled) {
            matrixStack.pushPose();
            matrixStack.scale(customScale, customScale, customScale);
        }
        try { renderer.submit(mobState, matrixStack, queue, cameraRenderState); } catch (Exception e) {
            if (scaled) matrixStack.popPose();
            return;
        }
        if (scaled) matrixStack.popPose();

        ci.cancel();
    }

    // ── Ender Dragon render helper ────────────────────────────────────
    private void renderAsEnderDragon(
            Minecraft client,
            Player player,
            AvatarRenderState playerState,
            PoseStack matrixStack,
            SubmitNodeCollector queue,
            CameraRenderState cameraRenderState,
            String username,
            float customScale,
            CallbackInfo ci) {

        // Get or create a cached dragon entity
        EnderDragon dragon = cachedDragons.get(username);
        if (dragon == null) {
            try {
                dragon = (EnderDragon) EntityTypes.ENDER_DRAGON.create(
                        player.level(), EntitySpawnReason.COMMAND);
                if (dragon == null) return;
                cachedDragons.put(username, dragon);
            } catch (Exception e) { return; }
        }

        // Sync position & rotation to the player.
        // The dragon model faces the opposite direction to normal entities,
        // so we add 180 degrees to all yaw values to flip it the right way.
        dragon.setPos(player.getX(), player.getY(), player.getZ());
        dragon.xo = player.xo;
        dragon.yo = player.yo;
        dragon.zo = player.zo;
        dragon.setYRot(player.getYRot() + 180f);
        dragon.yRotO = player.yRotO + 180f;
        dragon.setXRot(player.getXRot());
        dragon.xRotO = player.xRotO;
        dragon.yBodyRot = player.yBodyRot + 180f;
        dragon.yBodyRotO = player.yBodyRotO + 180f;
        dragon.yHeadRot = player.yHeadRot + 180f;
        dragon.yHeadRotO = player.yHeadRotO + 180f;

        // Tick age counter and throttle tickMovement to ~20/sec (game tick rate).
        // Calling it every render frame (60+/sec) makes the wings flap way too fast.
        int age = dragonAge.getOrDefault(username, 0) + 1;
        dragonAge.put(username, age);
        dragon.tickCount = age;
        dragon.setDeltaMovement(0.0, 0.1, 0.0);

        // Only call aiStep every 3rd render frame ≈ 20 times/sec at 60fps.
        // This advances prevWingPosition/wingPosition at the correct rate for natural wing flap speed.
        if (age % 3 == 0) {
            try { dragon.aiStep(); } catch (Exception ignored) {}
        }

        // Get the dragon's renderer via the entity render dispatcher
        EntityRenderDispatcher dispatcher = client.getEntityRenderDispatcher();
        @SuppressWarnings("unchecked")
        EntityRenderer<EnderDragon, EnderDragonRenderState> renderer =
                (EntityRenderer<EnderDragon, EnderDragonRenderState>)
                        dispatcher.getRenderer(dragon);
        if (renderer == null) return;

        // Get or create the render state reflectively
        EnderDragonRenderState dragonState = cachedDragonStates.get(username);
        if (dragonState == null) {
            Method createRenderState = findNoArgRenderStateMethod(renderer.getClass());
            if (createRenderState == null) return;
            try {
                dragonState = (EnderDragonRenderState) createRenderState.invoke(renderer);
                cachedDragonStates.put(username, dragonState);
            } catch (Exception e) { return; }
        }

        // Let the renderer populate all the dragon-specific fields
        // (partBodyYaws, partPitches, sinceLastAttackTick, wingProgress, etc.)
        try { renderer.extractRenderState(dragon, dragonState, 1.0f); } catch (Exception e) { return; }

        // Override position/light to match the player.
        // extractRenderState already populated body/head rotation from the dragon above.
        dragonState.x = playerState.x;
        dragonState.y = playerState.y;
        dragonState.z = playerState.z;
        dragonState.lightCoords = playerState.lightCoords;
        dragonState.ageInTicks = playerState.ageInTicks;
        dragonState.isInvisible = false;
        // Show the player's name above the disguise.
        dragonState.nameTag = playerState.nameTag;
        dragonState.nameTagAttachment = playerState.nameTagAttachment;
        dragonState.distanceToCameraSq = playerState.distanceToCameraSq;

        // Render the dragon in place of the player, scaled if custom scaling is active.
        boolean scaled = customScale != 1.0f;
        if (scaled) {
            matrixStack.pushPose();
            matrixStack.scale(customScale, customScale, customScale);
        }
        try { renderer.submit(dragonState, matrixStack, queue, cameraRenderState); } catch (Exception e) {
            if (scaled) matrixStack.popPose();
            return;
        }
        if (scaled) matrixStack.popPose();

        ci.cancel();
    }

    /**
     * Finds a no-arg method on the renderer class (or any superclass) whose
     * return type is assignable to EntityRenderState. Used to call
     * createRenderState() reflectively without depending on the exact name
     * (which can be obfuscated / change between MC versions).
     */
    private static Method findNoArgRenderStateMethod(Class<?> cls) {
        while (cls != null) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 &&
                        net.minecraft.client.renderer.entity.state.EntityRenderState.class
                                .isAssignableFrom(m.getReturnType())) {
                    m.setAccessible(true);
                    return m;
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }
}