package me.mrnv.geminigram.managers.impl;

import me.mrnv.geminigram.common.SpamEntry;
import me.mrnv.geminigram.common.SpamType;
import me.mrnv.geminigram.managers.Managers;

import java.util.*;

public class SpamManager
{
    private final Map< Long, List< SpamEntry > > timers =
            Collections.synchronizedMap( new HashMap<>() );

    public boolean check( long bot, long chat, SpamType type )
    {
        long addtime = Managers.CONFIG.getLong( type.getConfigEntry() );

        synchronized( timers )
        {
            long time = System.currentTimeMillis();

            if( !timers.containsKey( bot ) )
                timers.put( bot, new ArrayList<>() );

            List< SpamEntry > entries = timers.get( bot );

            entries.removeIf(
                    entry -> time >= entry.getTime()
            );

            for( SpamEntry entry : entries )
            {
                if( entry.getChat() == chat )
                {
                    entry.setTime( entry.getTime() + ( addtime / 2L ) );
                    return false;
                }
            }

            // not found
            SpamEntry entry = new SpamEntry( chat );
            entry.setTime( time + addtime );
            entries.add( entry );

            return true;
        }
    }

    public void forceUpdateTime( long bot, long chat, SpamType type )
    {
        long addtime = Managers.CONFIG.getLong( type.getConfigEntry() );

        synchronized( timers )
        {
            if( timers.containsKey( bot ) )
            {
                List< SpamEntry > entries = timers.get( bot );

                for( SpamEntry entry : entries )
                {
                    if( entry.getChat() == chat )
                    {
                        entry.setTime( System.currentTimeMillis() + addtime );
                        break;
                    }
                }
            }
        }
    }
}
