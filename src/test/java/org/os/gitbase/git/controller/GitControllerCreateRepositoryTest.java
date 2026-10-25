package org.os.gitbase.git.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.os.gitbase.exception.GlobalExceptionHandler;
import org.os.gitbase.git.repository.TeamMemberRepository;
import org.os.gitbase.git.service.ActivityService;
import org.os.gitbase.git.service.CollaboratorService;
import org.os.gitbase.git.service.GitService;
import org.os.gitbase.git.service.OrganizationService;
import org.os.gitbase.git.service.RepositoryProvisioningService;
import org.os.gitbase.security.CurrentUserResolver;
import org.os.gitbase.security.RepositoryAccessGuard;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression for the repository-creation namespace bug: the owner used to be taken from the
 * request body, so any user could create repositories under someone else's account.
 */
class GitControllerCreateRepositoryTest {

    private static final String CREATE = "/api/v1/web/gitbase/create";

    private final GitService gitService = mock(GitService.class);
    private final OrganizationService organizations = mock(OrganizationService.class);
    private final CurrentUserResolver currentUser = mock(CurrentUserResolver.class);
    private final Principal mallory = () -> "mallory@example.com";
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(currentUser.requireUsername(any())).thenReturn("mallory");
        RepositoryProvisioningService provisioning = new RepositoryProvisioningService(gitService, organizations,
                mock(CollaboratorService.class), mock(TeamMemberRepository.class));
        mvc = MockMvcBuilders.standaloneSetup(new GitController(gitService, mock(ActivityService.class),
                        mock(RepositoryAccessGuard.class), currentUser, provisioning,
                        mock(org.os.gitbase.git.service.RepositoryTransferService.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void creatingInSomeoneElsesNamespace_isForbidden() throws Exception {
        mvc.perform(post(CREATE).principal(mallory).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"repoName\":\"pwned\"}"))
                .andExpect(status().isForbidden());
        verify(gitService, never()).createRepository(anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void creatingInAnOrganization_requiresItsPermission() throws Exception {
        when(organizations.canCreateRepository("acme", "mallory")).thenReturn(true);

        mvc.perform(post(CREATE).principal(mallory).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"acme\",\"repoName\":\"tool\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.remoteUrl").value("http://localhost:8880/gitbase/acme/tool.git"));
        verify(gitService).createRepository("acme", "tool", false, "main");
    }

    @Test
    void ownerAlwaysComesFromTheCaller_evenWithoutAUsername() throws Exception {
        mvc.perform(post(CREATE).principal(mallory).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repoName\":\"mine\",\"isPrivate\":true}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.remoteUrl").value("http://localhost:8880/gitbase/mallory/mine.git"));
        verify(gitService).createRepository("mallory", "mine", true, "main");
    }
}
