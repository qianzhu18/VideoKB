package com.example.server.utils;

import com.example.server.exception.VideoDownloadException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class YtDlpUtilsTest {

    @Test
    void classifiesYoutubeBotChallengeAsAnAuthenticationFailure() {
        assertEquals(VideoDownloadException.Reason.AUTHENTICATION_REQUIRED,
                YtDlpUtils.classifyDownloadFailure("Sign in to confirm you’re not a bot"));
    }

    @Test
    void classifiesOtherDownloadFailuresAsSourceUnavailable() {
        assertEquals(VideoDownloadException.Reason.SOURCE_UNAVAILABLE,
                YtDlpUtils.classifyDownloadFailure("HTTP Error 403: Forbidden"));
    }

    @Test
    void selectedMaximumResolutionIsAppliedToEveryVideoFormatFallback() {
        String selector = YtDlpUtils.formatSelectorFor(360);
        assertEquals(6, selector.split("height<=360", -1).length - 1);
        org.junit.jupiter.api.Assertions.assertFalse(selector.contains("height<=720"));
    }
}
