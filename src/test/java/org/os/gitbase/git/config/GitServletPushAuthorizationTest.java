package org.os.gitbase.git.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.git.hook.PushActivityHook;
import org.os.gitbase.security.RepositoryAccessGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end check of the {@code /gitbase/*} smart-HTTP endpoint: the real {@code GitServlet} as
 * wired by {@link GitServletConfig}, served by an embedded Tomcat, driven by a real JGit client.
 *
 * <p>Unit tests on the factory cannot prove the factory is actually installed, nor that both the
 * {@code info/refs?service=git-receive-pack} advertisement and the POST are gated. This does.
 * Authentication is reduced to a filter that turns HTTP Basic credentials into the request
 * principal — exactly what the Spring git filter chain hands the servlet.
 */
class GitServletPushAuthorizationTest {

    @TempDir
    Path tempDir;

    private Tomcat tomcat;
    private String remoteUrl;
    private Path bareRepo;

    @BeforeEach
    void startServer() throws Exception {
        Path repositories = tempDir.resolve("repositories");
        bareRepo = repositories.resolve("alice").resolve("project.git");
        seedBareRepository();

        RepositoryAccessGuard guard = mock(RepositoryAccessGuard.class);
        // Everyone may read; only carol may write.
        when(guard.requireWriteAccess(eq("alice"), eq("project"), argThat(p -> p != null && !p.getName().equals("carol"))))
                .thenThrow(new AccessDeniedDomainException("You do not have write access to alice/project"));

        var resolver = new GitbaseRepositoryResolver(guard, repositories.toString());
        var factory = new GitbaseReceivePackFactory(guard, mock(PushActivityHook.class));
        var gitServlet = new GitServletConfig().gitServlet(resolver, factory).getServlet();

        tomcat = new Tomcat();
        tomcat.setBaseDir(tempDir.resolve("tomcat").toString());
        tomcat.setPort(0);
        tomcat.getConnector();
        Context context = tomcat.addContext("", null);
        Tomcat.addServlet(context, "git", gitServlet);
        context.addServletMappingDecoded("/gitbase/*", "git");
        addFilter(context, new BasicAuthPrincipalFilter());
        tomcat.start();

        remoteUrl = "http://localhost:" + tomcat.getConnector().getLocalPort() + "/gitbase/alice/project.git";
    }

    @AfterEach
    void stopServer() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    @Test
    void readOnlyUser_canCloneButCannotPush() throws Exception {
        Path work = cloneAs("bob");
        String before = tipOfMain();

        try (Git git = Git.open(work.toFile())) {
            commitFile(git, work, "bob.txt");
            // Refused at the info/refs advertisement with a 403, which JGit reports as "not
            // permitted" (the git CLI prints "The requested URL returned error: 403").
            assertThatThrownBy(() -> git.push().setCredentialsProvider(credentials("bob")).call())
                    .isInstanceOf(TransportException.class)
                    .hasMessageContaining("git-receive-pack not permitted");
        }

        assertThat(tipOfMain()).isEqualTo(before);
    }

    @Test
    void writer_canPush() throws Exception {
        Path work = cloneAs("carol");

        String pushed;
        try (Git git = Git.open(work.toFile())) {
            pushed = commitFile(git, work, "carol.txt");
            git.push().setCredentialsProvider(credentials("carol")).call();
        }

        assertThat(tipOfMain()).isEqualTo(pushed);
    }

    // ───────────────────────── fixtures ─────────────────────────

    private Path cloneAs(String user) throws Exception {
        Path work = tempDir.resolve("clone-" + user);
        Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(work.toFile())
                .setCredentialsProvider(credentials(user))
                .call()
                .close();
        return work;
    }

    private static UsernamePasswordCredentialsProvider credentials(String user) {
        return new UsernamePasswordCredentialsProvider(user, "token-of-" + user);
    }

    private String commitFile(Git git, Path work, String file) throws Exception {
        Files.writeString(work.resolve(file), file + "\n");
        git.add().addFilepattern(file).call();
        return git.commit().setMessage("add " + file).setAuthor("Test", "test@example.com").call().getName();
    }

    private void seedBareRepository() throws Exception {
        Path seed = tempDir.resolve("seed");
        try (Git git = Git.init().setDirectory(seed.toFile()).setInitialBranch("main").call()) {
            commitFile(git, seed, "README.md");
        }
        Files.createDirectories(bareRepo.getParent());
        Git.cloneRepository().setURI(seed.toUri().toString()).setDirectory(bareRepo.toFile())
                .setBare(true).call().close();
    }

    private String tipOfMain() throws IOException {
        try (Repository repo = new FileRepositoryBuilder().setGitDir(bareRepo.toFile()).setBare().build()) {
            return repo.resolve(Constants.R_HEADS + "main").getName();
        }
    }

    private static void addFilter(Context context, Filter filter) {
        FilterDef def = new FilterDef();
        def.setFilterName("basic-auth");
        def.setFilter(filter);
        context.addFilterDef(def);
        FilterMap map = new FilterMap();
        map.setFilterName("basic-auth");
        map.addURLPattern("/*");
        context.addFilterMap(map);
    }

    /** Stand-in for the Spring git filter chain: Basic credentials → request principal, else 401. */
    private static final class BasicAuthPrincipalFilter implements Filter {
        @Override
        public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                throws IOException, ServletException {
            HttpServletRequest request = (HttpServletRequest) req;
            String header = request.getHeader("Authorization");
            if (header == null || !header.startsWith("Basic ")) {
                HttpServletResponse response = (HttpServletResponse) res;
                response.setHeader("WWW-Authenticate", "Basic realm=\"Git Repository\"");
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
            String decoded = new String(Base64.getDecoder().decode(header.substring(6)), StandardCharsets.UTF_8);
            String user = decoded.substring(0, decoded.indexOf(':'));
            Principal principal = () -> user;
            chain.doFilter(new HttpServletRequestWrapper(request) {
                @Override
                public Principal getUserPrincipal() {
                    return principal;
                }
            }, res);
        }
    }
}
