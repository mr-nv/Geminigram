package me.mrnv.geminigram;

import me.mrnv.geminigram.managers.Managers;
import me.mrnv.geminigram.util.Utils;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class SaveThread extends Thread
{
    @Override
    public void run()
    {
        while( !Main.SHUTDOWN )
        {
            if( Managers.MODELS.shouldSave() )
            {
                try
                {
                    byte[] bytes = Managers.MODELS.toJson().getBytes( StandardCharsets.UTF_8 );

                    try( FileOutputStream fos = new FileOutputStream( "usage.json" ) )
                    {
                        fos.write( bytes );
                    }
                }
                catch( Throwable ignored ) {}
            }

            Utils.sleep( 30000 );
        }
    }
}
