package org.os.gitbase.git.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request body for creating or updating a label. */
public record CreateLabelDto(
        @NotBlank(message = "Label name is required")
        @Size(max = 100, message = "Label name must be at most 100 characters")
        String name,

        @Pattern(regexp = "^[0-9a-fA-F]{6}$", message = "Color must be a 6-digit hex value, e.g. d73a4a")
        String color,

        @Size(max = 255, message = "Description must be at most 255 characters")
        String description
) {}
