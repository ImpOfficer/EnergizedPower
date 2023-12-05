package me.jddev0.ep.block;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import me.jddev0.ep.block.entity.HeatGeneratorBlockEntity;
import me.jddev0.ep.block.entity.ModBlockEntities;
import me.jddev0.ep.codec.CodecFix;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

public class HeatGeneratorBlock extends BlockWithEntity {
    public static final MapCodec<HeatGeneratorBlock> CODEC = RecordCodecBuilder.mapCodec(instance -> {
        return instance.group(CodecFix.FABRIC_BLOCK_SETTINGS_CODEC.fieldOf("properties").forGetter(block -> {
            return (FabricBlockSettings)block.getSettings();
        })).apply(instance, HeatGeneratorBlock::new);
    });

    public HeatGeneratorBlock(FabricBlockSettings props) {
        super(props);
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos blockPos, BlockState state) {
        return new HeatGeneratorBlockEntity(blockPos, state);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public ActionResult onUse(BlockState state, World level, BlockPos blockPos, PlayerEntity player, Hand handItem, BlockHitResult hit) {
        if(level.isClient())
            return ActionResult.SUCCESS;

        BlockEntity blockEntity = level.getBlockEntity(blockPos);
        if(!(blockEntity instanceof HeatGeneratorBlockEntity))
            throw new IllegalStateException("Container is invalid");

        player.openHandledScreen((HeatGeneratorBlockEntity)blockEntity);

        return ActionResult.SUCCESS;
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World level, BlockState state, BlockEntityType<T> type) {
        return validateTicker(type, ModBlockEntities.HEAT_GENERATOR_ENTITY, HeatGeneratorBlockEntity::tick);
    }
}
