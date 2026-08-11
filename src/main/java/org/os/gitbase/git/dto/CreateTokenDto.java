package org.os.gitbase.git.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class CreateTokenDto {
    private String name;
    private String scopes;
}
