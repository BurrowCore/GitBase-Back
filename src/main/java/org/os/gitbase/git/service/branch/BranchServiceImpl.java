package org.os.gitbase.git.service.branch;


import org.os.gitbase.git.dto.BranchDto;
import org.os.gitbase.git.entity.Branch;
import org.os.gitbase.git.entity.Commit;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.repository.BranchRepository;
import org.os.gitbase.git.repository.CommitRepository;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.git.service.GitStorageService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class BranchServiceImpl implements BranchService {
    private final BranchRepository branchRepository;
    private final GitRepositoryDB repositoryGitRepository;
    private final CommitRepository commitRepository;
    private final GitStorageService gitStorageService;
    public BranchServiceImpl(BranchRepository branchRepository, GitRepositoryDB repositoryGitRepository, CommitRepository commitRepository, GitStorageService gitStorageService) {
        this.branchRepository = branchRepository;
        this.repositoryGitRepository = repositoryGitRepository;
        this.commitRepository = commitRepository;
        this.gitStorageService = gitStorageService;
    }

    @Override
    public List<Branch> listBranches(UUID repositoryId) throws Exception {
        RepositoryGit repo = repositoryGitRepository.findById(repositoryId)
                .orElseThrow(() -> new Exception("Repository not found"));
        return branchRepository.findByRepository(repo);
    }

    @Override
    public Branch getBranch(UUID repositoryId, String branchName) throws Exception {
        RepositoryGit repo = repositoryGitRepository.findById(repositoryId)
                .orElseThrow(() -> new Exception("Repository not found"));
        return branchRepository.findByNameAndRepository(branchName, repo)
                .orElseThrow(() -> new Exception("Branch not found"));
    }

    @Override
    public Branch createBranch(UUID repositoryId, BranchDto dto) throws Exception {
        RepositoryGit repo = repositoryGitRepository.findById(repositoryId)
                .orElseThrow(() -> new Exception("Repository not found"));

        if (branchRepository.findByNameAndRepository(dto.getName(), repo).isPresent()) {
            throw new Exception("Branch already exists: " + dto.getName());
        }

        Branch branch = new Branch();
        branch.setName(dto.getName());
        branch.setRepository(repo);

        if (dto.getHeadCommitId() != null) {
            Commit head = commitRepository.findById(dto.getHeadCommitId())
                    .orElseThrow(() -> new Exception("Head commit not found"));
            branch.setCommit(head);
        }

        Branch saved = branchRepository.save(branch);

        // optionally update underlying storage ref
        if (gitStorageService != null) {
            try {
                gitStorageService.updateBranchRef(repo.getId(), saved.getName(), saved.getCommit() != null ? saved.getCommit().getCommitHash() : null);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create branch on storage: " + e.getMessage(), e);
            }
        }

        return saved;
    }

    @Override
    public Branch updateBranchHead(UUID repositoryId, String branchName, UUID newCommitId) throws Exception {
        RepositoryGit repo = repositoryGitRepository.findById(repositoryId)
                .orElseThrow(() -> new Exception("Repository not found"));
        Branch branch = branchRepository.findByNameAndRepository(branchName, repo)
                .orElseThrow(() -> new Exception("Branch not found"));

        Commit newHead = commitRepository.findById(newCommitId)
                .orElseThrow(() -> new Exception("Commit not found"));

        branch.setCommit(newHead);
        Branch saved = branchRepository.save(branch);

        // update underlying storage
        if (gitStorageService != null) {
            try {
                gitStorageService.updateBranchRef(repo.getId(), branchName, newHead.getCommitHash());
            } catch (Exception e) {
                throw new RuntimeException("Failed to update branch ref on storage: " + e.getMessage(), e);
            }
        }

        return saved;
    }

    @Override
    public Branch pushCommitToBranch(UUID repositoryId, String branchName, Commit commit) throws Exception {
        RepositoryGit repo = repositoryGitRepository.findById(repositoryId)
                .orElseThrow(() -> new Exception("Repository not found"));
        Branch branch = branchRepository.findByNameAndRepository(branchName, repo)
                .orElseThrow(() -> new Exception("Branch not found"));

        // persist commit if not already persisted
        if (commit.getId() == null) {
            commit.setRepository(repo);
            commit = commitRepository.save(commit);
        }

        branch.setCommit(commit);
        Branch saved = branchRepository.save(branch);

        if (gitStorageService != null) {
            try {
                gitStorageService.updateBranchRef(repo.getId(), branchName, commit.getCommitHash());
            } catch (Exception e) {
                throw new RuntimeException("Failed to update branch ref on storage: " + e.getMessage(), e);
            }
        }

        return saved;
    }
}
