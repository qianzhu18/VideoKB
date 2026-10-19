package com.example.server.controller;

import com.example.server.exception.VideoDownloadException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiExceptionHandlerTest {
    @Test
    void multipartAndContentTypeErrorsUseActionable4xxResponsesInsteadOfRetriable500() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new UploadController())
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(multipart("/upload")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("code").value(40000));
        mvc.perform(get("/oversized")).andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("code").value(41300));
        mvc.perform(post("/json").contentType(MediaType.TEXT_PLAIN).content("text"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("code").value(41500));
    }

    @Test
    void youtubeAuthenticationChallengeReturnsAnActionableServiceUnavailableResponse() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new VideoController())
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/video-url"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("code").value(50300))
                .andExpect(jsonPath("message").value("该视频平台要求登录验证，当前无法直接解析这个链接。请改用“本地视频”上传，或稍后重试。"));
    }

    @Test
    void slowVideoDownloadReturnsAUsefulTimeoutMessage() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new TimeoutController())
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/video-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("code").value(50300))
                .andExpect(jsonPath("message").value("视频源下载较慢，等待时间已超出限制。请稍后重试，或下载后改用“本地视频”上传。"));
    }

    @RestController
    static class VideoController {
        @GetMapping("/video-url") String download() {
            throw new VideoDownloadException(VideoDownloadException.Reason.AUTHENTICATION_REQUIRED,
                    "Sign in to confirm you’re not a bot");
        }
    }

    @RestController
    static class TimeoutController {
        @GetMapping("/video-timeout") String download() {
            throw new VideoDownloadException(VideoDownloadException.Reason.DOWNLOAD_TIMEOUT,
                    "视频链接下载超时");
        }
    }

    @RestController
    static class UploadController {
        @PostMapping("/upload") String upload(@RequestPart("file") MultipartFile file) { return "ok"; }
        @GetMapping("/oversized") String oversized() { throw new MaxUploadSizeExceededException(10); }
        @PostMapping(value = "/json", consumes = "application/json") String json() { return "ok"; }
    }
}
