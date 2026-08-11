package org.os.gitbase.git.controller;

import org.os.gitbase.common.ApiResponseEntity;
import org.os.gitbase.constant.Constant;
import org.os.gitbase.git.dto.CreateTokenDto;
import org.os.gitbase.git.dto.GitTokenInfo;
import org.os.gitbase.git.entity.enums.ActivityType;
import org.os.gitbase.git.service.ActivityService;
import org.os.gitbase.git.service.CommandGitService;
import org.os.gitbase.helper.Helper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(Constant.GITBASE_MAPPING_REQUEST + "/tokens")
public class GitTokenController {

    private final CommandGitService tokenService;
    private final ActivityService activityService;

    public GitTokenController(CommandGitService tokenService, ActivityService activityService) {
        this.tokenService = tokenService;
        this.activityService = activityService;
    }

    @PostMapping
    public ResponseEntity<ApiResponseEntity<Map<String, String>>> createToken(
            Principal principal,
            @RequestBody CreateTokenDto createTokenDto) {

        String username = Helper.removeAtSymbolAndFollowing(principal.getName());

        String rawToken = tokenService.createToken(
                username,
                createTokenDto.getName(),
                createTokenDto.getScopes(),
                Duration.ofDays(360) // expire in 360 days, like GitHub default
        );

        Map<String, String> data = new LinkedHashMap<>();
        data.put("token", rawToken);
        data.put("note", "Save this token securely. It will not be shown again.");

        activityService.logActivity(ActivityType.TOKEN_CREATED, username, createTokenDto.getName(),
                "[ " + createTokenDto.getName() + " ] Token created successfully");

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseEntity.created(data, "Token created successfully"));
    }

    @GetMapping
    public ResponseEntity<ApiResponseEntity<List<GitTokenInfo>>> getTokens(Principal principal) {
        String username = Helper.removeAtSymbolAndFollowing(principal.getName());
        List<GitTokenInfo> tokens = tokenService.getTokens(username);
        return ResponseEntity.ok(ApiResponseEntity.ok(tokens, "Tokens retrieved"));
    }

    @DeleteMapping("/{tokenId}")
    public ResponseEntity<ApiResponseEntity<Void>> revokeToken(
            Principal principal,
            @PathVariable Long tokenId) {

        String username = Helper.removeAtSymbolAndFollowing(principal.getName());
        tokenService.revokeToken(username, tokenId);

        activityService.logActivity(ActivityType.TOKEN_REVOKED, username, String.valueOf(tokenId),
                "Token revoked");

        return ResponseEntity.ok(ApiResponseEntity.message("Token revoked", HttpStatus.OK));
    }
}
