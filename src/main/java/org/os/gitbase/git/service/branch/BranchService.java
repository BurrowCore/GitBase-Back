package org.os.gitbase.git.service.branch;

import org.os.gitbase.git.dto.BranchDto;
import org.os.gitbase.git.entity.Branch;
import org.os.gitbase.git.entity.Commit;

import java.util.List;
import java.util.UUID;

public interface BranchService {
    List<Branch> listBranches(UUID repositoryId) throws Exception;

    Branch getBranch(UUID repositoryId, String branchName) throws Exception;


    Branch createBranch(UUID repositoryId, BranchDto dto) throws Exception;


    Branch updateBranchHead(UUID repositoryId, String branchName, UUID newCommitId) throws Exception;


    Branch pushCommitToBranch(UUID repositoryId, String branchName, Commit commit) throws Exception;
}
