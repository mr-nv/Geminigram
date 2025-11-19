package me.mrnv.geminigram.managers.impl;

import com.google.common.collect.ImmutableList;
import com.google.genai.Client;
import com.google.genai.types.*;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.response.GetMeResponse;
import kotlin.Pair;
import me.mrnv.geminigram.Main;
import me.mrnv.geminigram.common.AttachmentType;
import me.mrnv.geminigram.common.LLM;
import me.mrnv.geminigram.common.LLMRates;
import me.mrnv.geminigram.common.SpamType;
import me.mrnv.geminigram.managers.Managers;
import me.mrnv.geminigram.util.Utils;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

public class GeminiManager extends Thread
{
    private final String API_KEY = Managers.CONFIG.getString( "gemini.apiKey" );

    private final String[] textmodels = Managers.CONFIG.getArray( "gemini.text.models" );
    private final String[] imagemodels = Managers.CONFIG.getArray( "gemini.images.models" );
    private final String[] videomodels = Managers.CONFIG.getArray( "gemini.videos.models" );
    private final String[] audiomodels = Managers.CONFIG.getArray( "gemini.audio.models" );

    private final String textthink = Managers.CONFIG.getString( "gemini.text.thinking.enabled" );
    private final String imagethink = Managers.CONFIG.getString( "gemini.images.thinking.enabled" );
    private final String videothink = Managers.CONFIG.getString( "gemini.videos.thinking.enabled" );
    private final String audiothink = Managers.CONFIG.getString( "gemini.audio.thinking.enabled" );

    private final String textbudget = Managers.CONFIG.getString( "gemini.text.thinking.budget" );
    private final String imagebudget = Managers.CONFIG.getString( "gemini.images.thinking.budget" );
    private final String videobudget = Managers.CONFIG.getString( "gemini.videos.thinking.budget" );
    private final String audiobudget = Managers.CONFIG.getString( "gemini.audio.thinking.budget" );

    private final BlockingQueue< Runnable > queue = new LinkedBlockingQueue<>();

    @Override
    public void run()
    {
        long lastcall = 0;

        while( !Main.SHUTDOWN )
        {
            try
            {
                Runnable task = queue.take();
                long diff = 600 - ( System.currentTimeMillis() - lastcall );
                if( diff > 0 )
                    Utils.sleep( diff );

                lastcall = System.currentTimeMillis();

                task.run();
            }
            catch( Throwable ignored ) {}
        }
    }

    public void reply( TelegramBot bot, GetMeResponse me, Message message, String text,
                       byte[] instructions, @Nullable Pair< AttachmentType, byte[] > attachment )
    {
        queue.add( () -> internal( bot, me, message, text, instructions, attachment, null ) );
    }

    private void internal( TelegramBot bot, GetMeResponse me, Message message, String text,
                          byte[] instructions, @Nullable Pair< AttachmentType, byte[] > attachment, @Nullable LLMRates ratelimiter )
    {
        String[] models;
        SpamType spamtype;

        if( attachment == null )
        {
            models = textmodels;
            spamtype = SpamType.TEXT;
        }
        else
        {
            models = switch( attachment.component1() )
            {
                case IMAGE -> imagemodels;
                case VIDEO -> videomodels;
                case AUDIO -> audiomodels;
            };

            spamtype = switch( attachment.component1() )
            {
                case IMAGE -> SpamType.IMAGES;
                case VIDEO -> SpamType.VIDEOS;
                case AUDIO -> SpamType.AUDIO;
            };
        }

        boolean canretry = ratelimiter == null;
        List< LLMRates > list;
        if( ratelimiter == null )
        {
            // get available models, then get a random model
            list = Managers.MODELS.getAvailableModels( models );
            if( list == null ) return;

            ratelimiter = list.get( ThreadLocalRandom.current().nextInt( list.size() ) );
        }
        else
        {
            list = null;
        }

        LLM model = ratelimiter.getModel();

        AttachmentType attach = attachment != null ? attachment.component1() : null;

        boolean hasimage = attachment != null &&
                attachment.component1() == AttachmentType.IMAGE;

        boolean hasvideo = attachment != null &&
                attachment.component1() == AttachmentType.VIDEO;

        boolean hasaudio = attachment != null &&
                attachment.component1() == AttachmentType.AUDIO;

        boolean think = model.isCanThink();
        if( think )
            think = shouldThink( attach );

        // force thinking
        if( attachment == null && model == LLM.GEMINI_2_5_FLASH )
            think = true;
        
        // cant disable thinking for Gemini 2.5 Pro
        if( model == LLM.GEMINI_2_5_PRO )
            think = true;

        // disable thinking for text only prompts using Gemini 2.5 Flash Lite
        if( attachment == null && model == LLM.GEMINI_2_5_FLASH_LITE )
            think = false;

        try
        {
            // text prompts already send the typing action
            if( attach != null )
                Managers.TELEGRAM.sendTyping( bot, message.chat() );

            ratelimiter.setTime( System.currentTimeMillis() );
            ratelimiter.increaseCount( 1 );

            try( Client client = getClient() )
            {
                ImmutableList< SafetySetting > safety = ImmutableList.of(
                        SafetySetting.builder()
                                .category( HarmCategory.Known.HARM_CATEGORY_HARASSMENT )
                                .threshold( HarmBlockThreshold.Known.BLOCK_NONE )
                                .build(),
                        SafetySetting.builder()
                                .category( HarmCategory.Known.HARM_CATEGORY_HATE_SPEECH )
                                .threshold( HarmBlockThreshold.Known.BLOCK_NONE )
                                .build(),
                        SafetySetting.builder()
                                .category( HarmCategory.Known.HARM_CATEGORY_CIVIC_INTEGRITY )
                                .threshold( HarmBlockThreshold.Known.BLOCK_NONE )
                                .build()
                );

                Content inst = Content.fromParts(
                        Part.fromText( new String( instructions, StandardCharsets.UTF_8 ) )
                );

                List< Part > parts = new ArrayList<>();

                if( text != null )
                    parts.add( Part.fromText( text ) );

                if( hasimage )
                    parts.add( Part.fromBytes( attachment.component2(), "image/jpeg" ) );

                if( hasvideo )
                    parts.add( Part.fromBytes( attachment.component2(), "video/mp4" ) );

                if( hasaudio )
                    parts.add( Part.fromBytes( attachment.component2(), "audio/mp3" ) );

                int budget = getThinkingBudget( think, model, attach );

                Content content = Content.fromParts( parts.toArray( new Part[ 0 ] ) );
                GenerateContentConfig cfg = GenerateContentConfig.builder()
                        .thinkingConfig( ThinkingConfig.builder().thinkingBudget( budget ) )
                        .candidateCount( 1 )
                        .systemInstruction( inst )
                        .safetySettings( safety )
                        .build();

                CompletableFuture< GenerateContentResponse > future =
                        client.async.models.generateContent( model.getCode(), content, cfg );

                LLMRates finalRatelimiter = ratelimiter;
                future.exceptionally( e ->
                {
                    if( canretry && e.getMessage() != null && e.getMessage().contains( "overloaded" ) )
                    {
                        finalRatelimiter.decreaseCount( 1 );
                        list.remove( finalRatelimiter );

                        if( !list.isEmpty() )
                        {
                            LLMRates random = list.get( ThreadLocalRandom.current().nextInt( list.size() ) );
                            queue.add( () -> internal( bot, me, message, text, instructions, attachment, random ) );
                        }
                    }

                    return null;
                } ).thenAccept( response ->
                {
                    if( response != null && response.text() != null )
                    {
                        Managers.SPAM.forceUpdateTime( me.user().id(), message.chat().id(), spamtype );

                        String send = response.text();
                        if( Managers.CONFIG.getBool( "telegram.debug" ) )
                        {
                            send += "\n\n%s - %d - %d".formatted(
                                    model.getCode(), budget, list != null ? list.size() : -1
                            );
                        }

                        Managers.TELEGRAM.reply( bot, send, message );
                    }
                } );
            }
        }
        catch( Throwable e )
        {
            Managers.TELEGRAM.reply( bot, "Error: " + e.getMessage(), message );
        }
    }

    private Client getClient()
    {
        return Client.builder()
                .apiKey( API_KEY )
                .vertexAI( false )
                .httpOptions( HttpOptions.builder().apiVersion( "v1alpha" ).build() )
                .build();
    }

    private boolean shouldThink( AttachmentType attachment )
    {
        String str;
        if( attachment == null )
            str = textthink;
        else
        {
            str = switch( attachment )
            {
                case IMAGE -> imagethink;
                case VIDEO -> videothink;
                case AUDIO -> audiothink;
            };
        }

        return switch( str )
        {
            case "true" -> true;
            case "random" -> ThreadLocalRandom.current().nextBoolean();
            default -> false; // and case "false"
        };
    }

    private int getThinkingBudget( boolean shouldthink, LLM model, AttachmentType attachment )
    {
        if( !shouldthink ) return 0; // dont think

        String value;
        if( attachment == null )
            value = textbudget;
        else
        {
            value = switch( attachment )
            {
                case IMAGE -> imagebudget;
                case VIDEO -> videobudget;
                case AUDIO -> audiobudget;
            };
        }

        if( "auto".equalsIgnoreCase( value ) )
            return -1;

        try
        {
            int ret = Integer.parseInt( value );
            if( ret < -1 )
                ret = -1;
            else if( ret > 0 )
                ret = Math.max( ret, model.getMinimumThinkingBudget() );

            return ret;
        }
        catch( Throwable ignored )
        {
            return -1;
        }
    }
}
