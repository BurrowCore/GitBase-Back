package org.os.gitbase.git.service;

import lombok.RequiredArgsConstructor;
import org.os.gitbase.git.entity.Activity;
import org.os.gitbase.git.entity.enums.ActivityType;
import org.os.gitbase.git.repository.ActivityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ActivityServiceImpl implements ActivityService {

    private final ActivityRepository activityRepository;

    /**
     * Audit logging runs in its own transaction (ADR-004 S8) so a failure here — e.g. a stale enum
     * check constraint — can never roll back the caller's primary work, notably the push commit/branch sync.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logActivity(ActivityType type, String actor, String target, String description) {
        Activity activity = new Activity();
        activity.setType(type);
        activity.setActor(actor);
        activity.setTarget(target);
        activity.setDescription(description);
        activity.setTimestamp(LocalDateTime.now());
        activityRepository.save(activity);
    }

    @Override
    public List<Activity> getRecentActivities(String username) {
        return activityRepository.findTop3ByActorOrderByTimestampDesc(username);
    }
}
