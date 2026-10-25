package org.os.gitbase.git.config;

import org.eclipse.jgit.http.server.GitServlet;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GitServletConfig {

    // handle refinfo, receivepacks and uploadpacks
    @Bean
    public ServletRegistrationBean<GitServlet> gitServlet(GitbaseRepositoryResolver repositoryResolver,
                                                          GitbaseReceivePackFactory receivePackFactory) {
        GitServlet gitServlet = new GitServlet();

        // Read gate: visibility- and traversal-checked resolver (private repos masked as 404).
        gitServlet.setRepositoryResolver(repositoryResolver);

        // Write gate: push requires owner or a WRITE/ADMIN collaborator. This factory is also what
        // ENABLES push — JGit's default refuses receive-pack unless `http.receivepack` is set per
        // repository — so it must not be removed. It attaches the push activity hook.
        gitServlet.setReceivePackFactory(receivePackFactory);

        // mount all repositories into this endpoint -> /gitbase/* (refs etc)
        return new ServletRegistrationBean<>(gitServlet, "/gitbase/*");
    }
}
