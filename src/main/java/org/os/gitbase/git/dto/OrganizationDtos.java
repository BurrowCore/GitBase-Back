package org.os.gitbase.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Request and response shapes for organizations and teams. */
public final class OrganizationDtos {

    private OrganizationDtos() {
    }

    /** Organization profile, with what the viewer may do there. */
    public record OrganizationDto(String name, String fullName, String description, Long createdAt,
                                  long repositoryCount, boolean viewerIsMember, boolean viewerIsOwner,
                                  boolean viewerCanCreateRepository) {}

    /** Row in "your organizations". */
    public record OrganizationSummaryDto(String name, String fullName, String description,
                                         boolean viewerIsOwner, boolean viewerCanCreateRepository) {}

    /**
     * New organization. The name joins the username namespace (letters, digits, {@code . _ -},
     * starting with a letter or digit, at most 39 characters — GitHub's limit) and is stored lowercase.
     */
    public record CreateOrganizationDto(
            @NotBlank(message = "name is required")
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,38}$",
                    message = "name may contain letters, digits, '.', '_' and '-', start with a letter or digit, and be at most 39 characters")
            String name,
            @Size(max = 255) String fullName,
            @Size(max = 500) String description) {}

    /** Null fields are left unchanged. The name cannot change — it is the repositories' path. */
    public record UpdateOrganizationDto(@Size(max = 255) String fullName, @Size(max = 500) String description) {}

    public record TeamDto(String name, String description, String permission, boolean includesAllRepositories,
                          boolean canCreateOrgRepo, boolean ownerTeam, long membersCount, long repositoriesCount) {}

    public record CreateTeamDto(
            @NotBlank(message = "name is required")
            @Size(max = 100)
            @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "team name may contain letters, digits, '.', '_' and '-'")
            String name,
            @Size(max = 500) String description,
            @Pattern(regexp = "READ|WRITE|ADMIN", message = "permission must be READ, WRITE or ADMIN")
            String permission,
            boolean includesAllRepositories,
            boolean canCreateOrgRepo) {}

    /** Null fields are left unchanged. The Owners team accepts only a description change. */
    public record UpdateTeamDto(
            @Size(max = 100)
            @Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "team name may contain letters, digits, '.', '_' and '-'")
            String name,
            @Size(max = 500) String description,
            @Pattern(regexp = "READ|WRITE|ADMIN", message = "permission must be READ, WRITE or ADMIN")
            String permission,
            Boolean includesAllRepositories,
            Boolean canCreateOrgRepo) {}

    /** A repository a team can reach; {@code visibility} is "public" or "private". */
    public record TeamRepositoryDto(String name, String visibility) {}

    /** One page of anything. */
    public record PageDto<T>(List<T> items, int page, int size, long totalElements, boolean hasNext) {}
}
