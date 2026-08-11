package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
public class GitTokenInfo {
    private Long id;
    private String name;
    private String scopes;
    private String createdAt;
    private String expiresAt;
}
