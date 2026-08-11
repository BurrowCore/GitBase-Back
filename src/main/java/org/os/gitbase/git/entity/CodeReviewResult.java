package org.os.gitbase.git.entity;

import org.os.gitbase.git.entity.enums.IssueSeverity;

import java.util.ArrayList;
import java.util.List;

public class CodeReviewResult {
    private String summary;
    private List<CodeIssue> issues;
    private double overallScore;
    private boolean approved;

    public CodeReviewResult() {
        this.issues = new ArrayList<>();
    }

    public CodeReviewResult(String summary, List<CodeIssue> issues, double overallScore, boolean approved) {
        this.summary = summary;
        this.issues = issues != null ? issues : new ArrayList<>();
        this.overallScore = overallScore;
        this.approved = approved;
    }

    public boolean hasHighSeverityIssues() {
        return issues.stream()
                .anyMatch(issue -> issue.getSeverity() == IssueSeverity.HIGH ||
                        issue.getSeverity() == IssueSeverity.CRITICAL);
    }

    public long getHighSeverityCount() {
        return issues.stream()
                .filter(issue -> issue.getSeverity() == IssueSeverity.HIGH ||
                        issue.getSeverity() == IssueSeverity.CRITICAL)
                .count();
    }

    // Getters and Setters
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public List<CodeIssue> getIssues() { return issues; }
    public void setIssues(List<CodeIssue> issues) { this.issues = issues; }

    public double getOverallScore() { return overallScore; }
    public void setOverallScore(double overallScore) { this.overallScore = overallScore; }

    public boolean isApproved() { return approved; }
    public void setApproved(boolean approved) { this.approved = approved; }
}
