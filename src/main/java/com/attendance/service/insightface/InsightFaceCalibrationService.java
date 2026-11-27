package com.attendance.service.insightface;

import com.attendance.repository.FaceReviewOutcomeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the threshold calibration report from the live gallery and the logged review decisions
 * (see {@link ThresholdCalibrator}). Read-only: it only recommends; thresholds are changed through
 * the configuration (environment variables) so a change is deliberate and survives restarts.
 */
@Service
@RequiredArgsConstructor
public class InsightFaceCalibrationService {

    /** Leave-one-out probes are capped so the report stays fast on large galleries. */
    private static final int MAX_PROBES = 3000;

    private final InsightFaceGallery gallery;
    private final InsightFaceSettings settings;
    private final FaceReviewOutcomeRepository reviewOutcomeRepository;

    public ThresholdCalibrator.Report report() {
        List<ThresholdCalibrator.ReviewPoint> reviews = reviewOutcomeRepository.findByCandidateScoreIsNotNull().stream()
                .filter(r -> r.getConfirmedCandidate() != null)
                .map(r -> new ThresholdCalibrator.ReviewPoint(r.getCandidateScore(), r.getConfirmedCandidate()))
                .collect(Collectors.toList());
        return ThresholdCalibrator.calibrate(gallery.snapshot(), reviews, settings.getLowThreshold(),
                settings.getHighThreshold(), settings.getCalibrationTargetFar(),
                settings.getCalibrationTargetReviewRecall(), MAX_PROBES);
    }
}
