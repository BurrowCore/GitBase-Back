package org.os.gitbase.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class CommitDto {
    @NotBlank
    @Size(max = 4000)
    private String message;

    // Author user id (UUID)
    private UUID authorId;

    // List of parent commit IDs (for merge create two parents)
    private List<UUID> parentIds;

    // Optional content hash or tree hash produced by actual Git plumbing
    private String commitHash;
}

