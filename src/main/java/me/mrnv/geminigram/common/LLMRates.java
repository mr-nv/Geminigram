package me.mrnv.geminigram.common;

import com.google.gson.JsonObject;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@RequiredArgsConstructor
public class LLMRates
{
    private static final long ONE_DAY_IN_MILLIS =
            TimeUnit.DAYS.toMillis( 1 );

    @Getter private transient final ReentrantLock mutex = new ReentrantLock();
    @Getter private final LLM model;

    private int rpm = 0;
    private int rpd = 0;
    private long startminute = 0;
    private long startday = 0;

    public void update()
    {
        long time = System.currentTimeMillis();
        if( startminute > 0 )
        {
            // reset RPM (requests per minute)
            if( ( time - startminute ) >= 60000 )
            {
                rpm = 0;
                startminute = 0;
            }
        }

        if( startday > 0 )
        {
            // reset RPD (requests per day)
            if( ( time - startday ) >= ONE_DAY_IN_MILLIS )
            {
                rpd = 0;
                startday = 0;
            }
        }
    }

    public boolean check()
    {
        return rpm < model.getMaxRequestsPerMinute() &&
                rpd < model.getMaxRequestsPerDay();
    }

    public synchronized void setTime( long time )
    {
        if( startminute == 0 )
            startminute = time;

        if( startday == 0 )
            startday = time;
    }

    public synchronized void increaseCount( int value )
    {
        this.rpm += value;
        this.rpd += value;
    }

    public synchronized void decreaseCount( int value )
    {
        this.rpm -= value;
        this.rpd -= value;
    }

    public static LLMRates fromJson( JsonObject json )
    {
        try
        {
            String model = json.get( "model" ).getAsString();
            LLM llm = LLM.valueOf( model );

            int rpm = json.get( "rpm" ).getAsInt();
            int rpd = json.get( "rpd" ).getAsInt();
            long startminute = json.get( "startminute" ).getAsLong();
            long startday = json.get( "startday" ).getAsLong();

            LLMRates ret = new LLMRates( llm );

            ret.rpm = rpm;
            ret.rpd = rpd;
            ret.startminute = startminute;
            ret.startday = startday;

            return ret;
        }
        catch( Throwable e )
        {
            return null;
        }
    }
}
