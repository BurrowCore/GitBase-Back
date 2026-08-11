package org.os.gitbase.data;

import lombok.extern.slf4j.Slf4j;
import org.os.gitbase.auth.entity.Role;
import org.os.gitbase.auth.repository.RoleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
@Slf4j
@Order(1) // Ensure this runs early in the startup process
public class DataInitializer implements CommandLineRunner {

    @Autowired
    private RoleRepository roleRepository;

    @Override
    public void run(String... args) throws Exception {
        log.info("🚀 Starting data initialization...");
        
        try {
            // Initialize default roles
            initializeRoles();
            
            log.info("✅ Data initialization completed successfully!");
        } catch (Exception e) {
            log.error("❌ Error during data initialization: {}", e.getMessage(), e);
            throw e;
        }
    }

    private void initializeRoles() {
        log.info("📋 Initializing default roles...");
        
        List<String> defaultRoles = Arrays.asList(
            "ROLE_USER",      // Basic user role
            "ROLE_ADMIN",     // Administrator role
            "ROLE_CONTRIBUTER"  // Contributer role
        );

        int createdCount = 0;
        int skippedCount = 0;

        for (String roleName : defaultRoles) {
            try {
                if (!roleRepository.findRoleByRoleName(roleName).isPresent()) {
                    Role role = Role.builder()
                            .roleName(roleName)
                            .build();
                    
                    Role savedRole = roleRepository.save(role);
                    log.info("✅ Created default role: {}", savedRole.getRoleName());
                    createdCount++;
                } else {
                    log.info("⏭️  Role {} already exists, skipping...", roleName);
                    skippedCount++;
                }
            } catch (Exception e) {
                log.error("❌ Error creating role {}: {}", roleName, e.getMessage());
            }
        }

        log.info("📊 Role initialization summary: {} created, {} skipped", createdCount, skippedCount);
    }
} 