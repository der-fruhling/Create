package com.simibubi.create.content.logistics.packagePort.postbox;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.AllSoundEvents;
import com.simibubi.create.Create;
import com.simibubi.create.content.logistics.box.PackageEntity;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.trains.station.GlobalStation;
import com.simibubi.create.content.trains.station.GlobalStation.GlobalPackagePort;

import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.animation.LerpedFloat.Chaser;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

public class PostboxBlockEntity extends PackagePortBlockEntity {

	public WeakReference<GlobalStation> trackedGlobalStation;

	public LerpedFloat flag;
	public boolean forceFlag;

	private boolean sendParticles;
	private boolean isDirtied = false;

	public PostboxBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
		trackedGlobalStation = new WeakReference<>(null);
		flag = LerpedFloat.linear()
			.startWithValue(0);

		GlobalStation station = trackedGlobalStation.get();
		if(station != null && station.connectedPorts.containsKey(worldPosition)) {
			GlobalPackagePort globalPackagePort = station.connectedPorts.get(worldPosition);
			for (int i = 0; i < inventory.getSlots(); i++) {
				inventory.setStackInSlot(i, globalPackagePort.offlineBuffer.getStackInSlot(i));
			}

			Create.RAILWAYS.markTracksDirty();
		}

		inventory.whenContentsChanged(integer -> isDirtied = true);
	}

	private void updateInventoryFromStation() {
		GlobalStation station = trackedGlobalStation.get();
		if(station != null && station.connectedPorts.containsKey(worldPosition)) {
			GlobalPackagePort globalPackagePort = station.connectedPorts.get(worldPosition);
			List<ItemStack> overflow = new ArrayList<>();

			if(!isDirtied && !globalPackagePort.primed) return;
			isDirtied = false;

			for (int i = 0; i < inventory.getSlots(); i++) {
				ItemStack offline = globalPackagePort.offlineBuffer.getStackInSlot(i);
				ItemStack self = inventory.getStackInSlot(i);

				if(!offline.isEmpty() && self.isEmpty()) {
					inventory.setStackInSlot(i, offline);
				} else if(offline.isEmpty() && !self.isEmpty()) {
					globalPackagePort.offlineBuffer.setStackInSlot(i, self);
				} else if(!offline.isEmpty() && !self.isEmpty()) {
					if(offline.is(self.getItem()) && offline.getCount() == self.getCount()) {
						// both are equal
						inventory.setStackInSlot(i, offline);
					} else {
						// items in conflict
						// trust offline buffer for this slot and attempt to
						// fit extra item in another slot later
						inventory.setStackInSlot(i, offline);
						overflow.add(self);
					}
				} else if (!overflow.isEmpty()) {
					// both slots empty (attempt to empty overflow)
					ItemStack rem = inventory.insertItem(i, overflow.get(0), false);

					if (rem.isEmpty()) {
						overflow.remove(0);
					} else {
						overflow.set(0, rem);
					}
				}
			}

			if (level != null) {
				for (ItemStack item : overflow) {
					if (PackageItem.isPackage(item)) {
						PackageEntity e = new PackageEntity(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5);
						e.setBox(item);
						level.addFreshEntity(e);
					} else {
						level.addFreshEntity(new ItemEntity(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5, item));
					}
				}
			}

			for (int i = 0; i < inventory.getSlots(); i++) {
				globalPackagePort.offlineBuffer.setStackInSlot(i, inventory.getStackInSlot(i));
			}

			Create.RAILWAYS.markTracksDirty();
		}
	}

	@Override
	public void tick() {
		super.tick();

		if (!level.isClientSide) {
			updateInventoryFromStation();
		}

		if (!level.isClientSide && !isVirtual()) {
			if (sendParticles)
				sendData();
			return;
		}

		float currentTarget = flag.getChaseTarget();
		if (currentTarget == 0 || flag.settled()) {
			int target = (inventory.isEmpty() && !forceFlag) ? 0 : 1;
			if (target != currentTarget) {
				flag.chase(target, 0.1f, Chaser.LINEAR);
				if (target == 1)
					AllSoundEvents.CONTRAPTION_ASSEMBLE.playAt(level, worldPosition, 1, 2, true);
			}
		}
		boolean settled = flag.getValue() > .15f;
		flag.tickChaser();
		if (currentTarget == 0 && settled != flag.getValue() > .15f)
			AllSoundEvents.CONTRAPTION_DISASSEMBLE.playAt(level, worldPosition, 0.75f, 1.5f, true);

		if (sendParticles) {
			sendParticles = false;
			BoneMealItem.addGrowthParticles(level, worldPosition, 40);
		}
	}

	@Override
	protected void onOpenChange(boolean open) {
		level.setBlockAndUpdate(worldPosition, getBlockState().setValue(PostboxBlock.OPEN, open));
		level.playSound(null, worldPosition, open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE,
			SoundSource.BLOCKS);
	}

	public void spawnParticles() {
		sendParticles = true;
	}

	@Override
	protected void write(CompoundTag tag, boolean clientPacket) {
		super.write(tag, clientPacket);
		if (clientPacket && sendParticles)
			NBTHelper.putMarker(tag, "Particles");
		sendParticles = false;
	}

	@Override
	protected void read(CompoundTag tag, boolean clientPacket) {
		super.read(tag, clientPacket);
		sendParticles = clientPacket && tag.contains("Particles");
	}
}
