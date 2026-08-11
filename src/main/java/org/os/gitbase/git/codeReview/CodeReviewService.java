package org.os.gitbase.git.codeReview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.os.gitbase.git.entity.CodeIssue;
import org.os.gitbase.git.entity.CodeReviewResult;
import org.os.gitbase.git.entity.enums.IssueSeverity;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


@Service
public class CodeReviewService {

    //@Value("${anthropic.api.key}")
    private String anthropicApiKey;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public CodeReviewResult reviewCode(String diff, String language) {
        String prompt = buildCodeReviewPrompt(diff, language);

        AIRequest request = AIRequest.builder()
                .model("claude-sonnet-4-20250514")
                .maxTokens(4000)
                .messages(List.of(
                        AIRequest.Message.builder()
                                .role("user")
                                .content(prompt)
                                .build()
                ))
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", anthropicApiKey);
        headers.set("anthropic-version", "2023-06-01");
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<AIRequest> entity = new HttpEntity<>(request, headers);

        AIResponse response = restTemplate.postForObject(
                "https://api.anthropic.com/v1/messages",
                entity,
                AIResponse.class
        );

        return parseCodeReviewResponse(response.getContent().get(0).getText());
    }

    public CodeReviewResult parseCodeReviewResponse(String responseText) {
        try {
            // Extract JSON from response (in case it's wrapped in markdown)
            String jsonContent = extractJsonFromResponse(responseText);

            // Parse JSON response
            JsonNode rootNode = objectMapper.readTree(jsonContent);

            CodeReviewResult result = new CodeReviewResult();
            result.setSummary(rootNode.path("summary").asText());
            result.setOverallScore(rootNode.path("overallScore").asDouble());
            result.setApproved(rootNode.path("approved").asBoolean());

            // Parse issues
            List<CodeIssue> issues = new ArrayList<>();
            JsonNode issuesNode = rootNode.path("issues");
            if (issuesNode.isArray()) {
                for (JsonNode issueNode : issuesNode) {
                    CodeIssue issue = new CodeIssue();
                    issue.setFileName(issueNode.path("fileName").asText());
                    issue.setLineNumber(issueNode.path("lineNumber").asInt());
                    issue.setSeverity(IssueSeverity.valueOf(
                            issueNode.path("severity").asText().toUpperCase()));
                    issue.setMessage(issueNode.path("message").asText());
                    issue.setCategory(issueNode.path("category").asText());
                    issue.setSuggestion(issueNode.path("suggestion").asText());
                    issues.add(issue);
                }
            }
            result.setIssues(issues);

            return result;

        } catch (Exception e) {
            // Return error result if parsing fails
            return new CodeReviewResult("Failed to parse review response: " + e.getMessage(),
                    Arrays.asList(new CodeIssue("unknown", 0, IssueSeverity.CRITICAL,
                            "Response parsing error", "system",
                            "Check response format")),
                    0.0, false);
        }
    }

    private String extractJsonFromResponse(String responseText) {
        // Remove markdown code blocks if present
        String cleaned = responseText.trim();
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        }
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        return cleaned.trim();
    }

    private String buildCodeReviewPrompt(String diff, String language) {
        return String.format("""
            Please review this %s code diff and provide feedback on:
            1. Code quality and best practices
            2. Potential bugs or security issues
            3. Performance considerations
            4. Maintainability suggestions
            
            Code diff:
            ```
            %s
            ```
            
            Please format your response as JSON with the following structure:
            {
                "overall_score": 1-10,
                "issues": [
                    {
                        "type": "bug|style|performance|security",
                        "severity": "low|medium|high",
                        "line": number,
                        "message": "description",
                        "suggestion": "how to fix"
                    }
                ],
                "summary": "overall feedback"
            }
            """, language, diff);
    }
}
