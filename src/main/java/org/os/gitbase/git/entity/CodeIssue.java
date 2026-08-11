package org.os.gitbase.git.entity;

import org.os.gitbase.git.entity.enums.IssueSeverity;

public class CodeIssue {
    private String fileName;
    private int lineNumber;
    private IssueSeverity severity;
    private String message;
    private String category;
    private String suggestion;

    public CodeIssue() {}

    public CodeIssue(String fileName, int lineNumber, IssueSeverity severity,
                     String message, String category, String suggestion) {
        this.fileName = fileName;
        this.lineNumber = lineNumber;
        this.severity = severity;
        this.message = message;
        this.category = category;
        this.suggestion = suggestion;
    }

    // Getters and Setters
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }

    public IssueSeverity getSeverity() { return severity; }
    public void setSeverity(IssueSeverity severity) { this.severity = severity; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
}
