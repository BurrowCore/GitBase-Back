package org.os.gitbase.git.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.os.gitbase.git.entity.enums.ActivityType;

import java.time.LocalDateTime;

@Entity
@Table(name = "activities")
@Getter
@Setter
public class Activity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivityType type;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String target;

    @Column(nullable = false)
    private LocalDateTime timestamp = LocalDateTime.now();

    @Column(length = 1000)
    private String description;
}
