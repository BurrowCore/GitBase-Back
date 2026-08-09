package org.os.gitbase.git.dto;

/**
 * Raw bytes of a single blob, ready to be streamed to the client.
 *
 * @param path        the file path inside the repository
 * @param content     the blob's raw bytes
 * @param contentType the MIME type to serve with
 * @param inline      whether the browser may render it inline ({@code true}) or must download it
 *                    ({@code false}). Only an allowlist of raster image types and plain text is
 *                    ever served inline — see {@code GitServiceImpl.rawContentType}.
 */
public record RawFileDto(String path, byte[] content, String contentType, boolean inline) {
}
