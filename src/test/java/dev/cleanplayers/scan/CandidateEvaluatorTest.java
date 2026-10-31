package dev.cleanplayers.scan;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateEvaluatorTest {

    @Test
    void prefixMatchingIsCaseInsensitive() {
        assertTrue(CandidateEvaluator.matchesPrefix("x_Steve", List.of("x_")));
        assertTrue(CandidateEvaluator.matchesPrefix("X_STEVE", List.of("x_")));
        assertTrue(CandidateEvaluator.matchesPrefix("Xx_Steve", List.of("xx_")));
        assertFalse(CandidateEvaluator.matchesPrefix("Steve", List.of("x_")));
        assertFalse(CandidateEvaluator.matchesPrefix(null, List.of("x_")));
        assertFalse(CandidateEvaluator.matchesPrefix("x_Steve", List.of()));
    }
}
