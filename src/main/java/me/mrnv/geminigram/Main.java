package me.mrnv.geminigram;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import me.mrnv.geminigram.managers.Managers;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Main
{
    private static final String PROGRAM = "Geminigram";

    public static final Logger LOGGER = LogManager.getLogger( PROGRAM );
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static boolean SHUTDOWN = false;

    public static void main( String[] args ) throws Exception
    {
        LOGGER.info( PROGRAM + " starting" );

        Managers.CONFIG.load();
        Managers.init();

        new SaveThread().start();
        Runtime.getRuntime().addShutdownHook( new ShutdownHook() );

        LOGGER.info( "Starting bots" );
        Managers.TELEGRAM.start();
    }
}