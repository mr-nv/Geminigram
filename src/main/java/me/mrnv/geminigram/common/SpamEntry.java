package me.mrnv.geminigram.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@RequiredArgsConstructor
@Getter
@Setter
public class SpamEntry
{
    private final long chat;
    private long time;
}
