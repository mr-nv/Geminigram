package me.mrnv.geminigram.util;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Utils
{
    private static final Pattern WORDPATTERN =
            Pattern.compile( "[\\s&&[^\\n\\r\\f\\u000B]]+" );

    public static void sleep( long ms )
    {
        try
        {
            Thread.sleep( ms );
        }
        catch( Throwable ignored ) {}
    }

    public static List< String > splitChunks( String str, int len )
    {
        List< String > ret = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        String[] words = WORDPATTERN.split( str );
        for( String word : words )
        {
            if( ( current.length() + word.length() ) >= len )
            {
                ret.add( current.toString().trim() );
                current = new StringBuilder( word ).append( " " );

                // if we already got 2 full chunks that we can send, return them
                // had a case where Gemini responded with 50000+ characters...
                if( ret.size() == 2 )
                    return ret;
            }
            else
                current.append( word ).append( " " );
        }

        if( !current.isEmpty() )
            ret.add( current.toString().trim() );

        return ret;
    }
}
