package me.mrnv.geminigram.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

// https://ai.google.dev/gemini-api/docs/rate-limits#current-rate-limits

@Getter
@AllArgsConstructor
public enum LLM
{
    GEMINI_2_5_PRO( "gemini-2.5-pro", 5, 100, true, 128 ),
    GEMINI_2_5_FLASH( "gemini-2.5-flash", 10, 250, true, 1 ),
    GEMINI_2_5_FLASH_LITE( "gemini-2.5-flash-lite", 15, 1000, true, 512 ),
    GEMINI_2_0_FLASH( "gemini-2.0-flash", 15, 200, false, 0 ),
    GEMINI_2_0_FLASH_LITE( "gemini-2.0-flash-lite", 30, 200, false, 0 );

    private final String code;
    private final int maxRequestsPerMinute;
    private final int maxRequestsPerDay;
    private final boolean canThink;
    private final int minimumThinkingBudget;
}
