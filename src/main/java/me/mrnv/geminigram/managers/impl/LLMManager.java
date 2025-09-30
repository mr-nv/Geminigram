package me.mrnv.geminigram.managers.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import me.mrnv.geminigram.Main;
import me.mrnv.geminigram.common.LLM;
import me.mrnv.geminigram.common.LLMRates;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LLMManager
{
    private boolean shouldsave = false;
    private final List< LLMRates > ratelimits = Collections.synchronizedList( new ArrayList<>() );

    public LLMManager()
    {
        // load from file, if it exists
        if( new File( "usage.json" ).exists() )
        {
            JsonArray json;

            try
            {
                byte[] bytes;

                try( FileInputStream fis = new FileInputStream( "usage.json" ) )
                {
                    bytes = fis.readAllBytes();
                }

                json = Main.GSON.fromJson(
                        new String( bytes, StandardCharsets.UTF_8 ),
                        JsonArray.class
                );

                if( json == null )
                    throw new RuntimeException( "Invalid JSON data" );

                for( JsonElement element : json.asList() )
                {
                    if( element.isJsonObject() )
                    {
                        LLMRates rate = LLMRates.fromJson( element.getAsJsonObject() );
                        if( rate != null ) // most likely model not found
                            ratelimits.add( rate );
                    }
                }
            }
            catch( Throwable e )
            {
                Main.LOGGER.warn( "Failed to load usage stats from file, resetting", e );
                ratelimits.clear();
            }
        }

        for( LLM model : LLM.values() )
        {
            // check if an instance of LLMRates already exists
            if( ratelimits.stream().noneMatch( e ->
                    e.getModel().ordinal() == model.ordinal() ) )
            {
                ratelimits.add( new LLMRates( model ) );
            }
        }
    }

    public List< LLMRates > getAvailableModels( String[] list )
    {
        synchronized( ratelimits )
        {
            List< LLMRates > ret = new ArrayList<>();

            for( String name : list )
            {
                LLMRates ratelimiter = getRates( name );
                if( ratelimiter == null ) continue;

                ratelimiter.getMutex().lock();

                try
                {
                    ratelimiter.update();

                    if( ratelimiter.check() ) // see if we use this model
                    {
                        shouldsave = true;
                        ret.add( ratelimiter );
                    }
                }
                finally
                {
                    ratelimiter.getMutex().unlock();
                }
            }

            if( !ret.isEmpty() )
                return ret;
        }

        return null;
    }

    private LLMRates getRates( String model )
    {
        return ratelimits.stream().filter( e ->
                e.getModel().getCode().equals( model ) )
                .findFirst()
                .orElse( null );
    }

    public String toJson()
    {
        return Main.GSON.toJson( ratelimits );
    }

    public boolean shouldSave()
    {
        boolean ret = shouldsave;
        shouldsave = false;
        return ret;
    }
}
