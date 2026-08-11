package org.os.gitbase.git.config;

import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.os.gitbase.git.hook.PushSyncHook;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GitServletConfig {

    // handle refinfo, receivepacks and uploadpacks
    @Bean
    public ServletRegistrationBean<GitServlet> gitServlet(PushSyncHook pushSyncHook,
                                                          GitbaseRepositoryResolver repositoryResolver) {
        GitServlet gitServlet = new GitServlet();

        // Visibility- and traversal-checked resolver: private repos are only served to their owner.
        gitServlet.setRepositoryResolver(repositoryResolver);

        // Attach the DB-sync post-receive hook to every push handled by the servlet.
        gitServlet.setReceivePackFactory((ReceivePackFactory<jakarta.servlet.http.HttpServletRequest>) (req, db) -> {
            ReceivePack rp = new ReceivePack(db);
            rp.setPostReceiveHook(pushSyncHook);
            return rp;
        });

        // mount all repositories into this endpoint -> /gitbase/* (refs etc)
        return new ServletRegistrationBean<>(gitServlet, "/gitbase/*");
    }
}
