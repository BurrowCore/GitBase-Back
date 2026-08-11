package org.os.gitbase.git.hook;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.transport.PostReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.os.gitbase.git.service.PushSyncService;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Collection;

/**
 * Post-receive hook that mirrors a successful push into the database. It derives the
 * owner/repo identity from the bare repository's directory
 * ({@code .../repositories/{username}/{repoName}.git}) so it works regardless of which
 * transport (GitServlet or controller) handled the push, then delegates to
 * {@link PushSyncService}. Never throws — the push has already completed.
 */
@Slf4j
@Component
public class PushSyncHook implements PostReceiveHook {

    private final PushSyncService pushSyncService;

    public PushSyncHook(PushSyncService pushSyncService) {
        this.pushSyncService = pushSyncService;
    }

    @Override
    public void onPostReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        try {
            File gitDir = rp.getRepository().getDirectory();
            if (gitDir == null) {
                return;
            }
            String repoName = gitDir.getName();
            if (repoName.endsWith(".git")) {
                repoName = repoName.substring(0, repoName.length() - 4);
            }
            File parent = gitDir.getParentFile();
            String username = parent != null ? parent.getName() : null;
            if (username == null || username.isBlank()) {
                log.warn("Push sync: could not resolve owner from {}", gitDir.getAbsolutePath());
                return;
            }
            pushSyncService.syncPush(rp.getRepository(), username, repoName, commands);
        } catch (Exception e) {
            log.error("Push sync hook failed: {}", e.getMessage(), e);
        }
    }
}
