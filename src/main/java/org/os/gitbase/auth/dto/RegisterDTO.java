package org.os.gitbase.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public class RegisterDTO {
    @NotNull(message = "email cannot be null")
    @Pattern(regexp = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$", message = "email must be a valid email address")
    String email;
    @NotNull(message = "password cannot be null")
    String password;
    @NotNull(message = "name cannot be null")
    String name;
    @NotNull(message = "fullName cannot be null")
    String fullName;
}
