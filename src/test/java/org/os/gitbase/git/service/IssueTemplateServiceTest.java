package org.os.gitbase.git.service;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.os.gitbase.git.dto.IssueTemplatesDto;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Templates are read live from the default branch of a real bare repository. */
class IssueTemplateServiceTest {

    @TempDir
    Path tempDir;

    private Path work;
    private Git git;
    private IssueTemplateService service;

    @BeforeEach
    void setUp() throws Exception {
        work = tempDir.resolve("work");
        git = Git.init().setDirectory(work.toFile()).setInitialBranch("main").call();
        service = new IssueTemplateService(tempDir.resolve("repos").toString());
    }

    @AfterEach
    void tearDown() {
        git.close();
    }

    @Test
    void parsesFrontMatterAndBody_sortedByFileName() throws Exception {
        write(".gitbase/ISSUE_TEMPLATE/bug.md", """
                ---
                name: Bug report
                about: Something is broken
                title: "[bug] "
                labels: [bug, triage]
                assignees: alice, bob
                ---
                ## Steps to reproduce
                """);
        write(".gitbase/ISSUE_TEMPLATE/a-feature.md", """
                ---
                name: Feature
                description: Suggest an idea
                ---
                Describe it.
                """);
        publish();

        IssueTemplatesDto result = service.templates("alice", "demo");

        assertThat(result.templates()).extracting(IssueTemplatesDto.Template::name)
                .containsExactly("Feature", "Bug report");
        var bug = result.templates().get(1);
        assertThat(bug.about()).isEqualTo("Something is broken");
        assertThat(bug.title()).isEqualTo("[bug] ");
        assertThat(bug.labels()).containsExactly("bug", "triage");
        assertThat(bug.assignees()).containsExactly("alice", "bob");
        assertThat(bug.body()).isEqualTo("## Steps to reproduce\n");
        assertThat(result.templates().get(0).about()).isEqualTo("Suggest an idea");
        assertThat(result.blankIssuesEnabled()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void readsConfig_andRejectsNonHttpContactLinks() throws Exception {
        write(".gitbase/ISSUE_TEMPLATE/bug.md", "Plain template without front matter");
        write(".gitbase/ISSUE_TEMPLATE/config.yml", """
                blank_issues_enabled: false
                contact_links:
                  - name: Forum
                    url: https://forum.example.com
                    about: Ask questions here
                  - name: Sneaky
                    url: "javascript:alert(1)"
                """);
        publish();

        IssueTemplatesDto result = service.templates("alice", "demo");

        assertThat(result.blankIssuesEnabled()).isFalse();
        assertThat(result.contactLinks()).extracting(IssueTemplatesDto.ContactLink::url)
                .containsExactly("https://forum.example.com");
        assertThat(result.errors()).anySatisfy(e -> assertThat(e.message()).contains("http(s)"));
        // No front matter: still offered, named after the file.
        assertThat(result.templates()).singleElement()
                .satisfies(t -> assertThat(t.name()).isEqualTo("bug"));
    }

    @Test
    void onlyTheGitbaseFolderIsRead() throws Exception {
        write(".gitbase/ISSUE_TEMPLATE/ours.md", "ours");
        write(".github/ISSUE_TEMPLATE/other.md", "not a GitBase template location");
        write(".gitea/ISSUE_TEMPLATE/other.md", "not a GitBase template location");
        publish();

        assertThat(service.templates("alice", "demo").templates())
                .extracting(IssueTemplatesDto.Template::fileName).containsExactly("ours.md");
    }

    @Test
    void reportsYamlFormsAndBrokenFrontMatter_withoutFailing() throws Exception {
        write(".gitbase/ISSUE_TEMPLATE/form.yml", "name: Form\nbody: []\n");
        write(".gitbase/ISSUE_TEMPLATE/broken.md","---\nname: [unclosed\n---\nbody\n");
        publish();

        IssueTemplatesDto result = service.templates("alice", "demo");

        assertThat(result.errors()).extracting(IssueTemplatesDto.TemplateError::fileName)
                .contains("form.yml", "broken.md");
        assertThat(result.templates()).singleElement()
                .satisfies(t -> assertThat(t.body()).isEqualTo("body\n"));
    }

    @Test
    void fallsBackToALegacySingleFile() throws Exception {
        write("ISSUE_TEMPLATE.md", "Please describe the problem.");
        publish();

        assertThat(service.templates("alice", "demo").templates()).singleElement()
                .satisfies(t -> assertThat(t.body()).isEqualTo("Please describe the problem."));
    }

    @Test
    void totalSizeIsCapped_soOneRequestCannotPullTensOfMegabytes() throws Exception {
        String big = "x".repeat(900 * 1024); // under the 1 MiB per-file limit
        for (int i = 0; i < 4; i++) {
            write(".gitbase/ISSUE_TEMPLATE/t" + i + ".md", big);
        }
        publish();

        IssueTemplatesDto result = service.templates("alice", "demo");

        long total = result.templates().stream().mapToLong(t -> t.body().length()).sum();
        org.assertj.core.api.Assertions.assertThat(total).isLessThanOrEqualTo(IssueTemplateService.MAX_TOTAL_BYTES);
        assertThat(result.templates()).hasSize(2);
        assertThat(result.errors()).anySatisfy(e -> assertThat(e.message()).contains("2 MiB"));
    }

    @Test
    void errorListIsCapped() throws Exception {
        for (int i = 0; i < 40; i++) {
            write(".gitbase/ISSUE_TEMPLATE/form" + i + ".yml", "name: f\n");
        }
        publish();

        assertThat(service.templates("alice", "demo").errors()).hasSize(IssueTemplateService.MAX_ERRORS + 1);
    }

    @Test
    void yamlAliasBombIsRejected_notExpanded() throws Exception {
        write(".gitbase/ISSUE_TEMPLATE/bomb.md", """
                ---
                a: &a ["x","x","x","x","x","x","x","x","x"]
                b: &b [*a,*a,*a,*a,*a,*a,*a,*a,*a]
                c: &c [*b,*b,*b,*b,*b,*b,*b,*b,*b]
                d: &d [*c,*c,*c,*c,*c,*c,*c,*c,*c]
                name: Bomb
                ---
                body
                """);
        publish();

        IssueTemplatesDto result = service.templates("alice", "demo");

        assertThat(result.errors()).singleElement().satisfies(e -> assertThat(e.message()).contains("Invalid front matter"));
        assertThat(result.templates()).singleElement().satisfies(t -> assertThat(t.name()).isEqualTo("bomb"));
    }

    @Test
    void emptyOrMissingRepository_hasNoTemplates() {
        IssueTemplatesDto result = service.templates("nobody", "nothing");
        assertThat(result.templates()).isEmpty();
        assertThat(result.blankIssuesEnabled()).isTrue();
    }

    private void write(String path, String content) throws Exception {
        Path file = work.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        git.add().addFilepattern(path).call();
    }

    /** Commits and mirrors to the bare location the service reads (repos/alice/demo.git). */
    private void publish() throws Exception {
        git.commit().setMessage("templates").setAuthor("A", "a@example.com").call();
        Path bare = tempDir.resolve("repos/alice/demo.git");
        Files.createDirectories(bare.getParent());
        Git.cloneRepository().setURI(work.toUri().toString()).setDirectory(bare.toFile()).setBare(true).call().close();
    }
}
