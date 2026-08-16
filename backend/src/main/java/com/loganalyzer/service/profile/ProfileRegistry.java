package com.loganalyzer.service.profile;

import com.loganalyzer.service.LogSource;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Picks the dialect that best explains a log.
 *
 * <p>Scoring rather than a chain of {@code if}s is what makes this additive: a log that happens
 * to contain another dialect's marker still goes to whichever profile explains it best, and a new
 * format is a new {@link LogProfile} rather than an edit here.
 */
@Component
public class ProfileRegistry {

    /** Below this, a match is too weak to trust and the generic analysis runs instead. */
    public static final double MIN_CONFIDENCE = 0.5;

    public static final String GENERIC_ID = "generic";

    private final List<LogProfile> profiles;

    public ProfileRegistry() {
        // Structured first: when a log parses as data there is nothing left to guess at.
        this(List.of(new EcsJsonProfile(), new AirflowProfile(), new SparkProfile(),
                new ElabBatchProfile(), new BatchLayerProfile(), new TalendGenericProfile()));
    }

    ProfileRegistry(List<LogProfile> profiles) {
        this.profiles = List.copyOf(profiles);
    }

    /**
     * The chosen dialect and the units it found.
     *
     * <p>Confidence alone is not enough to choose by. stdout carries the work a job did while
     * stderr carries what went wrong, and the two are analysed together - so an error-heavy
     * stderr can out-score the stdout that describes the actual processing. When that happened,
     * a profile with no notion of units won and every layer, row count and progress figure from
     * stdout was discarded.
     *
     * <p>So among the profiles that match, the first one that can actually break the log into
     * units wins: a dialect that explains the structure always tells you more than one that only
     * recognises the error text. Confidence orders the candidates; producing units decides.
     */
    public ProfileSelection select(LogSource source) {

        List<ProfileMatch> candidates = profiles.stream()
                .map(profile -> new ProfileMatch(profile, profile.detect(source)))
                .filter(match -> match.confidence() >= MIN_CONFIDENCE)
                .sorted(Comparator.comparingDouble(ProfileMatch::confidence).reversed())
                .toList();

        for (ProfileMatch candidate : candidates) {
            List<ProcessingUnit> units = candidate.profile().parse(source);
            if (!units.isEmpty()) {
                return new ProfileSelection(candidate, units);
            }
        }

        // Nothing found units; keep the most confident match so the report still names a format.
        return new ProfileSelection(
                candidates.isEmpty() ? ProfileMatch.NONE : candidates.get(0), List.of());
    }

    /** The best match by confidence alone, without regard to whether it finds units. */
    public ProfileMatch detect(LogSource source) {
        return profiles.stream()
                .map(profile -> new ProfileMatch(profile, profile.detect(source)))
                .filter(match -> match.confidence() >= MIN_CONFIDENCE)
                .max(Comparator.comparingDouble(ProfileMatch::confidence))
                .orElse(ProfileMatch.NONE);
    }

    /** Every dialect this analyzer can recognise, in registration order. */
    public List<LogProfile> profiles() {
        return profiles;
    }
}
