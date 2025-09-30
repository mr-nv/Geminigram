package me.mrnv.geminigram.managers;

import me.mrnv.geminigram.managers.impl.*;

public class Managers
{
    public static final ConfigManager CONFIG = new ConfigManager();
    public static TelegramManager TELEGRAM = new TelegramManager();

    public static DatabaseManager DATABASE;
    public static SpamManager SPAM;
    public static LLMManager MODELS;
    public static GeminiManager GEMINI;

    public static void init()
    {
        DATABASE = new DatabaseManager();
        SPAM = new SpamManager();
        MODELS = new LLMManager();

        GEMINI = new GeminiManager();
        GEMINI.start();
    }
}
