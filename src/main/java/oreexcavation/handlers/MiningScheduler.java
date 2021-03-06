package oreexcavation.handlers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.MinecraftForge;
import oreexcavation.core.ExcavationSettings;
import oreexcavation.events.EventExcavate;
import oreexcavation.shapes.ExcavateShape;
import oreexcavation.undo.ExcavateHistory;
import oreexcavation.undo.RestoreResult;
import com.google.common.base.Stopwatch;

public class MiningScheduler
{
	public static final MiningScheduler INSTANCE = new MiningScheduler();
	
	private ArrayDeque<MiningAgent> agents = new ArrayDeque<>();
	private HashMap<UUID,ExcavateHistory> undoing = new HashMap<>();
	private HashMap<UUID,List<ExcavateHistory>> undoHistory = new HashMap<>();
	
	private Stopwatch timer;
	
	private MiningScheduler()
	{
		timer = Stopwatch.createStarted();
	}
	
	public MiningAgent getActiveAgent(UUID uuid)
	{
		for(MiningAgent a : agents)
		{
			if(a.getPlayerID().equals(uuid))
			{
				return a;
			}
		}
		
		return null;
	}
	
	public void stopMining(EntityPlayerMP player)
	{
		MiningAgent a = getActiveAgent(player.getUniqueID());
		
		if(a != null)
		{
			MinecraftForge.EVENT_BUS.post(new EventExcavate.Post(a));
			a.dropEverything();
			agents.remove(a);
		}
	}
	
	@Deprecated
	public MiningAgent startMining(EntityPlayerMP player, BlockPos pos, IBlockState state, ExcavateShape shape)
	{
		return startMining(player, pos, state, shape, ExcavateShape.getFacing(player));
	}
	
	public MiningAgent startMining(EntityPlayerMP player, BlockPos pos, IBlockState state)
	{
		return startMining(player, pos, state, null, EnumFacing.NORTH);
	}
	
	public MiningAgent startMining(EntityPlayerMP player, BlockPos pos, IBlockState state, ExcavateShape shape, EnumFacing dir)
	{
		MiningAgent existing = getActiveAgent(player.getUniqueID());
		
		if(existing != null)
		{
			existing.appendBlock(pos);
		} else
		{
			existing = new MiningAgent(player, pos, state);
			
			if(shape != null)
			{
				existing.setShape(shape, dir);
			}
			
			if(MinecraftForge.EVENT_BUS.post(new EventExcavate.Pre(existing)))
			{
				return null;
			}
			
			agents.add(existing);
			
			existing.init();
		}
		
		return existing;
	}
	
	public RestoreResult attemptUndo(EntityPlayer player)
	{
		RestoreResult result;
		List<ExcavateHistory> list = undoHistory.get(player.getUniqueID());
		list = list != null? list : new ArrayList<>();
		
		if(list.size() <= 0)
		{
			return RestoreResult.NO_UNDO_HISTORY;
		} else
		{
			result = list.get(list.size() - 1).canRestore(player.getServer(), player);
		}
		
		if(result == RestoreResult.SUCCESS)
		{
			undoing.put(player.getUniqueID(), list.remove(list.size() - 1));
		}
		
		return result;
	}
	
	public void appendHistory(UUID uuid, ExcavateHistory history)
	{
		List<ExcavateHistory> list = undoHistory.get(uuid);
		list = list != null? list : new ArrayList<>();
		
		list.add(history);
		
		while(list.size() > ExcavationSettings.maxUndos)
		{
			list.remove(0);
		}
		
		undoHistory.put(uuid, list);
	}
	
	public void tickAgents(MinecraftServer server)
	{
		timer.reset();
		timer.start();
		
		int n = agents.size();
		
		for(int i = 0; i < n && !agents.isEmpty(); i++)
		{
			if(ExcavationSettings.tpsGuard && timer.elapsed(TimeUnit.MILLISECONDS) > 40)
			{
				EventHandler.skipNext = true;
				break;
			}
			
			MiningAgent a = agents.poll();
			
			if(a == null)
			{
				continue;
			}
			
			EventHandler.captureAgent = a;
			boolean complete = a.tickMiner(timer);
			EventHandler.captureAgent = null;
			
			if(complete)
			{
				MinecraftForge.EVENT_BUS.post(new EventExcavate.Post(a));
				
				a.dropEverything();
				appendHistory(a.getPlayerID(), a.getHistory());
			} else
			{
				agents.add(a);
			}
		}
		
		Iterator<Entry<UUID,ExcavateHistory>> iterUndo = undoing.entrySet().iterator();
		
		while(iterUndo.hasNext())
		{
			if(ExcavationSettings.tpsGuard && timer.elapsed(TimeUnit.MILLISECONDS) > 40)
			{
				EventHandler.skipNext = true;
				break;
			}
			
			Entry<UUID,ExcavateHistory> entry = iterUndo.next();
			
			boolean complete = entry.getValue().tickRestore(server, server.getPlayerList().getPlayerByUUID(entry.getKey()));
			
			if(complete)
			{
				iterUndo.remove();
			}
		}
		
		timer.stop();
	}
	
	public void resetAll()
	{
		agents.clear();
		undoing.clear();
		undoHistory.clear();
	}
}
