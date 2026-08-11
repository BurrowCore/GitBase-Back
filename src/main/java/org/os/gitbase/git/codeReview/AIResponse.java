package org.os.gitbase.git.codeReview;

import java.util.List;

public class AIResponse {
    private String id;
    private String type;
    private String role;
    private List<Content> content;
    private String model;
    private Usage usage;

    public List<Content> getContent() { return content; }
    public void setContent(List<Content> content) { this.content = content; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Usage getUsage() { return usage; }
    public void setUsage(Usage usage) { this.usage = usage; }

    public static class Content {
        private String type;
        private String text;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    public static class Usage {
        private int inputTokens;
        private int outputTokens;

        public int getInputTokens() { return inputTokens; }
        public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }

        public int getOutputTokens() { return outputTokens; }
        public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    }
}
