package me.mrnv.geminigram.managers.impl;

import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

import java.sql.*;

// database for storing instructions
public class DatabaseManager
{
    private final Connection connection;

    public DatabaseManager()
    {
        try
        {
            Class.forName( "org.sqlite.JDBC" );
        }
        catch( Throwable ignored )
        {
            throw new RuntimeException( "SQLite driver not loaded" );
        }

        try
        {
            SQLiteConfig config = new SQLiteConfig();
            config.setOpenMode( SQLiteOpenMode.FULLMUTEX );

            connection = DriverManager.getConnection( "jdbc:sqlite:instructions.db", config.toProperties() );
            connection.setAutoCommit( true );

            try( Statement statement = connection.createStatement() )
            {
                statement.execute( "CREATE TABLE IF NOT EXISTS instructions (chat LONG, bot LONG, data BLOB, PRIMARY KEY (chat, bot))" );
                statement.execute( "PRAGMA journal_mode = WAL;" );
            }
        }
        catch( Throwable e )
        {
            throw new RuntimeException( "Failed to initialize the database manager", e );
        }
    }

    public void put( long bot, long chat, byte[] value ) throws SQLException
    {
        synchronized( connection )
        {
            try( PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR REPLACE INTO instructions (chat, bot, data) VALUES (?, ?, ?)" ) )
            {
                statement.setLong( 1, chat );
                statement.setLong( 2, bot );
                statement.setBytes( 3, value );
                statement.executeUpdate();
            }
        }
    }

    public byte[] get( long bot, long chat )
    {
        try
        {
            synchronized( connection )
            {
                try( PreparedStatement statement = connection.prepareStatement(
                        "SELECT data FROM instructions WHERE chat = ? AND bot = ?" ) )
                {
                    statement.setLong( 1, chat );
                    statement.setLong( 2, bot );
                    try( ResultSet set = statement.executeQuery() )
                    {
                        if( set.next() )
                            return set.getBytes( "data" );
                    }
                }
            }
        }
        catch( Throwable ignored ) {}

        return null;
    }

    public void remove( long bot, long chat )
    {
        try
        {
            synchronized( connection )
            {
                try( PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM instructions WHERE chat = ? AND bot = ?" ) )
                {
                    statement.setLong( 1, chat );
                    statement.setLong( 2, bot );
                    statement.executeUpdate();
                }
            }
        }
        catch( Throwable ignored ) {}
    }

    public void shutdown()
    {
        synchronized( connection )
        {
            try
            {
                if( !connection.isClosed() )
                    connection.close();
            }
            catch( Throwable ignored ) {}
        }
    }
}
