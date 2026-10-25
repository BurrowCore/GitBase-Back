package org.os.gitbase.git.service;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.os.gitbase.git.dto.IssueTemplatesDto;
import org.os.gitbase.git.dto.IssueTemplatesDto.ContactLink;
import org.os.gitbase.git.dto.IssueTemplatesDto.Template;
import org.os.gitbase.git.dto.IssueTemplatesDto.TemplateError;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Issue templates, read <b>live from the default branch</b> on every request — nothing is stored
 * (DECISION-007: git is the source of truth), so a pushed template is usable immediately.
 *
 * <p>GitBase's convention: Markdown templates with YAML front matter ({@code name}, {@code about}
 * or {@code description}, {@code title}, {@code labels}, {@code assignees}) in
 * {@code .gitbase/ISSUE_TEMPLATE/}, plus an optional {@code .gitbase/ISSUE_TEMPLATE/config.yml}
 * ({@code blank_issues_enabled}, {@code contact_links}). Without that directory, a single
 * {@code .gitbase/ISSUE_TEMPLATE.md} or root {@code ISSUE_TEMPLATE.md} is offered as the one
 * template. YAML issue <i>forms</i> are not supported yet and are reported in {@code errors}.
 *
 * <p>Bounded per ADR-004: at most {@value #MAX_TEMPLATES} templates of at most 1 MiB each.
 */
@Slf4j
@Service
public class IssueTemplateService {

    static final int MAX_TEMPLATES = 50;
    private static final long MAX_FILE_BYTES = 1024 * 1024;
    /** Cap on all template text in one response — 50 × 1 MiB per request would be an amplifier. */
    static final long MAX_TOTAL_BYTES = 2L * 1024 * 1024;
    static final int MAX_ERRORS = 20;
    /** Directory entries looked at; a template directory with thousands of files is not read. */
    static final int MAX_DIRECTORY_ENTRIES = 200;

    private static final List<String> TEMPLATE_DIRS = List.of(
            ".gitbase/ISSUE_TEMPLATE", ".gitbase/issue_template");

    private static final List<String> SINGLE_TEMPLATE_FILES = List.of(
            ".gitbase/ISSUE_TEMPLATE.md", ".gitbase/issue_template.md",
            "ISSUE_TEMPLATE.md", "issue_template.md");

    private final String basePath;

    public IssueTemplateService(@Value("${gitbase.repositories-path:./gitbase/repositories}") String basePath) {
        this.basePath = basePath;
    }

    public IssueTemplatesDto templates(String owner, String repoName) {
        try (Repository repo = new FileRepositoryBuilder()
                .setGitDir(Paths.get(basePath, owner, repoName + ".git").toFile()).setBare().build();
             RevWalk walk = new RevWalk(repo)) {
            ObjectId head = repo.resolve(Constants.HEAD);
            if (head == null) {
                return IssueTemplatesDto.empty(); // empty repository
            }
            return read(repo, walk.parseCommit(head).getTree());
        } catch (IOException e) {
            log.warn("Could not read issue templates of {}/{}: {}", owner, repoName, e.getMessage());
            return IssueTemplatesDto.empty();
        }
    }

    private IssueTemplatesDto read(Repository repo, RevTree tree) throws IOException {
        List<Template> templates = new ArrayList<>();
        Errors errors = new Errors();
        Budget budget = new Budget();
        boolean blankIssuesEnabled = true;
        List<ContactLink> contactLinks = new ArrayList<>();

        Optional<String> dir = firstExistingDirectory(repo, tree);
        if (dir.isPresent()) {
            List<Entry> entries = listFiles(repo, tree, dir.get());
            if (entries.size() > MAX_DIRECTORY_ENTRIES) {
                errors.add(dir.get(), "More than " + MAX_DIRECTORY_ENTRIES + " files; only the first were read");
                entries = entries.subList(0, MAX_DIRECTORY_ENTRIES);
            }
            for (Entry entry : entries) {
                String lower = entry.name().toLowerCase(Locale.ROOT);
                if (lower.equals("config.yml") || lower.equals("config.yaml")) {
                    blankIssuesEnabled = readConfig(repo, entry, contactLinks, errors, budget);
                } else if (lower.endsWith(".md")) {
                    if (templates.size() >= MAX_TEMPLATES) {
                        errors.add(entry.name(), "Ignored: more than " + MAX_TEMPLATES + " templates");
                    } else {
                        readText(repo, entry, errors, budget)
                                .ifPresent(text -> templates.add(parseTemplate(entry.name(), text, errors.list())));
                    }
                } else if (lower.endsWith(".yml") || lower.endsWith(".yaml")) {
                    errors.add(entry.name(), "YAML issue forms are not supported yet; use a Markdown template");
                }
            }
        } else {
            for (String path : SINGLE_TEMPLATE_FILES) {
                Optional<Entry> legacy = findFile(repo, tree, path);
                if (legacy.isPresent()) {
                    readText(repo, legacy.get(), errors, budget)
                            .ifPresent(text -> templates.add(parseTemplate(path, text, errors.list())));
                    break;
                }
            }
        }

        templates.sort(Comparator.comparing(Template::fileName));
        return new IssueTemplatesDto(List.copyOf(templates), blankIssuesEnabled, List.copyOf(contactLinks),
                List.copyOf(errors.list()));
    }

    /** Error list that stops growing at {@link #MAX_ERRORS}, saying so once. */
    private static final class Errors {
        private final List<TemplateError> list = new ArrayList<>();

        void add(String fileName, String message) {
            if (list.size() < MAX_ERRORS) {
                list.add(new TemplateError(fileName, message));
            } else if (list.size() == MAX_ERRORS) {
                list.add(new TemplateError("…", "More problems were found; only the first " + MAX_ERRORS + " are listed"));
            }
        }

        /** Live view for {@link #parseTemplate}; may briefly exceed the cap by one parse error per file. */
        List<TemplateError> list() {
            return list;
        }
    }

    /** Bytes of template text read so far in this request. */
    private static final class Budget {
        private long used;
    }

    // ───────────────────────── parsing ─────────────────────────

    /**
     * Front matter between leading {@code ---} lines is YAML; the rest is the body. A file with
     * missing or invalid front matter is still offered, named after the file.
     */
    Template parseTemplate(String fileName, String text, List<TemplateError> errors) {
        String fallbackName = stripExtension(fileName.substring(fileName.lastIndexOf('/') + 1));
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            return new Template(fileName, fallbackName, "", "", List.of(), List.of(), normalized);
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            errors.add(new TemplateError(fileName, "Front matter is not closed with '---'"));
            return new Template(fileName, fallbackName, "", "", List.of(), List.of(), normalized);
        }
        String body = normalized.substring(Math.min(normalized.length(), end + 4)).replaceFirst("^[^\\n]*\\n?", "");
        Map<String, Object> meta;
        try {
            meta = loadYamlMap(normalized.substring(4, end));
        } catch (RuntimeException e) {
            errors.add(new TemplateError(fileName, "Invalid front matter: " + firstLine(e.getMessage())));
            return new Template(fileName, fallbackName, "", "", List.of(), List.of(), body);
        }
        String about = string(meta.get("about"));
        return new Template(fileName,
                Optional.ofNullable(string(meta.get("name"))).filter(s -> !s.isBlank()).orElse(fallbackName),
                about != null ? about : Optional.ofNullable(string(meta.get("description"))).orElse(""),
                // Untrimmed: a prefix like "[bug] " keeps its space so the user types after it.
                meta.get("title") == null ? "" : meta.get("title").toString(),
                stringList(meta.get("labels")),
                stringList(meta.get("assignees")),
                body);
    }

    /** @return {@code blank_issues_enabled}; contact links and problems are added to the lists */
    private boolean readConfig(Repository repo, Entry entry, List<ContactLink> links, Errors errors, Budget budget)
            throws IOException {
        Optional<String> text = readText(repo, entry, errors, budget);
        if (text.isEmpty()) {
            return true;
        }
        Map<String, Object> config;
        try {
            config = loadYamlMap(text.get());
        } catch (RuntimeException e) {
            errors.add(entry.name(), "Invalid YAML: " + firstLine(e.getMessage()));
            return true;
        }
        if (config.get("contact_links") instanceof Collection<?> rawLinks) {
            for (Object raw : rawLinks) {
                if (raw instanceof Map<?, ?> link) {
                    String name = string(link.get("name"));
                    String url = string(link.get("url"));
                    if (name == null || name.isBlank() || !isHttpUrl(url)) {
                        errors.add(entry.name(), "Contact link needs a name and an http(s) url: " + name);
                        continue;
                    }
                    links.add(new ContactLink(name, url, Optional.ofNullable(string(link.get("about"))).orElse("")));
                }
            }
        }
        return !(config.get("blank_issues_enabled") instanceof Boolean enabled) || enabled;
    }

    /**
     * SafeConstructor builds only plain maps, lists and scalars — never arbitrary classes — so a
     * malicious template cannot trigger deserialization gadgets. Alias expansion is capped too.
     */
    private static Map<String, Object> loadYamlMap(String yaml) {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(20);
        Object loaded = new Yaml(new SafeConstructor(options)).load(yaml);
        if (loaded == null) {
            return Map.of();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("expected key: value pairs");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) map;
        return typed;
    }

    /** Only http(s) — a {@code javascript:} link rendered in the chooser would be an XSS hole. */
    static boolean isHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String string(Object value) {
        return value == null ? null : value.toString().trim();
    }

    /** A YAML list, or GitHub's comma-separated string form ({@code labels: bug, ui}). */
    private static List<String> stringList(Object value) {
        if (value instanceof Collection<?> items) {
            return items.stream().filter(java.util.Objects::nonNull).map(Object::toString).map(String::trim)
                    .filter(s -> !s.isEmpty()).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        return List.of();
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    // ───────────────────────── git access ─────────────────────────

    private record Entry(String name, ObjectId blob) {}

    private Optional<String> firstExistingDirectory(Repository repo, RevTree tree) throws IOException {
        for (String dir : TEMPLATE_DIRS) {
            try (TreeWalk walk = TreeWalk.forPath(repo, dir, tree)) {
                if (walk != null && walk.getFileMode(0) == FileMode.TREE) {
                    return Optional.of(dir);
                }
            }
        }
        return Optional.empty();
    }

    /** Regular files directly inside {@code dir} (no recursion). */
    private List<Entry> listFiles(Repository repo, RevTree tree, String dir) throws IOException {
        List<Entry> files = new ArrayList<>();
        try (TreeWalk walk = TreeWalk.forPath(repo, dir, tree)) {
            if (walk == null) {
                return files;
            }
            try (TreeWalk children = new TreeWalk(repo)) {
                children.addTree(walk.getObjectId(0));
                children.setRecursive(false);
                while (children.next()) {
                    if (children.getFileMode(0).getObjectType() == Constants.OBJ_BLOB) {
                        files.add(new Entry(children.getNameString(), children.getObjectId(0)));
                    }
                }
            }
        }
        return files;
    }

    private Optional<Entry> findFile(Repository repo, RevTree tree, String path) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(repo, path, tree)) {
            if (walk != null && walk.getFileMode(0).getObjectType() == Constants.OBJ_BLOB) {
                return Optional.of(new Entry(path, walk.getObjectId(0)));
            }
        }
        return Optional.empty();
    }

    private Optional<String> readText(Repository repo, Entry entry, Errors errors, Budget budget) throws IOException {
        ObjectLoader loader = repo.open(entry.blob(), Constants.OBJ_BLOB);
        long size = loader.getSize();
        if (size > MAX_FILE_BYTES) {
            errors.add(entry.name(), "Larger than 1 MiB");
            return Optional.empty();
        }
        if (budget.used + size > MAX_TOTAL_BYTES) {
            errors.add(entry.name(), "Skipped: templates exceed 2 MiB in total");
            return Optional.empty();
        }
        budget.used += size;
        return Optional.of(new String(loader.getBytes(), StandardCharsets.UTF_8));
    }
}
