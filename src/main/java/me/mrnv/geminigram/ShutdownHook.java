package me.mrnv.geminigram;

import me.mrnv.geminigram.managers.Managers;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

// sometimes this does not get called...
// dont really care as there is SaveThread
public class ShutdownHook extends Thread
{
    @Override
    public void run()
    {
        Main.SHUTDOWN = true;
        Managers.DATABASE.shutdown();
        Main.LOGGER.info( "Saving LLM usage stats" );

        try
        {
            byte[] bytes = Managers.MODELS.toJson().getBytes( StandardCharsets.UTF_8 );

            try( FileOutputStream fos = new FileOutputStream( "usage.json" ) )
            {
                fos.write( bytes );
            }
        }
        catch( Throwable e )
        {
            Main.LOGGER.error( "Failed to save LLM usage stats", e );
        }

        Main.LOGGER.info( "Shutting down Telegram bots" );
        Managers.TELEGRAM.shutdown();
    }
}
