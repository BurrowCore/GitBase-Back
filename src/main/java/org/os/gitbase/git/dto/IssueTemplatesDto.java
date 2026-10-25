package org.os.gitbase.git.dto;

import java.util.List;

/**
 * Issue templates and chooser configuration, read live from the repository's default branch.
 *
 * @param templates          Markdown templates, sorted by file name
 * @param blankIssuesEnabled whether "Open a blank issue" is offered ({@code config.yml}; default true)
 * @param contactLinks       external links shown in the chooser ({@code config.yml})
 * @param errors             files that could not be used, so maintainers can fix them; never fatal
 */
public record IssueTemplatesDto(List<Template> templates, boolean blankIssuesEnabled,
                                List<ContactLink> contactLinks, List<TemplateError> errors) {

    /** A Markdown template: front-matter fields plus the body to prefill. */
    public record Template(String fileName, String name, String about, String title,
                           List<String> labels, List<String> assignees, String body) {}

    public record ContactLink(String name, String url, String about) {}

    public record TemplateError(String fileName, String message) {}

    public static IssueTemplatesDto empty() {
        return new IssueTemplatesDto(List.of(), true, List.of(), List.of());
    }
}
