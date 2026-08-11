package org.os.gitbase.git.util;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;


@Component
public class GitUtils {
    private static final Logger logger = LoggerFactory.getLogger(GitUtils.class);

    public static String generateDiff(Repository repository, ReceiveCommand cmd) {
        try (Git git = new Git(repository)) {
            ObjectId oldId = cmd.getOldId();
            ObjectId newId = cmd.getNewId();

            if (oldId.equals(ObjectId.zeroId())) {
                // New branch - compare with empty tree
                return getDiffForNewBranch(git, newId);
            } else {
                // Existing branch - compare old and new commits
                return getDiffBetweenCommits(git, oldId, newId);
            }
        } catch (Exception e) {
            logger.error("Error generating diff", e);
            return "Error generating diff: " + e.getMessage();
        }
    }

    private static String getDiffForNewBranch(Git git, ObjectId newId) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            git.diff()
                    .setOldTree(new EmptyTreeIterator())
                    .setNewTree(new CanonicalTreeParser(null,
                            git.getRepository().newObjectReader(), newId))
                    .setOutputStream(out)
                    .call();
            return out.toString("UTF-8");
        }
    }

    private static String getDiffBetweenCommits(Git git, ObjectId oldId, ObjectId newId) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            git.diff()
                    .setOldTree(getTreeIterator(git.getRepository(), oldId))
                    .setNewTree(getTreeIterator(git.getRepository(), newId))
                    .setOutputStream(out)
                    .call();
            return out.toString("UTF-8");
        }
    }

    private static AbstractTreeIterator getTreeIterator(Repository repository, ObjectId id) throws Exception {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(id);
            RevTree tree = walk.parseTree(commit.getTree().getId());

            CanonicalTreeParser parser = new CanonicalTreeParser();
            parser.reset(repository.newObjectReader(), tree.getId());
            return parser;
        }
    }

    public static String detectLanguage(String diff) {
        // Simple language detection based on file extensions in diff
        Map<String, Integer> languageCount = new HashMap<>();

        String[] lines = diff.split("\n");
        for (String line : lines) {
            if (line.startsWith("diff --git")) {
                String fileName = extractFileName(line);
                String extension = getFileExtension(fileName);
                String language = mapExtensionToLanguage(extension);
                languageCount.merge(language, 1, Integer::sum);
            }
        }

        return languageCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("unknown");
    }

    private static String extractFileName(String diffLine) {
        // Extract filename from "diff --git a/file.ext b/file.ext"
        String[] parts = diffLine.split(" ");
        if (parts.length >= 4) {
            return parts[3].substring(2); // Remove "b/" prefix
        }
        return "";
    }

    private static String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1) : "";
    }

    private static String mapExtensionToLanguage(String extension) {
        Map<String, String> extensionMap = Map.of(
                "java", "Java",
                "js", "JavaScript",
                "ts", "TypeScript",
                "py", "Python",
                "cpp", "C++",
                "c", "C",
                "cs", "C#",
                "go", "Go",
                "rb", "Ruby",
                "php", "PHP"
        );

        return extensionMap.getOrDefault(extension.toLowerCase(), "unknown");
    }
}
