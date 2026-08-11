package org.os.gitbase.git.hook;

import org.eclipse.jgit.transport.PreReceiveHook;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.os.gitbase.git.codeReview.CodeReviewService;
import org.os.gitbase.git.entity.CodeReviewResult;
import org.os.gitbase.git.util.GitUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;

@Component
public class CodeReviewHook implements PreReceiveHook {

    @Autowired
    private CodeReviewService codeReviewService;



    @Override
    public void onPreReceive(ReceivePack rp, Collection<ReceiveCommand> commands) {
        for (ReceiveCommand cmd : commands) {
            if (cmd.getType() == ReceiveCommand.Type.UPDATE ||
                    cmd.getType() == ReceiveCommand.Type.CREATE) {

                String diff = GitUtils.generateDiff(rp.getRepository(), cmd);
                CodeReviewResult review = codeReviewService.reviewCode(diff, GitUtils.detectLanguage(diff));

                if (review.hasHighSeverityIssues()) {
                    cmd.setResult(ReceiveCommand.Result.REJECTED_OTHER_REASON,
                            "Code review failed: " + review.getSummary());
                }
            }
        }
    }
}
