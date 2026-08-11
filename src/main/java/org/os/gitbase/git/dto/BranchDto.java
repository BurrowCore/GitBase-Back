package org.os.gitbase.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class BranchDto {
    @NotBlank
    @Size(max = 255)
    private String name;

    // optional head commit hash -> if null, will point to repository default branch head or provided commitId
    private UUID headCommitId;
}
