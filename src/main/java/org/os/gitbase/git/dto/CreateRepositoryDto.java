package org.os.gitbase.git.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CreateRepositoryDto {

    @NotBlank(message = "username is required")
    private String username;

    @NotBlank(message = "repoName is required")
    @Size(max = 100, message = "repoName must be at most 100 characters")
    @Pattern(regexp = "^[a-zA-Z0-9._-]+$",
            message = "repoName may only contain letters, numbers, dots, hyphens and underscores")
    private String repoName;

    @JsonProperty("isPrivate")
    private boolean isPrivate;

    private String defaultBranch;
}
