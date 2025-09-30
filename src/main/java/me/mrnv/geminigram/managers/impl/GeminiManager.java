package me.mrnv.geminigram.managers.impl;

import com.google.common.collect.ImmutableList;
import com.google.genai.Client;
import com.google.genai.types.*;
import com.pengrad.telegrambot.TelegramBot;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.response.GetMeResponse;
import me.mrnv.geminigram.Main;
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

    private final String textthink = Managers.CONFIG.getString( "gemini.text.thinking.enabled" );
    private final String imagethink = Managers.CONFIG.getString( "gemini.images.thinking.enabled" );

    private final String textbudget = Managers.CONFIG.getString( "gemini.text.thinking.budget" );
    private final String imagebudget = Managers.CONFIG.getString( "gemini.images.thinking.budget" );

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
                       byte[] instructions, @Nullable byte[] image )
    {
        queue.add( () -> internal( bot, me, message, text, instructions, image, null ) );
    }

    private void internal( TelegramBot bot, GetMeResponse me, Message message, String text,
                          byte[] instructions, @Nullable byte[] image, @Nullable LLMRates ratelimiter )
    {
        String[] models = image == null
                ? textmodels
                : imagemodels;

        SpamType spamtype = image == null
                ? SpamType.TEXT
                : SpamType.IMAGES;

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

        boolean think = model.isCanThink();
        if( think )
            think = shouldThink( image != null );

        // force thinking
        if( image == null && model == LLM.GEMINI_2_5_FLASH )
            think = true;
        
        // cant disable thinking for Gemini 2.5 Pro
        if( model == LLM.GEMINI_2_5_PRO )
            think = true;

        // disable thinking for text only prompts using Gemini 2.5 Flash Lite
        if( image == null && model == LLM.GEMINI_2_5_FLASH_LITE )
            think = false;

        try
        {
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

                if( image != null )
                    parts.add( Part.fromBytes( image, "image/jpeg" ) );

                int budget = getThinkingBudget( think, model, image != null );

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
                            queue.add( () -> internal( bot, me, message, text, instructions, image, random ) );
                        }
                    }

                    return null;
                } ).thenAccept( response ->
                {
                    if( response != null && response.text() != null )
                    {
                        Managers.SPAM.forceUpdateTime( me.user().id(), message.chat().id(), spamtype );
                        Managers.TELEGRAM.reply( bot, response.text(), message );
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

    private boolean shouldThink( boolean image )
    {
        String str = image ? imagethink : textthink;

        return switch( str )
        {
            case "true" -> true;
            case "random" -> ThreadLocalRandom.current().nextBoolean();
            default -> false; // and case "false"
        };
    }

    private int getThinkingBudget( boolean shouldthink, LLM model, boolean image )
    {
        if( !shouldthink ) return 0; // dont think

        String value = image ? imagebudget : textbudget;
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
