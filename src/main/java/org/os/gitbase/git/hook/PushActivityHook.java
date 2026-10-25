package org.os.gitbase.git.hook;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PostReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.os.gitbase.git.config.RepositoryCoordinates;
import org.os.gitbase.git.entity.enums.ActivityType;
import org.os.gitbase.git.service.ActivityService;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Records an activity-feed entry for each successful push.
 *
 * <p>This replaces the former {@code PushSyncHook}, which mirrored commits and branch heads into
 * the database. Git is now the single source of truth for refs and objects (DECISION-007), so
 * nothing about the push is persisted — the activity row is pure collaboration metadata, which is
 * exactly the kind of data the database is still responsible for.
 *
 * <p>Identity is derived from the bare repository's directory
 * ({@code .../repositories/{username}/{repoName}.git}). Never throws: the push has already been
 * accepted by the time a post-receive hook runs, so failing here would only corrupt the response.
 */
@Slf4j
@Component
public class PushActivityHook implements PostReceiveHook {

    private final ActivityService activityService;

    public PushActivityHook(ActivityService activityService) {
        this.activityService = activityService;
    }

    @Override
    public void onPostReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        try {
            if (commands == null || commands.isEmpty()) {
                return;
            }
            Optional<RepositoryCoordinates> coordinates = RepositoryCoordinates.fromGitDir(repositoryDirectory(rp));
            if (coordinates.isEmpty()) {
                log.warn("Push activity: could not resolve owner/repository from the git directory");
                return;
            }
            String username = coordinates.get().owner();
            String repoName = coordinates.get().repoName();

            List<String> branches = commands.stream()
                    .filter(c -> c.getResult() == ReceiveCommand.Result.OK)
                    .map(c -> Repository.shortenRefName(c.getRefName()))
                    .toList();
            if (branches.isEmpty()) {
                return;
            }

            activityService.logActivity(ActivityType.PUSH, username, repoName,
                    "[ " + repoName + " ] Pushed to " + String.join(", ", branches));

        } catch (Exception e) {
            log.error("Push activity hook failed: {}", e.getMessage(), e);
        }
    }

    private File repositoryDirectory(ReceivePack rp) {
        Repository repository = rp.getRepository();
        return repository == null ? null : repository.getDirectory();
    }
}
