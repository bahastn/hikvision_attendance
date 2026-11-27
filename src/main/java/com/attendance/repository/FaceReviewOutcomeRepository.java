package com.attendance.repository;

import com.attendance.entity.FaceReviewOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FaceReviewOutcomeRepository extends JpaRepository<FaceReviewOutcome, Long> {

    List<FaceReviewOutcome> findByCandidateScoreIsNotNull();
}
