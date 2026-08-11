package org.os.gitbase.git.config;

import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.os.gitbase.git.hook.PushActivityHook;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GitServletConfig {

    // handle refinfo, receivepacks and uploadpacks
    @Bean
    public ServletRegistrationBean<GitServlet> gitServlet(PushActivityHook pushActivityHook,
                                                          GitbaseRepositoryResolver repositoryResolver) {
        GitServlet gitServlet = new GitServlet();

        // Visibility- and traversal-checked resolver: private repos are only served to their owner.
        gitServlet.setRepositoryResolver(repositoryResolver);

        // This factory is also what ENABLES push: JGit's default factory refuses receive-pack unless
        // `http.receivepack` is set in each repository's config. Returning a ReceivePack here allows
        // it unconditionally (authorization is handled by the resolver + security chain).
        // The hook only records an activity-feed entry — no git state is mirrored into the database.
        gitServlet.setReceivePackFactory((ReceivePackFactory<jakarta.servlet.http.HttpServletRequest>) (req, db) -> {
            ReceivePack rp = new ReceivePack(db);
            rp.setPostReceiveHook(pushActivityHook);
            return rp;
        });

        // mount all repositories into this endpoint -> /gitbase/* (refs etc)
        return new ServletRegistrationBean<>(gitServlet, "/gitbase/*");
    }
}
