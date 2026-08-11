package org.os.gitbase.git.repository;

import org.os.gitbase.git.entity.Activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ActivityRepository extends JpaRepository<Activity, Long> {
    List<Activity> findTop3ByActorOrderByTimestampDesc(String actor);
}
