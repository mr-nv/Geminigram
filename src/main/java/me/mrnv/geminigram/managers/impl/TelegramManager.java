package me.mrnv.geminigram.managers.impl;

import com.pengrad.telegrambot.Callback;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.*;
import com.pengrad.telegrambot.model.request.ChatAction;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.model.request.ReplyParameters;
import com.pengrad.telegrambot.request.*;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.GetChatMemberResponse;
import com.pengrad.telegrambot.response.GetFileResponse;
import com.pengrad.telegrambot.response.GetMeResponse;
import kotlin.Pair;
import me.mrnv.geminigram.common.AttachmentType;
import me.mrnv.geminigram.common.SpamType;
import me.mrnv.geminigram.common.TelegramBotEntry;
import me.mrnv.geminigram.managers.Managers;
import me.mrnv.geminigram.util.Utils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public class TelegramManager
{
    private final List< TelegramBotEntry > startlist = new ArrayList<>();
    private final List< TelegramBot > bots = new ArrayList<>();

    public void addBot( TelegramBotEntry entry )
    {
        startlist.add( entry );
    }

    public List< TelegramBotEntry > getStartList()
    {
        return startlist;
    }

    public void start()
    {
        boolean reactToImages = Managers.CONFIG.getBool( "telegram.reactToImages" );
        boolean reactToVideos = Managers.CONFIG.getBool( "telegram.reactToVideos" );
        boolean reactToAudio = Managers.CONFIG.getBool( "telegram.reactToAudio" );

        for( TelegramBotEntry entry : startlist )
        {
            try
            {
                TelegramBot bot = new TelegramBot( entry.apiKey() );
                GetMeResponse me = bot.execute( new GetMe() );
                if( !me.isOk() )
                    throw new IllegalStateException( "GetMe returned invalid response, error code " + me.errorCode() );

                List< String > ask = Arrays.asList( entry.askCommands() );
                List< String > instruction = Arrays.asList( entry.instructionCommands() );

                String startcmd = "/start@" + me.user().username();

                bot.setUpdatesListener( updates ->
                {
                    for( Update update : updates )
                    {
                        // check if we were kicked from a group
                        if( update.myChatMember() != null )
                        {
                            ChatMemberUpdated member = update.myChatMember();
                            if( member.chat() == null || member.newChatMember() == null ) continue;

                            ChatMember.Status status = member.newChatMember().status();
                            if( status == ChatMember.Status.kicked || status == ChatMember.Status.left )
                            {
                                // remove instructions from the database
                                Managers.DATABASE.remove( me.user().id(), member.chat().id() );
                            }
                            else if( status == ChatMember.Status.member )
                            {
                                try
                                {
                                    String text = getHelpText( entry );
                                    bot.execute( new SendMessage( member.chat().id(), text ) );
                                }
                                catch( Throwable ignored ) {}
                            }
                        }
                        else if( update.message() != null )
                        {
                            Message message = update.message();
                            if( message.from() == null || message.chat() == null ) continue;

                            // check if the message came from a (super)group
                            Chat chat = message.chat();
                            if( chat.type() == Chat.Type.group ||
                                chat.type() == Chat.Type.supergroup )
                            {
                                User from = message.from();

                                String text;
                                if( message.photo() != null || message.video() != null || message.audio() != null )
                                    text = message.caption();
                                else
                                    text = message.text();

                                String[] split = text == null ? null : text.split( " " );

                                // start
                                if( text != null && text.startsWith( startcmd ) )
                                {
                                    try
                                    {
                                        String help = getHelpText( entry );
                                        bot.execute( new SendMessage( chat.id(), help ) );
                                    }
                                    catch( Throwable ignored ) {}

                                    continue;
                                }

                                // instruction command handling
                                if( text != null && checkCommand( me, split, instruction ) )
                                {
                                    if( from.isBot() ) continue;

                                    try
                                    {
                                        String finalText1 = text;
                                        bot.execute( new GetChatMember( chat.id(), from.id() ),
                                                new Callback< GetChatMember, GetChatMemberResponse >()
                                                {
                                                    @Override
                                                    public void onResponse( GetChatMember request, GetChatMemberResponse response )
                                                    {
                                                        if( !response.isOk() || response.chatMember() == null ) return;

                                                        ChatMember.Status status = response.chatMember().status();
                                                        boolean admin = status == ChatMember.Status.creator || status == ChatMember.Status.administrator;
                                                        if( status == ChatMember.Status.administrator )
                                                        {
                                                            // this is null for creators of the group as they have all permissions
                                                            // check for an actual admin permission
                                                            if( !response.chatMember().canDeleteMessages() )
                                                                admin = false;
                                                        }

                                                        if( !admin )
                                                        {
                                                            reply( bot, "Only admins can set instructions", message );
                                                            return;
                                                        }

                                                        String[] split = finalText1.split( " " );
                                                        if( split.length == 1 )
                                                        {
                                                            Managers.DATABASE.remove( me.user().id(), chat.id() );
                                                            reply( bot, "Instructions cleared, not responding anymore", message );
                                                        }
                                                        else
                                                        {
                                                            split = Arrays.copyOfRange( split, 1, split.length );

                                                            String instructions = String.join( " ", split );

                                                            if( instructions.length() < 1000 )
                                                            {
                                                                try
                                                                {
                                                                    Managers.DATABASE.put( me.user().id(), chat.id(), instructions.getBytes( StandardCharsets.UTF_8 ) );
                                                                    reply( bot, "Instructions set", message );
                                                                }
                                                                catch( Throwable ignored )
                                                                {
                                                                    reply( bot, "Error", message );
                                                                }
                                                            }
                                                            else
                                                            {
                                                                reply( bot, "Instructions must be shorter than 1000 characters", message );
                                                            }
                                                        }
                                                    }

                                                    @Override
                                                    public void onFailure( GetChatMember request, IOException e ) {}
                                                }
                                        );
                                    }
                                    catch( Throwable ignored ) {}

                                    continue;
                                }

                                // check whether we should reply
                                byte[] instructions = Managers.DATABASE.get( me.user().id(), chat.id() );
                                if( instructions == null ) continue;

                                boolean textcmd = text != null && checkCommand( me, split, ask );
                                if( textcmd )
                                {
                                    if( split.length == 1 )
                                        text = null;
                                    else
                                    {
                                        split = Arrays.copyOfRange( split, 1, split.length );
                                        text = String.join( " ", split );
                                    }
                                }

                                boolean hasimage = reactToImages && message.photo() != null && message.mediaGroupId() == null;
                                if( hasimage )
                                {
                                    if( !Managers.SPAM.check( me.user().id(), chat.id(), SpamType.IMAGES ) ) continue;

                                    String finalText2 = text;
                                    downloadAttachment( bot, message.photo(), bytes ->
                                    {
                                        Managers.GEMINI.reply( bot, me, message, finalText2, instructions,
                                                new Pair<>( AttachmentType.IMAGE, bytes ) );
                                    } );

                                    continue;
                                }

                                boolean hasvideo = reactToVideos && message.video() != null && message.mediaGroupId() == null;
                                if( hasvideo )
                                {
                                    Video video = message.video();
                                    if( video.duration() <= 30 ) // only work with short videos (30 seconds)
                                    {
                                        if( !Managers.SPAM.check( me.user().id(), chat.id(), SpamType.VIDEOS ) ) continue;

                                        String finalText2 = text;
                                        downloadAttachment( bot, video, bytes ->
                                        {
                                            Managers.GEMINI.reply( bot, me, message, finalText2, instructions,
                                                    new Pair<>( AttachmentType.VIDEO, bytes ) );
                                        } );
                                    }

                                    continue;
                                }

                                boolean hasaudio = reactToAudio && message.audio() != null;
                                if( hasaudio )
                                {
                                    try
                                    {
                                        Audio audio = message.audio();
                                        if( audio.duration() != null && audio.duration() <= 300 ) // 5 minutes is the max length
                                        {
                                            if( !Managers.SPAM.check( me.user().id(), chat.id(), SpamType.AUDIO ) ) continue;

                                            String finalText2 = text;
                                            downloadAttachment( bot, audio, bytes ->
                                            {
                                                Managers.GEMINI.reply( bot, me, message, finalText2, instructions,
                                                        new Pair<>( AttachmentType.AUDIO, bytes ) );
                                            } );
                                        }
                                    }
                                    catch( Throwable ignored ) {}
                                }

                                if( textcmd && text != null )
                                {
                                    if( !Managers.SPAM.check( me.user().id(), chat.id(), SpamType.TEXT ) ) continue;

                                    sendTyping( bot, chat );
                                    Managers.GEMINI.reply( bot, me, message, text, instructions, null );
                                }
                            }
                            else if( chat.type() == Chat.Type.Private )
                            {
                                try
                                {
                                    bot.execute( new SendMessage( chat.id(), "This bot only works in groups" ) );
                                }
                                catch( Throwable ignored ) {}
                            }
                        }
                    }

                    return UpdatesListener.CONFIRMED_UPDATES_ALL;
                }, e ->
                {
                    // dont care
                } );

                bots.add( bot );
            }
            catch( Throwable e )
            {
                throw new RuntimeException( "Failed to start Telegram bot", e );
            }
        }
    }

    public void shutdown()
    {
        for( TelegramBot bot : bots )
        {
            try
            {
                bot.removeGetUpdatesListener();
            }
            catch( Throwable ignored ) {}

            try
            {
                bot.shutdown();
            }
            catch( Throwable ignored ) {}
        }
    }

    public void reply( TelegramBot bot, String text, Message message )
    {
        List< String > chunks = Utils.splitChunks( text, 4050 );
        for( int i = 0; i < chunks.size(); i++ )
        {
            try
            {
                String chunk = chunks.get( i );

                // only reply to someone when sending the first chunk
                // other chunks will be sent as normal messages
                boolean reply = i == 0;

                String markdown = chunk.replaceAll( "([\\.\\!\\-\\(\\)\"])", "\\\\$1" );

                SendMessage send = new SendMessage( message.chat().id(), markdown )
                        .parseMode( ParseMode.MarkdownV2 );

                if( reply )
                    send.setReplyParameters( new ReplyParameters( message.messageId() ) );

                if( !bot.execute( send ).isOk() )
                {
                    // send without markdown
                    send = new SendMessage( message.chat().id(), cleanResponse( chunk ) );

                    if( reply )
                        send.setReplyParameters( new ReplyParameters( message.messageId() ) );

                    bot.execute( send );
                }
            }
            catch( Throwable ignored ) {}
        }
    }

    private String cleanResponse( String text )
    {
        String ret = text;
        if( ret.startsWith( "*" ) )
            ret = ret.substring( 1 );

        if( ret.endsWith( "*" ) )
            ret = ret.substring( 0, ret.length() - 1 );

        ret = ret.replace( "* *", " " );

        return ret;
    }

    private void downloadAttachment( TelegramBot bot, Object attachment, Consumer< byte[] > callback )
    {
        try
        {
            String fileid = null;

            if( attachment instanceof PhotoSize[] photos )
            {
                // find the biggest photo
                PhotoSize pick = photos[ 0 ];
                for( PhotoSize photo : photos )
                {
                    if( photo.width() > pick.width() )
                        pick = photo;
                }

                fileid = pick.fileId();
            }
            else if( attachment instanceof Video video )
            {
                // limit to mp4 videos only
                if( video.mimeType() == null || !"video/mp4".equals( video.mimeType() ) )
                    return;

                fileid = video.fileId();
            }
            else if( attachment instanceof Audio audio )
            {
                // mp3 files only
                if( audio.mimeType() == null || !"audio/mpeg".equals( audio.mimeType() ) )
                    return;

                fileid = audio.fileId();
            }

            // shouldnt happen
            if( fileid == null ) return;

            bot.execute( new GetFile( fileid ),
                    new Callback< GetFile, GetFileResponse >()
                    {
                        @Override
                        public void onResponse( GetFile request, GetFileResponse response )
                        {
                            if( !response.isOk() || response.file() == null ) return;

                            try
                            {
                                String url = bot.getFullFilePath( response.file() );

                                try( HttpClient client = HttpClient.newHttpClient() )
                                {
                                    HttpRequest httpreq = HttpRequest.newBuilder()
                                            .GET()
                                            .uri( URI.create( url ) )
                                            .build();

                                    HttpResponse< byte[] > httpres = client.send( httpreq, HttpResponse.BodyHandlers.ofByteArray() );
                                    if( httpres.statusCode() == 200 )
                                    {
                                        byte[] bytes = httpres.body();
                                        callback.accept( bytes );
                                    }
                                }
                            }
                            catch( Throwable ignored ) {}
                        }

                        @Override
                        public void onFailure( GetFile request, IOException e ) {}
                    }
            );
        }
        catch( Throwable ignored ) {}
    }

    public void sendTyping( TelegramBot bot, Chat chat )
    {
        try
        {
            bot.execute( new SendChatAction( chat.id(), ChatAction.typing ),
                    new Callback< SendChatAction, BaseResponse >()
                    {
                        @Override
                        public void onResponse( SendChatAction request, BaseResponse response ) {}

                        @Override
                        public void onFailure( SendChatAction request, IOException e ) {}
                    }
            );
        }
        catch( Throwable ignored ) {}
    }

    private boolean checkCommand( GetMeResponse me, String[] split, List< String > commands )
    {
        for( String cmd : commands )
        {
            String cmduser = "%s@%s".formatted( cmd, me.user().username() );

            if( split[ 0 ].equals( cmd ) || split[ 0 ].equals( cmduser ) )
                return true;
        }

        return false;
    }

    private String getHelpText( TelegramBotEntry entry )
    {
        return """
                Available commands for this bot:
                
                %s - send a prompt to Gemini
                %s - set instructions (chat admins only) (don't provide anything to clear instructions)
                
                The bot will be inactive in this chat without instructions set."""
                .formatted( listCommands( entry.askCommands() ), listCommands( entry.instructionCommands() ) );
    }

    private String listCommands( String[] commands )
    {
        return String.join( ", ", commands ).replaceFirst(
                "(?s)(.*), ", "$1 or "
        );
    }
}
