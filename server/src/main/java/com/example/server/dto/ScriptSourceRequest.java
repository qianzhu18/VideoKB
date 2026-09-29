package com.example.server.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Upload of a Markdown/plain-text script (口播稿/笔记) as a SCRIPT knowledge source.
 */
public record ScriptSourceRequest(
        @Size(max = 255, message = "标题不能超过 255 字") String title,
        Long spaceId,
        Long collectionId,
        @NotBlank(message = "脚本内容不能为空")
        @Size(max = 200_000, message = "脚本内容过大，请分批上传") String content) {

    /** Title defaults to the first non-blank line so casual pastes still get a name. */
    public String effectiveTitle() {
        if (title != null && !title.isBlank()) return title.strip();
        for (String line : content.split("\n", 3)) {
            if (!line.isBlank()) {
                String stripped = line.strip().replaceAll("^#+\\s*", "");
                return stripped.length() > 120 ? stripped.substring(0, 120) : stripped;
            }
        }
        return "未命名脚本";
    }
}
