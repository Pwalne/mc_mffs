package com.mffs.model.tile.type;

import com.mffs.MFFS;
import com.mffs.ModConfiguration;
import com.mffs.api.IProjector;
import com.mffs.api.modules.IModule;
import com.mffs.api.modules.IProjectorMode;
import com.mffs.api.vector.Vector3D;
import com.mffs.model.blocks.BlockForceField;
import com.mffs.model.items.card.CardBlank;
import com.mffs.model.items.modules.projector.ModuleDisintegration;
import com.mffs.model.items.modules.projector.type.ModeCustom;
import com.mffs.model.items.modules.upgrades.ModuleSilence;
import com.mffs.model.items.modules.upgrades.ModuleSpeed;
import com.mffs.model.net.packet.ForcefieldCalculation;
import com.mffs.model.tile.TileFieldInteraction;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import net.minecraft.block.*;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * @author Calclavia
 */
public class TileForceFieldProjector extends TileFieldInteraction implements IProjector {

    /* Set of all forceFields by this entity */
    protected final Set<Vector3D> blocks = new HashSet<>();

    /* Flag indicating if this entity has finished */
    private boolean isComplete;

    public boolean requireTicks, markFieldUpdate = true;

    public TileForceFieldProjector() {
        this.capacityBase = 50;
        this.module_index = 1;
    }

    @Override
    public void start() {
        super.start();
        calculatedForceField();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        destroyField();
    }

    @Override
    public void updateEntity() {
        super.updateEntity();
        if (isActive() && getMode() != null && requestFortron(getFortronCost(), false) >= getFortronCost()) {
            requestFortron(getFortronCost(), true);
            if (this.ticks % 10 == 0 || markFieldUpdate || requireTicks)
                if (!this.isFinished)
                    calculatedForceField();
                else
                    projectField();

            if (isActive() && worldObj.isRemote)
                this.animation += getFortronCost() / 10;

            if (this.ticks % 40 == 0 && getModuleCount(ModuleSilence.class) <= 0)
                this.worldObj.playSoundEffect(this.xCoord + 0.5, this.yCoord + 0.5, this.zCoord + 0.5, MFFS.MODID + ":field", 0.6F, 1.0F - this.worldObj.rand.nextFloat() * 0.1F);
        } else if (!this.worldObj.isRemote) {
            destroyField();
        }
    }

    /**
     * Calculates the forcefield locations.
     */
    @Override
    public void calculatedForceField() {
        if (!isCalc && !getWorldObj().isRemote) {
            IProjectorMode stack = getMode();
            if (stack != null) {
                this.blocks.clear();
            }
        }
        super.calculatedForceField();
        this.isComplete = false;
        this.requireTicks = false;

        Set<ItemStack> modules = getModuleStacks();
        for (ItemStack mod : modules) {
            if (((IModule) mod.getItem()).requireTicks(mod)) {
                requireTicks = true;
                return;
            }
        }
    }

    @Override
    public void onCalculationCompletion() {
        //TODO: Send field to client
        //Check if repulsion
        //if(getModuleCount())
        //MFFS.channel.sendToAll(new ForcefieldCalculation(TileForceFieldProjector.this));
    }

    @Override
    public int getSizeInventory() {
        return 21;
    }

    @Override
    public int getProjectionSpeed() {
        return 28 + 28 * getModuleCount(ModuleSpeed.class, getModuleSlots());
    }

    @Override
    public long getTicks() {
        return this.ticks;
    }

    /**
     * @return
     */
    @Override
    public int calculateFortronCost() {
        IProjectorMode mode = getMode();
        if (mode != null) {
            return Math.round(super.calculateFortronCost() + mode.getFortronCost(getAmplifier()));
        }
        return 0;
    }

    @Override
    public float getAmplifier() {
        IProjectorMode mode = getMode();
        if (mode instanceof ModeCustom) {
            //TODO: Custom mode
        }
        return Math.max(Math.min(getCalculatedField().size() / 1000, 10), 1);
    }

    @Override
    public Set<Vector3D> getForceFields() {
        return blocks;
    }

    public void projectField() {
        if (this.isFinished && !this.isCalc && (!this.isComplete || this.markFieldUpdate || this.requireTicks)) {
            this.markFieldUpdate = false;
            int constructCount = 0;
            int constructSpeed = Math.min(getProjectionSpeed(), ModConfiguration.MAX_FORCE_FIELDS_PER_TICK);
            restart:
            synchronized (this.calculatedFields) {
                Set<Vector3D> fieldToBeProjected = new HashSet(this.calculatedFields);
                for (IModule module : getModules(getModuleSlots())) {
                    if (module.onProject(this, fieldToBeProjected)) {
                        return;
                    }
                }
                Vector3D projector = new Vector3D(this);
                for (Iterator<Vector3D> it$ = fieldToBeProjected.iterator(); it$.hasNext(); ) {
                    Vector3D vec = it$.next();
                    Block block = worldObj.getBlock(vec.intX(), vec.intY(), vec.intZ());

                    if (block == null || getModuleCount(ModuleDisintegration.class) >= 0 && block.getBlockHardness(worldObj, vec.intX(), vec.intY(), vec.intZ()) != -1.0
                            || block.getMaterial().isLiquid() || block instanceof BlockSnow || block instanceof BlockVine || block instanceof BlockTallGrass || block instanceof BlockDeadBush
                            || block.isReplaceable(worldObj, vec.intX(), vec.intY(), vec.intZ())) {
                        if (vec != projector && !(block instanceof BlockForceField)) {
                            constructCount++;
                            for (IModule module : getModules(getModuleSlots())) {
                                int flag = module.onProject(this, vec);

                                if (flag == 1)
                                    break;
                                if (flag == 2)
                                    break restart;
                            }

                            if (!worldObj.isRemote)
                                worldObj.setBlock(vec.intX(), vec.intY(), vec.intZ(), BlockForceField.BLOCK_FORCE_FIELD, 0, 2);

                            this.blocks.add(vec);

                            TileEntity entity = vec.getTileEntity(worldObj);
                            if (entity instanceof TileForceField)
                                ((TileForceField) entity).setProjector(projector);

                            requestFortron(1, true);
                            if (constructCount > constructSpeed)
                                break;
                        }
                    }
                }
            }
            this.isComplete = (constructCount == 0);
        }
    }

    @Override
    public void destroyField() {
        if (!worldObj.isRemote && !this.isCalc && isFinished) {
            synchronized (this.calculatedFields) {
                for (IModule module : getModules(getModuleSlots())) {
                    if (module.onDestroy(this, getCalculatedField())) {
                        break;
                    }
                }
                for (Iterator<Vector3D> it$ = new HashSet<>(this.calculatedFields).iterator(); it$.hasNext(); ) {
                    Vector3D vec = it$.next();
                    Block block = worldObj.getBlock(vec.intX(), vec.intY(), vec.intZ());
                    if (block instanceof BlockForceField) {
                        worldObj.setBlockToAir(vec.intX(), vec.intY(), vec.intZ());
                    }
                }
            }
        }
        this.blocks.clear();
        this.calculatedFields.clear();
        this.isComplete = false;
        this.isFinished = false;
        this.requireTicks = false;
    }

    /**
     * @return
     */
    @Override
    public Set<ItemStack> getCards() {
        Set<ItemStack> set = new HashSet<>();
        set.add(super.getCard());
        set.add(getStackInSlot(1));
        return set;
    }

    /**
     * Returns true if automation is allowed to insert the given stack (ignoring stack size) into the given slot.
     *
     * @param slot
     * @param stack
     */
    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        if (slot <= 1) {
            return stack.getItem() instanceof CardBlank;
        } else if (slot == 2) {
            return stack.getItem() instanceof IProjectorMode;
        }
        return slot >= 15 || stack.getItem() instanceof IModule;
    }

    /**
     * Handles the message given by the handler.
     *
     * @param imessage The message.
     */
    @Override
    public IMessage handleMessage(IMessage imessage) {
        if (imessage instanceof ForcefieldCalculation) {
            ForcefieldCalculation calc = (ForcefieldCalculation) imessage;
            getCalculatedField().clear();
            getCalculatedField().addAll(calc.getBlocks());
            this.isCalc = true;
            return null; //we are done!
        }
        return super.handleMessage(imessage);
    }
}