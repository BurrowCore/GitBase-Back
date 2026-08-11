package org.os.gitbase.git.service.commit;

import org.os.gitbase.git.dto.CommitDto;
import org.os.gitbase.git.entity.Commit;

import java.util.List;
import java.util.UUID;

public interface CommitService {
    List<Commit> listCommits(UUID repositoryId) ;

    Commit getById(UUID commitId) ;

    Commit createCommit(UUID repositoryId, CommitDto dto) ;

    Commit createMergeCommit(UUID repositoryId, UUID authorId, String message, List<UUID> parentIds) ;
}
