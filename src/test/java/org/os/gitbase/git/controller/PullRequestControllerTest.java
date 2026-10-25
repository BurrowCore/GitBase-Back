package org.os.gitbase.git.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.os.gitbase.exception.AccessDeniedDomainException;
import org.os.gitbase.exception.ConflictException;
import org.os.gitbase.exception.GlobalExceptionHandler;
import org.os.gitbase.git.dto.MergeResultDto;
import org.os.gitbase.git.service.PullRequestService;
import org.os.gitbase.security.CurrentUserResolver;
import org.os.gitbase.security.RepositoryAccessGuard;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.security.Principal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP-level contract of the merge endpoint: status codes and the error envelope. */
class PullRequestControllerTest {

    private static final String MERGE = "/api/v1/web/gitbase/alice/demo/pulls/7/merge";

    private final PullRequestService service = mock(PullRequestService.class);
    private final RepositoryAccessGuard guard = mock(RepositoryAccessGuard.class);
    private final CurrentUserResolver currentUser = mock(CurrentUserResolver.class);
    private final Principal bob = () -> "bob@example.com";
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(currentUser.requireUsername(any())).thenReturn("bob");
        when(guard.effectivePermission(anyString(), anyString(), any())).thenReturn(Optional.empty());
        mvc = MockMvcBuilders.standaloneSetup(new PullRequestController(service, guard, currentUser))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void readCollaboratorCannotMerge_403() throws Exception {
        when(guard.requireWriteAccess(eq("alice"), eq("demo"), any()))
                .thenThrow(new AccessDeniedDomainException("You do not have write access to alice/demo"));

        mvc.perform(post(MERGE).principal(bob).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
        verify(service, never()).merge(anyString(), anyString(), anyInt(), anyString(), any());
    }

    @Test
    void unknownMergeMethod_400() throws Exception {
        mvc.perform(post(MERGE).principal(bob).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mergeMethod\":\"octopus\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedExpectedHeadSha_400() throws Exception {
        mvc.perform(post(MERGE).principal(bob).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedHeadSha\":\"not-a-sha\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void conflict_409_listsConflictingPathsInData() throws Exception {
        when(service.merge(eq("alice"), eq("demo"), eq(7), eq("bob"), any()))
                .thenThrow(new ConflictException("Merge conflict between feature and main", List.of("shared.txt")));

        mvc.perform(post(MERGE).principal(bob).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mergeMethod\":\"squash\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data[0]").value("shared.txt"));
    }

    @Test
    void successfulSquash_200_reportsTheMethod() throws Exception {
        when(service.merge(eq("alice"), eq("demo"), eq(7), eq("bob"), any()))
                .thenReturn(new MergeResultDto(true, "a".repeat(40), "squash", "Pull request successfully merged"));

        mvc.perform(post(MERGE).principal(bob).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mergeMethod\":\"squash\",\"expectedHeadSha\":\"" + "b".repeat(40) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mergeMethod").value("squash"));
    }
}
