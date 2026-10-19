package com.example.server.exception;

/** A video platform refused or could not serve a URL to the server-side downloader. */
public class VideoDownloadException extends RuntimeException {

    public enum Reason {
        AUTHENTICATION_REQUIRED,
        DOWNLOAD_TIMEOUT,
        SOURCE_UNAVAILABLE
    }

    private final Reason reason;

    public VideoDownloadException(Reason reason, String diagnostics) {
        super(diagnostics);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
