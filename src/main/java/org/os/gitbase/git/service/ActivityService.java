package org.os.gitbase.git.service;

import org.os.gitbase.git.entity.Activity;
import org.os.gitbase.git.entity.enums.ActivityType;

import java.util.List;

public interface ActivityService {
    void logActivity(ActivityType type, String actor, String target, String description);
    List<Activity> getRecentActivities(String username);
}
