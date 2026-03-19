package com.perfplatform.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TestRunRepository extends JpaRepository<TestRun, String> {

    List<TestRun> findByTeamOrderByCreatedAtDesc(String team);

    List<TestRun> findByTeamAndTestTypeOrderByCreatedAtDesc(String team, TestRun.TestType testType);

    @Query("SELECT t FROM TestRun t WHERE t.team = :team ORDER BY t.createdAt DESC LIMIT 10")
    List<TestRun> findRecentByTeam(String team);

    @Query("SELECT t FROM TestRun t WHERE t.status = 'RUNNING'")
    List<TestRun> findAllRunning();
}
