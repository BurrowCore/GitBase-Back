package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * The content of a single file (git blob) at a given ref.
 *
 * <p>For text files, {@code content} holds the UTF-8 decoded text and {@code binary} is false.
 * For binary files, {@code content} is {@code null} and {@code binary} is true — the client
 * should offer a download instead of rendering.
 */
@Getter
@Setter
public class FileContentDto {
    private String path;
    private String ref;
    private long size;       // bytes
    private boolean binary;
    private String content;  // null when binary

    public FileContentDto(String path, String ref, long size, boolean binary, String content) {
        this.path = path;
        this.ref = ref;
        this.size = size;
        this.binary = binary;
        this.content = content;
    }
}
