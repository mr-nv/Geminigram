package me.mrnv.geminigram.managers.impl;

import com.amihaiemil.eoyaml.Yaml;
import com.amihaiemil.eoyaml.YamlMapping;
import com.amihaiemil.eoyaml.YamlNode;
import com.amihaiemil.eoyaml.YamlSequence;
import me.mrnv.geminigram.Main;
import me.mrnv.geminigram.common.LLM;
import me.mrnv.geminigram.common.TelegramBotEntry;
import me.mrnv.geminigram.managers.Managers;
import me.mrnv.geminigram.util.Utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

public class ConfigManager
{
    private YamlMapping yaml;

    public void load()
    {
        // save if not found
        if( !new File( "config.yml" ).exists() )
        {
            InputStream is = ConfigManager.class.getResourceAsStream( "/config.yml" );
            if( is == null )
                throw new RuntimeException( "Failed to extract the config, \"config.yml\" was not found in the resources" );

            try
            {
                byte[] bytes = is.readAllBytes();
                is.close();

                FileOutputStream fos = new FileOutputStream( "config.yml" );
                fos.write( bytes );
                fos.close();

                Main.LOGGER.info( "Generated the config (config.yml). Modify it to your liking." );

                Utils.sleep( 2000 );
                System.exit( 0 );
            }
            catch( Throwable e )
            {
                throw new RuntimeException( "Failed to save the default config", e );
            }
        }

        // load the config
        try
        {
            yaml = Yaml.createYamlInput( new File( "config.yml" ) ).readYamlMapping();
        }
        catch( Throwable e )
        {
            throw new RuntimeException( "Failed to read the config", e );
        }

        // validate the config and load some stuff
        try
        {
            String geminikey = getString( "gemini.apiKey" );
            if( geminikey.length() < 10 )
                throw new IllegalStateException( "Invalid Gemini API key provided" );

            YamlMapping telegram = yaml.yamlMapping( "telegram" );
            YamlSequence bots = telegram.yamlSequence( "bots" );
            for( YamlNode node : bots )
            {
                YamlMapping entry = node.asMapping();

                String key = entry.string( "apiKey" );
                String[] ask = sequenceAsArray( entry, "commandAsk" );
                String[] instructions = sequenceAsArray( entry, "commandInstructions" );

                if( key.isEmpty() )
                    throw new IllegalStateException( "Invalid Telegram bot API key provided" );

                // this sucks
                for( String command : ask )
                {
                    if( command.length() <= 1 )
                        throw new IllegalStateException( "Invalid ask command provided: [%s]".formatted( command ) );

                    for( TelegramBotEntry botentry : Managers.TELEGRAM.getStartList() )
                    {
                        if( Arrays.asList( botentry.instructionCommands() ).contains( command ) )
                            throw new IllegalStateException( "You can't have ask commands be the same as instruction commands" );
                    }
                }

                for( String command : instructions )
                {
                    if( command.length() <= 1 )
                        throw new IllegalStateException( "Invalid instruction command provided: [%s]".formatted( command ) );

                    for( TelegramBotEntry botentry : Managers.TELEGRAM.getStartList() )
                    {
                        if( Arrays.asList( botentry.askCommands() ).contains( command ) )
                            throw new IllegalStateException( "You can't have instruction commands be the same as ask commands" );
                    }
                }

                Managers.TELEGRAM.addBot( new TelegramBotEntry( key, ask, instructions ) );
            }

            String[] imagemodels = getArray( "gemini.images.models" );
            String[] textmodels = getArray( "gemini.text.models" );

            for( String model : imagemodels )
            {
                if( Arrays.stream( LLM.values() ).noneMatch( m ->
                        m.getCode().equals( model ) ) )
                {
                    throw new IllegalStateException( "Unknown LLM specified in images: " + model );
                }
            }

            for( String model : textmodels )
            {
                if( Arrays.stream( LLM.values() ).noneMatch( m ->
                        m.getCode().equals( model ) ) )
                {
                    throw new IllegalStateException( "Unknown LLM specified in text: " + model );
                }
            }
        }
        catch( Throwable e )
        {
            throw new RuntimeException( "Failed to validate the config", e );
        }
    }

    public boolean getBool( String entry )
    {
        String[] split = entry.split( "\\." );
        YamlMapping followed = follow( split );

        return followed.bool( split[ split.length - 1 ] );
    }

    public String getString( String entry )
    {
        String[] split = entry.split( "\\." );
        YamlMapping followed = follow( split );

        return followed.string( split[ split.length - 1 ] );
    }

    public long getLong( String entry )
    {
        String[] split = entry.split( "\\." );
        YamlMapping followed = follow( split );

        return followed.longNumber( split[ split.length - 1 ] );
    }

    public String[] getArray( String entry )
    {
        String[] split = entry.split( "\\." );
        YamlMapping followed = follow( split );
        YamlSequence sequence = followed.yamlSequence( split[ split.length - 1 ] );

        String[] ret = new String[ sequence.size() ];
        for( int i = 0; i < sequence.size(); i++ )
        {
            String value = Objects.requireNonNull( sequence.string( i ), "value [%s]".formatted( entry ) );
            ret[ i ] = value;
        }

        return ret;
    }

    private YamlMapping follow( String[] split )
    {
        YamlMapping ret = yaml;

        for( int i = 0; i < split.length - 1; i++ )
        {
            String name = split[ i ];

            ret = ret.yamlMapping( name );
            if( ret == null )
            {
                throw new IllegalStateException( "Failed to find \"%s\" in string \"%s\"".formatted(
                        name, String.join( ".", split ) )
                );
            }
        }

        return ret;
    }

    private String[] sequenceAsArray( YamlMapping mapping, String entry )
    {
        YamlSequence sequence = mapping.yamlSequence( entry );

        String[] ret = new String[ sequence.size() ];
        for( int i = 0; i < sequence.size(); i++ )
        {
            String value = Objects.requireNonNull( sequence.string( i ), "value [%s]".formatted( entry ) );
            ret[ i ] = value;
        }

        return ret;
    }
}
