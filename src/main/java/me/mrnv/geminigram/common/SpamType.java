package me.mrnv.geminigram.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum SpamType
{
    TEXT( "antispam.text" ),
    IMAGES( "antispam.images" ),
    VIDEOS( "antispam.videos" );

    private final String configEntry;
}
