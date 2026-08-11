package org.os.gitbase.git.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LogCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefAdvertiser;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.os.gitbase.auth.entity.User;
import org.os.gitbase.auth.repository.UserRepository;
import org.os.gitbase.git.dto.BranchSummaryDto;
import org.os.gitbase.git.dto.CommitDetailDto;
import org.os.gitbase.git.dto.CommitPageDto;
import org.os.gitbase.git.dto.CommitSummaryDto;
import org.os.gitbase.git.dto.CompareDto;
import org.os.gitbase.git.dto.DirEntryDto;
import org.os.gitbase.git.dto.FileDiffDto;
import org.os.gitbase.git.dto.DirectoryListingDto;
import org.os.gitbase.git.dto.FileContentDto;
import org.os.gitbase.git.dto.FileTreeNode;
import org.os.gitbase.git.dto.RawFileDto;
import org.os.gitbase.git.dto.RepositoryInfo;
import org.os.gitbase.git.dto.RepositoryTreeDto;
import org.os.gitbase.git.entity.RepositoryGit;
import org.os.gitbase.git.hook.CodeReviewHook;
import org.os.gitbase.git.repository.GitRepositoryDB;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class GitServiceImpl implements GitService {

    private final String repositoriesPath = "./gitbase/repositories";
    private static final Pattern VALID_REPO_NAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private static final Pattern VALID_USERNAME = Pattern.compile("^[a-zA-Z0-9._-]+$");
    private final GitRepositoryDB gitRepositoryDB;
    private final UserRepository userRepository;
    public GitServiceImpl(GitRepositoryDB gitRepositoryDB, UserRepository userRepository) {
        this.gitRepositoryDB = gitRepositoryDB;
        this.userRepository = userRepository;
    }

    public void createRepository(String user, String repoName, boolean isPrivate) {
        validateUsername(user);
        validateRepositoryName(repoName);

        String repoPath = repositoriesPath + "/" + user + "/" + repoName + ".git";

        try {
            Path repoDir = Paths.get(repoPath);
            Files.createDirectories(repoDir.getParent());

            org.eclipse.jgit.lib.Repository repo =
                    FileRepositoryBuilder.create(new File(repoPath));
            repo.create(true);

            StoredConfig config = repo.getConfig();
            config.setBoolean("http", null, "receivepack", true);
            config.setBoolean("core", null, "bare", true);
            config.setString("gitbase", null, "visibility", isPrivate ? "private" : "public");
            config.save();
            repo.close();

            // ✅ Persist metadata in PostgreSQL
            RepositoryGit entity = new RepositoryGit();
            entity.setOwner(userRepository.findUserByName(user).get());
            entity.setRepoName(repoName);
            entity.setPrivate(isPrivate);
            entity.setCreatedAt(LocalDateTime.now());
            gitRepositoryDB.save(entity);

        } catch (IOException e) {
            throw new RuntimeException("Failed to create repository: " + user+ "/" + repoName, e);
        }
    }

    public void handleReceivePack(String username, String repoName,
                                  HttpServletRequest request,
                                  HttpServletResponse response) {
        // Validate inputs
        validateUsername(username);
        validateRepositoryName(repoName);

        String repoPath = getRepositoryPath(username, repoName);

        // Check if repository exists
        if (!repositoryExists(username, repoName)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .readEnvironment()
                .build()) {

            ReceivePack receivePack = new ReceivePack(repo);
            receivePack.setPreReceiveHook(new CodeReviewHook());

            response.setContentType("application/x-git-receive-pack-result");
            receivePack.receive(request.getInputStream(), response.getOutputStream(), null);

        } catch (Exception e) {
            throw new RuntimeException("Git receive-pack failed for " + username + "/" + repoName, e);
        }
    }

    /**
     * Constructs the full filesystem path to a Git repository
     *
     * @param username the repository owner's username
     * @param repoName the repository name
     * @return the full path to the repository directory
     */
    private String getRepositoryPath(String username, String repoName) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(repoName)) {
            throw new IllegalArgumentException("Username and repository name cannot be empty");
        }

        // Ensure repository name ends with .git for bare repositories
        String normalizedRepoName = repoName.endsWith(".git") ? repoName : repoName + ".git";

        return Paths.get(repositoriesPath, username, normalizedRepoName).toString();
    }

    /**
     * Checks if a repository exists on the filesystem
     *
     * @param username the repository owner's username
     * @param repoName the repository name
     * @return true if the repository exists, false otherwise
     */
    public boolean repositoryExists(String username, String repoName) {
        try {
            String repoPath = getRepositoryPath(username, repoName);
            File repoDir = new File(repoPath);

            // Check if directory exists and contains Git repository files
            return repoDir.exists() &&
                    repoDir.isDirectory() &&
                    new File(repoDir, "HEAD").exists() &&
                    new File(repoDir, "config").exists();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gets the absolute path to a repository and validates it exists
     *
     * @param username the repository owner's username
     * @param repoName the repository name
     * @return the validated repository path
     * @throws IllegalArgumentException if repository doesn't exist
     */
    public String getValidatedRepositoryPath(String username, String repoName) {
        String repoPath = getRepositoryPath(username, repoName);

        if (!repositoryExists(username, repoName)) {
            throw new IllegalArgumentException("Repository does not exist: " + username + "/" + repoName);
        }

        return repoPath;
    }

    /**
     * Lists all repositories for a given user
     *
     * @return array of repository names (without .git extension)
     */
    private void debugRepository(Repository repo, String repoName) throws IOException {
        System.out.println("\n=== DEBUG: " + repoName + " ===");

        // Check HEAD
        Ref headRef = repo.exactRef(Constants.HEAD);
        System.out.println("HEAD ref: " + headRef);
        if (headRef != null) {
            System.out.println("  isSymbolic: " + headRef.isSymbolic());
            System.out.println("  target: " + headRef.getTarget());
            if (headRef.getTarget() != null) {
                System.out.println("  target objectId: " + headRef.getTarget().getObjectId());
            }
        }

        // List all branches
        System.out.println("\nBranches:");
        List<Ref> branches = repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);
        for (Ref branch : branches) {
            System.out.println("  " + branch.getName() + " -> " + branch.getObjectId());
        }

        // Try to read refs/heads/main directly from filesystem
        File mainRef = new File(repo.getDirectory(), "refs/heads/main");
        System.out.println("\nrefs/heads/main file exists: " + mainRef.exists());
        if (mainRef.exists()) {
            String content = new String(java.nio.file.Files.readAllBytes(mainRef.toPath())).trim();
            System.out.println("  content: " + content);
        }

        // Check packed-refs
        File packedRefs = new File(repo.getDirectory(), "packed-refs");
        System.out.println("packed-refs exists: " + packedRefs.exists());
        if (packedRefs.exists()) {
            String content = new String(java.nio.file.Files.readAllBytes(packedRefs.toPath()));
            System.out.println("  content:\n" + content);
        }

        System.out.println("=== END DEBUG ===\n");
    }

    public List<RepositoryTreeDto> listRepositories(String user) {
        List<RepositoryGit> repos = gitRepositoryDB.findByOwner(
                userRepository.findUserByName(user).get()
        );

        List<RepositoryTreeDto> result = new ArrayList<>();

        for (RepositoryGit repoEntity : repos) {
            String repoPath = Paths.get(repositoriesPath, user, repoEntity.getRepoName() + ".git").toString();

            try (Repository repo = new FileRepositoryBuilder()
                    .setGitDir(new File(repoPath))
                    .setBare()
                    .build()) {

                ObjectId headId = resolveHead(repo); // Smart resolution with auto-fix

                if (headId == null) {
                    System.out.println("⚠️ Repository " + repoEntity.getRepoName() + " is empty");
                    result.add(new RepositoryTreeDto(repoEntity.getRepoName(), repoEntity.isPrivate(), null));
                    continue;
                }

                try (RevWalk revWalk = new RevWalk(repo)) {
                    RevCommit commit = revWalk.parseCommit(headId);
                    RevTree tree = commit.getTree();

                    FileTreeNode root = buildFileTree(repo, tree);

                    RepositoryTreeDto dto = new RepositoryTreeDto();
                    dto.setRepoName(repoEntity.getRepoName());
                    dto.setPrivate(repoEntity.isPrivate());
                    dto.setRoot(root);

                    result.add(dto);
                }

            } catch (Exception e) {
                System.err.println("❌ Failed to load repository: " + repoEntity.getRepoName());
                e.printStackTrace();
                result.add(new RepositoryTreeDto(repoEntity.getRepoName(), repoEntity.isPrivate(), null));
            }
        }

        return result;
    }

    private ObjectId resolveHead(Repository repo) throws IOException {
        repo.getRefDatabase().refresh();

        // Try to resolve HEAD normally
        Ref headRef = repo.exactRef(Constants.HEAD);
        if (headRef != null && headRef.isSymbolic()) {
            Ref target = headRef.getTarget();
            if (target != null && target.getObjectId() != null) {
                return target.getObjectId();
            }
        }

        // HEAD is broken - find and use the actual default branch
        List<String> preferredBranches = Arrays.asList(
                "refs/heads/main",
                "refs/heads/master",
                "refs/heads/develop"
        );

        for (String branch : preferredBranches) {
            ObjectId id = repo.resolve(branch);
            if (id != null) {
                updateHeadSilently(repo, branch);
                return id;
            }
        }

        // Use any available branch
        List<Ref> branches = repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS);
        if (!branches.isEmpty() && branches.get(0).getObjectId() != null) {
            updateHeadSilently(repo, branches.get(0).getName());
            return branches.get(0).getObjectId();
        }

        return null;
    }

    private void updateHeadSilently(Repository repo, String branchRef) {
        try {
            RefUpdate refUpdate = repo.updateRef(Constants.HEAD, true);
            refUpdate.link(branchRef);
        } catch (IOException e) {
            // Fail silently
        }
    }

    private FileTreeNode buildFileTree(Repository repo, RevTree tree) throws IOException {
        FileTreeNode root = new FileTreeNode("root", true); // Changed from "/" to "root"

        try (TreeWalk treeWalk = new TreeWalk(repo)) {
            treeWalk.addTree(tree);
            treeWalk.setRecursive(true);

            int fileCount = 0;
            while (treeWalk.next()) {
                String path = treeWalk.getPathString();
                addPathToTree(root, path);
                fileCount++;
            }

            System.out.println("📁 Processed " + fileCount + " files");
        }

        return root;
    }

    private void addPathToTree(FileTreeNode root, String path) {
        String[] parts = path.split("/");
        FileTreeNode current = root;

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            boolean isDir = (i < parts.length - 1);

            FileTreeNode child = current.getChildren()
                    .stream()
                    .filter(c -> c.getName().equals(part))
                    .findFirst()
                    .orElse(null);

            if (child == null) {
                child = new FileTreeNode(part, isDir);
                current.getChildren().add(child);
            }

            current = child;
        }
    }

    /**
     * Deletes a repository: removes the PostgreSQL metadata row first, then the bare
     * repository directory on disk. Throws {@link ResourceNotFoundException} if no
     * repository exists for the given owner/name.
     *
     * @param username the repository owner's username
     * @param repoName the repository name
     */
    @Override
    public void deleteRepository(String username, String repoName) {
        validateUsername(username);
        validateRepositoryName(repoName);

        RepositoryGit entity = gitRepositoryDB.findByOwnerNameAndRepoName(username, repoName)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Repository not found: " + username + "/" + repoName));

        // Remove metadata first so the repo disappears from listings even if the
        // filesystem delete partially fails.
        gitRepositoryDB.delete(entity);

        String repoPath = getRepositoryPath(username, repoName);
        try {
            deleteDirectory(Paths.get(repoPath));
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete repository files: " + username + "/" + repoName, e);
        }
    }

    /**
     * Gets repository information
     *
     * @param username the repository owner's username
     * @param repoName the repository name
     * @return repository information object
     */
    public RepositoryInfo getRepositoryInfo(String username, String repoName) {
        // ✅ Check from DB first
        Optional<RepositoryGit> repoEntityOpt = gitRepositoryDB.findByOwnerNameAndRepoName(username, repoName);
        RepositoryGit repoEntity = repoEntityOpt.orElseThrow(() ->
                new ResourceNotFoundException("Repository not found: " + username + "/" + repoName));

        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .build();
             Git git = new Git(repo)) {

            // ✅ Fetch last commit
            Iterable<RevCommit> commits = git.log().setMaxCount(1).call();
            long lastCommitTime = commits.iterator().hasNext()
                    ? commits.iterator().next().getCommitTime() * 1000L
                    : 0L;

            return new RepositoryInfo(
                    username,
                    repoEntity.getRepoName(),
                    repoEntity.isPrivate(),
                    lastCommitTime
            );

        } catch (Exception e) {
            throw new RuntimeException("Failed to get repository info: " + username + "/" + repoName, e);
        }
    }

    /**
     * Returns the full recursive file tree for a single repository at the given ref.
     * A null/blank ref resolves to the repository's default branch (HEAD).
     */
    @Override
    public FileTreeNode getTree(String username, String repoName, String ref) {
        validateUsername(username);
        validateRepositoryName(repoName);

        if (!repositoryExists(username, repoName)) {
            throw new ResourceNotFoundException("Repository not found: " + username + "/" + repoName);
        }

        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .setBare()
                .build()) {

            ObjectId commitId = resolveRef(repo, ref);
            if (commitId == null) {
                // Empty repository — return an empty root rather than 404.
                return new FileTreeNode("root", true);
            }
            try (RevWalk revWalk = new RevWalk(repo)) {
                RevCommit commit = revWalk.parseCommit(commitId);
                return buildFileTree(repo, commit.getTree());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read tree for " + username + "/" + repoName, e);
        }
    }

    /**
     * Returns the raw content of a single file (blob) at the given ref. Text files are decoded
     * as UTF-8; binary files return {@code binary=true} with null content.
     */
    @Override
    public FileContentDto getFileContent(String username, String repoName, String ref, String path) {
        validateUsername(username);
        validateRepositoryName(repoName);
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("File path cannot be empty");
        }

        if (!repositoryExists(username, repoName)) {
            throw new ResourceNotFoundException("Repository not found: " + username + "/" + repoName);
        }

        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .setBare()
                .build()) {

            ObjectId commitId = resolveRef(repo, ref);
            if (commitId == null) {
                throw new ResourceNotFoundException("Repository is empty: " + username + "/" + repoName);
            }

            try (RevWalk revWalk = new RevWalk(repo)) {
                RevCommit commit = revWalk.parseCommit(commitId);
                try (TreeWalk treeWalk = TreeWalk.forPath(repo, path, commit.getTree())) {
                    if (treeWalk == null) {
                        throw new ResourceNotFoundException("File not found: " + path);
                    }
                    ObjectLoader loader = repo.open(treeWalk.getObjectId(0));
                    byte[] bytes = loader.getBytes();
                    boolean binary = isBinary(bytes);
                    String content = binary ? null : new String(bytes, StandardCharsets.UTF_8);
                    String resolvedRef = StringUtils.hasText(ref) ? ref : "HEAD";
                    return new FileContentDto(path, resolvedRef, bytes.length, binary, content);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file " + path + " in " + username + "/" + repoName, e);
        }
    }

    // ────────────────────────── Branch CRUD (git refs only) ──────────────────────────

    /**
     * Creates {@code refs/heads/<branchName>} pointing at {@code fromRef}.
     *
     * <p>There is no branch table — the ref *is* the branch (DECISION-007). The update is a
     * compare-and-swap against "must not already exist", so two concurrent creates cannot both win.
     */
    @Override
    public BranchSummaryDto createBranch(String username, String repoName, String branchName, String fromRef) {
        validateUsername(username);
        validateRepositoryName(repoName);

        String name = branchName == null ? "" : branchName.trim();
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("Branch name is required");
        }
        // Let git decide what a legal ref name is rather than inventing our own rules.
        if (!Repository.isValidRefName(Constants.R_HEADS + name)) {
            throw new IllegalArgumentException("Invalid branch name: " + name);
        }
        if (!repositoryExists(username, repoName)) {
            throw new ResourceNotFoundException("Repository not found: " + username + "/" + repoName);
        }

        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .setBare()
                .build()) {

            if (repo.exactRef(Constants.R_HEADS + name) != null) {
                throw new ConflictException("Branch already exists: " + name);
            }

            ObjectId startPoint = resolveRef(repo, fromRef);
            if (startPoint == null) {
                throw new ResourceNotFoundException(StringUtils.hasText(fromRef)
                        ? "Start point not found: " + fromRef
                        : "Repository is empty — nothing to branch from");
            }

            RefUpdate update = repo.updateRef(Constants.R_HEADS + name);
            update.setNewObjectId(startPoint);
            // Zero id as the expected old value means "create only" — fails if it already exists.
            update.setExpectedOldObjectId(ObjectId.zeroId());
            update.setRefLogMessage("branch: created from " + (StringUtils.hasText(fromRef) ? fromRef : "HEAD"), false);

            RefUpdate.Result result = update.update();
            if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD) {
                throw new ConflictException("Could not create branch " + name + " (" + result + ")");
            }

            String sha = startPoint.getName();
            return new BranchSummaryDto(name, sha, sha.substring(0, 7), false);

        } catch (IOException e) {
            throw new RuntimeException("Failed to create branch " + name + " in " + username + "/" + repoName, e);
        }
    }

    /** Deletes {@code refs/heads/<branchName>}. Refuses to delete the branch HEAD points at. */
    @Override
    public void deleteBranch(String username, String repoName, String branchName) {
        validateUsername(username);
        validateRepositoryName(repoName);

        String name = branchName == null ? "" : branchName.trim();
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("Branch name is required");
        }
        if (!repositoryExists(username, repoName)) {
            throw new ResourceNotFoundException("Repository not found: " + username + "/" + repoName);
        }

        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .setBare()
                .build()) {

            Ref existing = repo.exactRef(Constants.R_HEADS + name);
            if (existing == null) {
                throw new ResourceNotFoundException("Branch not found: " + name);
            }

            // Deleting the branch HEAD points at would leave the repository without a usable default.
            Ref head = repo.exactRef(Constants.HEAD);
            if (head != null && head.isSymbolic() && head.getTarget() != null
                    && head.getTarget().getName().equals(Constants.R_HEADS + name)) {
                throw new ConflictException("Cannot delete the default branch: " + name);
            }

            RefUpdate update = repo.updateRef(Constants.R_HEADS + name);
            update.setForceUpdate(true);
            update.setRefLogMessage("branch: deleted", false);

            RefUpdate.Result result = update.delete();
            if (result != RefUpdate.Result.FORCED && result != RefUpdate.Result.NEW) {
                throw new ConflictException("Could not delete branch " + name + " (" + result + ")");
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to delete branch " + name + " in " + username + "/" + repoName, e);
        }
    }

    /** Hard cap on a raw blob we will materialise in memory (ADR-004: bound every read). */
    private static final long MAX_RAW_BYTES = 25L * 1024 * 1024;

    /**
     * Raster image types we are willing to render inline. Everything else — including SVG, which
     * can carry script — is served as a download so a repository can never host active content
     * on our origin.
     */
    private static final Map<String, String> INLINE_IMAGE_TYPES = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp",
            "avif", "image/avif",
            "bmp", "image/bmp",
            "ico", "image/x-icon"
    );

    /**
     * Returns the raw bytes of a blob for image preview / download.
     *
     * <p>Content type is decided by a strict allowlist rather than by trusting the file: raster
     * images render inline, other text renders as {@code text/plain}, and everything else is forced
     * to {@code application/octet-stream} as an attachment.
     */
    @Override
    public RawFileDto getRawFile(String username, String repoName, String ref, String path) {
        validateUsername(username);
        validateRepositoryName(repoName);
        if (!StringUtils.hasText(path)) {
            throw new IllegalArgumentException("File path cannot be empty");
        }

        if (!repositoryExists(username, repoName)) {
            throw new ResourceNotFoundException("Repository not found: " + username + "/" + repoName);
        }

        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .setBare()
                .build()) {

            ObjectId commitId = resolveRef(repo, ref);
            if (commitId == null) {
                throw new ResourceNotFoundException("Repository is empty: " + username + "/" + repoName);
            }

            try (RevWalk revWalk = new RevWalk(repo)) {
                RevCommit commit = revWalk.parseCommit(commitId);
                try (TreeWalk treeWalk = TreeWalk.forPath(repo, path, commit.getTree())) {
                    if (treeWalk == null) {
                        throw new ResourceNotFoundException("File not found: " + path);
                    }
                    ObjectLoader loader = repo.open(treeWalk.getObjectId(0));
                    if (loader.getSize() > MAX_RAW_BYTES) {
                        throw new IllegalArgumentException(
                                "File is too large to serve (" + loader.getSize() + " bytes, limit " + MAX_RAW_BYTES + ")");
                    }
                    byte[] bytes = loader.getBytes();
                    return buildRawFile(path, bytes);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to read raw file " + path + " in " + username + "/" + repoName, e);
        }
    }

    /** Applies the inline/download content-type policy to a blob's bytes. */
    private RawFileDto buildRawFile(String path, byte[] bytes) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";

        String imageType = INLINE_IMAGE_TYPES.get(ext);
        if (imageType != null) {
            return new RawFileDto(path, bytes, imageType, true);
        }
        if (!isBinary(bytes)) {
            // Safe to show in a browser tab: text/plain + nosniff (set by the controller) means it
            // is never interpreted as HTML, so repository content cannot execute on our origin.
            return new RawFileDto(path, bytes, "text/plain; charset=utf-8", true);
        }
        return new RawFileDto(path, bytes, "application/octet-stream", false);
    }

    /**
     * GitHub-style listing of one directory level. For each entry we run a path-filtered
     * {@code git log -1} to find the commit that last modified it. Also returns the repo's
     * latest commit on the ref for the "latest commit" bar.
     */
    @Override
    public DirectoryListingDto listContents(String username, String repoName, String ref, String path) {
        validateUsername(username);
        validateRepositoryName(repoName);

        if (!repositoryExists(username, repoName)) {
            throw new ResourceNotFoundException("Repository not found: " + username + "/" + repoName);
        }

        String normPath = path == null ? "" : path.replaceAll("^/+|/+$", "");
        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .setBare()
                .build()) {

            ObjectId commitId = resolveRef(repo, ref);
            String refLabel = StringUtils.hasText(ref) ? ref : "HEAD";

            if (commitId == null) {
                return new DirectoryListingDto(normPath, refLabel, null, new ArrayList<>());
            }

            try (RevWalk revWalk = new RevWalk(repo); Git git = new Git(repo)) {
                RevCommit headCommit = revWalk.parseCommit(commitId);
                CommitSummaryDto latest = toSummary(headCommit);

                // Resolve the tree object whose immediate children we want to list.
                ObjectId treeToList;
                if (normPath.isEmpty()) {
                    treeToList = headCommit.getTree();
                } else {
                    try (TreeWalk sub = TreeWalk.forPath(repo, normPath, headCommit.getTree())) {
                        if (sub == null) {
                            throw new ResourceNotFoundException("Path not found: " + normPath);
                        }
                        if (!sub.getFileMode(0).equals(FileMode.TREE)) {
                            throw new IllegalArgumentException("Not a directory: " + normPath);
                        }
                        treeToList = sub.getObjectId(0);
                    }
                }

                List<DirEntryDto> entries = new ArrayList<>();
                try (TreeWalk tw = new TreeWalk(repo)) {
                    tw.addTree(treeToList);
                    tw.setRecursive(false);
                    while (tw.next()) {
                        String name = tw.getNameString();
                        boolean isDir = tw.isSubtree();
                        String fullPath = normPath.isEmpty() ? name : normPath + "/" + name;
                        CommitSummaryDto last = lastCommitForPath(git, commitId, fullPath);
                        entries.add(new DirEntryDto(
                                name,
                                fullPath,
                                isDir ? "dir" : "file",
                                last != null ? last.getMessage() : null,
                                last != null ? last.getShortSha() : null,
                                last != null ? last.getDate() : 0L
                        ));
                    }
                }

                return new DirectoryListingDto(normPath, refLabel, latest, entries);
            }
        } catch (IOException | GitAPIException e) {
            throw new RuntimeException("Failed to list contents of " + username + "/" + repoName, e);
        }
    }

    /** Most recent commit that modified {@code path}, starting from {@code start}; null if none. */
    private CommitSummaryDto lastCommitForPath(Git git, ObjectId start, String path) throws GitAPIException, IOException {
        Iterator<RevCommit> it = git.log().add(start).addPath(path).setMaxCount(1).call().iterator();
        return it.hasNext() ? toSummary(it.next()) : null;
    }

    private CommitSummaryDto toSummary(RevCommit commit) {
        String sha = commit.getName();
        String shortSha = sha.length() >= 7 ? sha.substring(0, 7) : sha;
        PersonIdent author = commit.getAuthorIdent();
        long dateMillis = commit.getCommitTime() * 1000L;
        return new CommitSummaryDto(
                sha,
                shortSha,
                commit.getShortMessage(),
                author != null ? author.getName() : null,
                author != null ? author.getEmailAddress() : null,
                dateMillis
        );
    }

    /** Resolves a ref name (branch, tag, or SHA) to a commit id; null/blank falls back to HEAD. */
    private ObjectId resolveRef(Repository repo, String ref) throws IOException {
        if (!StringUtils.hasText(ref)) {
            return resolveHead(repo);
        }
        ObjectId id = repo.resolve(ref);
        if (id == null) {
            id = repo.resolve("refs/heads/" + ref);
        }
        return id;
    }

    /** Heuristic binary detection: a NUL byte within the first 8KB marks the blob as binary. */
    private boolean isBinary(byte[] bytes) {
        int len = Math.min(bytes.length, 8000);
        for (int i = 0; i < len; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Paginated commit history for a ref (default branch when ref is null/blank), optionally
     * filtered to commits touching {@code path}. Read live from JGit via {@code git log}.
     */
    @Override
    public CommitPageDto listCommitHistory(String username, String repoName, String ref, String path, int page, int size) {
        validateUsername(username);
        validateRepositoryName(repoName);
        if (!repositoryExists(username, repoName)) {
            throw new ResourceNotFoundException("Repository not found: " + username + "/" + repoName);
        }

        int safePage = Math.max(page, 0);
        int safeSize = (size <= 0 || size > 100) ? 30 : size;
        String refLabel = StringUtils.hasText(ref) ? ref : "HEAD";

        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .setBare()
                .build()) {

            ObjectId startId = resolveRef(repo, ref);
            if (startId == null) {
                return new CommitPageDto(refLabel, safePage, safeSize, false, new ArrayList<>());
            }

            try (Git git = new Git(repo)) {
                LogCommand log = git.log().add(startId);
                if (StringUtils.hasText(path)) {
                    log.addPath(path.replaceAll("^/+|/+$", ""));
                }
                // Fetch one extra commit to detect whether a next page exists.
                log.setSkip(safePage * safeSize).setMaxCount(safeSize + 1);

                List<CommitSummaryDto> commits = new ArrayList<>();
                boolean hasNext = false;
                int count = 0;
                for (RevCommit c : log.call()) {
                    if (count == safeSize) {
                        hasNext = true;
                        break;
                    }
                    commits.add(toSummary(c));
                    count++;
                }
                return new CommitPageDto(refLabel, safePage, safeSize, hasNext, commits);
            }
        } catch (IOException | GitAPIException e) {
            throw new RuntimeException("Failed to list commits for " + username + "/" + repoName, e);
        }
    }

    /**
     * Full metadata and per-file unified diff for a single commit, compared against its first
     * parent (or the empty tree for a root commit). Binary files report {@code binary=true}
     * with null patch text.
     */
    @Override
    public CommitDetailDto getCommitDetail(String username, String repoName, String sha) {
        validateUsername(username);
        validateRepositoryName(repoName);
        if (!StringUtils.hasText(sha)) {
            throw new IllegalArgumentException("Commit sha cannot be empty");
        }
        if (!repositoryExists(username, repoName)) {
            throw new ResourceNotFoundException("Repository not found: " + username + "/" + repoName);
        }

        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .setBare()
                .build()) {

            ObjectId commitId = repo.resolve(sha);
            if (commitId == null) {
                throw new ResourceNotFoundException("Commit not found: " + sha);
            }

            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit commit = walk.parseCommit(commitId);
                RevCommit parent = commit.getParentCount() > 0
                        ? walk.parseCommit(commit.getParent(0).getId())
                        : null;

                List<String> parents = new ArrayList<>();
                for (RevCommit p : commit.getParents()) {
                    parents.add(p.getName());
                }

                RevTree oldTree = parent == null ? null : parent.getTree();
                DiffResult diff = computeDiff(repo, oldTree, commit.getTree(), Integer.MAX_VALUE, Integer.MAX_VALUE);

                CommitSummaryDto summary = toSummary(commit);
                return new CommitDetailDto(
                        commit.getName(),
                        summary.getShortSha(),
                        commit.getFullMessage() != null ? commit.getFullMessage().trim() : "",
                        summary.getAuthorName(),
                        summary.getAuthorEmail(),
                        summary.getDate(),
                        parents,
                        diff.additions(),
                        diff.deletions(),
                        diff.files()
                );
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load commit " + sha + " in " + username + "/" + repoName, e);
        }
    }

    /**
     * Lists local branches (refs/heads) with their head commit SHA, flagging the one HEAD points at.
     * Read live from JGit so it always reflects on-disk state.
     */
    @Override
    public List<BranchSummaryDto> listBranches(String username, String repoName) {
        validateUsername(username);
        validateRepositoryName(repoName);
        if (!repositoryExists(username, repoName)) {
            throw new ResourceNotFoundException("Repository not found: " + username + "/" + repoName);
        }

        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .setBare()
                .build()) {

            String headBranch = null;
            Ref head = repo.exactRef(Constants.HEAD);
            if (head != null && head.isSymbolic() && head.getTarget() != null) {
                headBranch = head.getTarget().getName();
            }

            List<BranchSummaryDto> branches = new ArrayList<>();
            for (Ref ref : repo.getRefDatabase().getRefsByPrefix(Constants.R_HEADS)) {
                ObjectId id = ref.getObjectId();
                String sha = id != null ? id.getName() : "";
                String shortSha = sha.length() >= 7 ? sha.substring(0, 7) : sha;
                String name = ref.getName().substring(Constants.R_HEADS.length());
                boolean isDefault = ref.getName().equals(headBranch);
                branches.add(new BranchSummaryDto(name, sha, shortSha, isDefault));
            }
            branches.sort((a, b) -> {
                if (a.isDefault() != b.isDefault()) return a.isDefault() ? -1 : 1;
                return a.getName().compareTo(b.getName());
            });
            return branches;
        } catch (IOException e) {
            throw new RuntimeException("Failed to list branches for " + username + "/" + repoName, e);
        }
    }

    /** Diff-display limits (per pull-requests feature spec): truncate very large diffs. */
    private static final int MAX_DIFF_FILES = 500;
    private static final int MAX_DIFF_LINES = 50_000;

    /**
     * Three-way comparison of two refs — the basis of a pull request. Computes ahead/behind counts,
     * the commits {@code head} adds over {@code base}, and the diff from their merge base to {@code head}
     * (so changes already present on {@code base} are excluded). Large diffs are truncated.
     */
    @Override
    public CompareDto compare(String username, String repoName, String base, String head) {
        validateUsername(username);
        validateRepositoryName(repoName);
        if (!StringUtils.hasText(base) || !StringUtils.hasText(head)) {
            throw new IllegalArgumentException("Both base and head refs are required");
        }
        if (!repositoryExists(username, repoName)) {
            throw new ResourceNotFoundException("Repository not found: " + username + "/" + repoName);
        }

        String repoPath = Paths.get(repositoriesPath, username, repoName + ".git").toString();
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .setBare()
                .build()) {

            ObjectId baseId = resolveRef(repo, base);
            ObjectId headId = resolveRef(repo, head);
            if (baseId == null) {
                throw new ResourceNotFoundException("Base ref not found: " + base);
            }
            if (headId == null) {
                throw new ResourceNotFoundException("Head ref not found: " + head);
            }

            try (RevWalk walk = new RevWalk(repo)) {
                RevCommit baseCommit = walk.parseCommit(baseId);
                RevCommit headCommit = walk.parseCommit(headId);

                // Merge base (three-way anchor).
                RevCommit mergeBase;
                try (RevWalk mbWalk = new RevWalk(repo)) {
                    mbWalk.setRevFilter(RevFilter.MERGE_BASE);
                    mbWalk.markStart(mbWalk.parseCommit(baseId));
                    mbWalk.markStart(mbWalk.parseCommit(headId));
                    mergeBase = mbWalk.next();
                }

                List<CommitSummaryDto> commits = new ArrayList<>();
                int aheadBy = collectRange(repo, headId, baseId, commits);   // head not in base
                int behindBy = collectRange(repo, baseId, headId, null);     // base not in head

                RevTree oldTree = mergeBase != null ? walk.parseCommit(mergeBase).getTree() : null;
                DiffResult diff = computeDiff(repo, oldTree, headCommit.getTree(), MAX_DIFF_FILES, MAX_DIFF_LINES);

                return new CompareDto(
                        base,
                        head,
                        mergeBase != null ? mergeBase.getName() : null,
                        aheadBy,
                        behindBy,
                        commits,
                        diff.additions(),
                        diff.deletions(),
                        diff.truncated(),
                        diff.files()
                );
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to compare " + base + "..." + head + " in " + username + "/" + repoName, e);
        }
    }

    /** Commits reachable from {@code include} but not {@code exclude}; appends summaries to {@code sink} if non-null. Returns the count. */
    private int collectRange(Repository repo, ObjectId include, ObjectId exclude, List<CommitSummaryDto> sink) throws IOException {
        int count = 0;
        try (RevWalk walk = new RevWalk(repo)) {
            walk.markStart(walk.parseCommit(include));
            walk.markUninteresting(walk.parseCommit(exclude));
            for (RevCommit c : walk) {
                if (sink != null) {
                    sink.add(toSummary(c));
                }
                count++;
            }
        }
        return count;
    }

    /** Holder for a computed diff. */
    private record DiffResult(List<FileDiffDto> files, int additions, int deletions, boolean truncated) {}

    /**
     * Computes the per-file diff between two trees (a null {@code oldTree} means the empty tree, i.e. all
     * additions). Stops and flags {@code truncated} once the file or line budget is exceeded.
     */
    private DiffResult computeDiff(Repository repo, RevTree oldTree, RevTree newTree, int maxFiles, int maxLines) throws IOException {
        List<FileDiffDto> files = new ArrayList<>();
        int totalAdd = 0;
        int totalDel = 0;
        int lineCount = 0;
        boolean truncated = false;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter df = new DiffFormatter(out)) {
            df.setRepository(repo);
            df.setDiffComparator(RawTextComparator.DEFAULT);
            df.setDetectRenames(true);

            AbstractTreeIterator oldIt = oldTree == null ? new EmptyTreeIterator() : prepareTreeParser(repo, oldTree);
            AbstractTreeIterator newIt = prepareTreeParser(repo, newTree);

            for (DiffEntry de : df.scan(oldIt, newIt)) {
                if (files.size() >= maxFiles || lineCount >= maxLines) {
                    truncated = true;
                    break;
                }
                out.reset();
                df.format(de);
                df.flush();
                String patch = out.toString(StandardCharsets.UTF_8);

                FileHeader header = df.toFileHeader(de);
                int add = 0;
                int del = 0;
                for (Edit edit : header.toEditList()) {
                    add += edit.getEndB() - edit.getBeginB();
                    del += edit.getEndA() - edit.getBeginA();
                }
                boolean binary = header.getPatchType() != FileHeader.PatchType.UNIFIED;

                String newPath = de.getChangeType() == DiffEntry.ChangeType.DELETE
                        ? de.getOldPath() : de.getNewPath();
                String oldPath = (de.getChangeType() == DiffEntry.ChangeType.RENAME
                        || de.getChangeType() == DiffEntry.ChangeType.COPY)
                        ? de.getOldPath() : null;

                totalAdd += add;
                totalDel += del;
                lineCount += add + del;
                files.add(new FileDiffDto(newPath, oldPath, de.getChangeType().name(),
                        add, del, binary, binary ? null : patch));
            }
        }
        return new DiffResult(files, totalAdd, totalDel, truncated);
    }

    /** Loads a tree into a CanonicalTreeParser for diffing. */
    private AbstractTreeIterator prepareTreeParser(Repository repo, RevTree tree) throws IOException {
        try (ObjectReader reader = repo.newObjectReader()) {
            CanonicalTreeParser parser = new CanonicalTreeParser();
            parser.reset(reader, tree.getId());
            return parser;
        }
    }

    // Validation methods
    private void validateUsername(String username) {
        if (!StringUtils.hasText(username)) {
            throw new IllegalArgumentException("Username cannot be empty");
        }
        if (!VALID_USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("Invalid username format: " + username);
        }
        if (username.length() > 100) {
            throw new IllegalArgumentException("Username too long: " + username);
        }
    }

    private void validateRepositoryName(String repoName) {
        if (!StringUtils.hasText(repoName)) {
            throw new IllegalArgumentException("Repository name cannot be empty");
        }

        // Remove .git extension for validation
        String nameToValidate = repoName.endsWith(".git") ?
                repoName.substring(0, repoName.length() - 4) : repoName;

        if (!VALID_REPO_NAME.matcher(nameToValidate).matches()) {
            throw new IllegalArgumentException("Invalid repository name format: " + repoName);
        }
        if (nameToValidate.length() > 100) {
            throw new IllegalArgumentException("Repository name too long: " + repoName);
        }
    }

    // Utility methods
    private void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Files.walk(directory)
                .sorted(java.util.Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
    }

    public void handleUploadPack(String username, String repoName,
                                 HttpServletRequest request,
                                 HttpServletResponse response) {
        validateUsername(username);
        validateRepositoryName(repoName);

        String repoPath = getRepositoryPath(username, repoName);

        if (!repositoryExists(username, repoName)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .readEnvironment()
                .build()) {

            UploadPack uploadPack = new UploadPack(repo);
            response.setContentType("application/x-git-upload-pack-result");
            uploadPack.upload(request.getInputStream(), response.getOutputStream(), null);

        } catch (Exception e) {
            throw new RuntimeException("Git upload-pack failed for " + username + "/" + repoName, e);
        }
    }

    public void handleInfoRefs(String username, String repoName, String service, HttpServletResponse response) {
        validateUsername(username);
        validateRepositoryName(repoName);

        String repoPath = getRepositoryPath(username, repoName);

        if (!repositoryExists(username, repoName)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(repoPath))
                .readEnvironment()
                .build();
             OutputStream out = response.getOutputStream()) {

            if ("git-upload-pack".equals(service)) {
                response.setContentType("application/x-git-upload-pack-advertisement");

                UploadPack uploadPack = new UploadPack(repo);
                RefAdvertiser.PacketLineOutRefAdvertiser advertiser =
                        new RefAdvertiser.PacketLineOutRefAdvertiser(new org.eclipse.jgit.transport.PacketLineOut(out));
                uploadPack.sendAdvertisedRefs(advertiser);
            } else if ("git-receive-pack".equals(service)) {
                response.setContentType("application/x-git-receive-pack-advertisement");

                ReceivePack rp = new ReceivePack(repo);
                RefAdvertiser.PacketLineOutRefAdvertiser advertiser =
                        new RefAdvertiser.PacketLineOutRefAdvertiser(new org.eclipse.jgit.transport.PacketLineOut(out));
                rp.sendAdvertisedRefs(advertiser);
            } else {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to handle info/refs for " + username + "/" + repoName, e);
        }
    }

}