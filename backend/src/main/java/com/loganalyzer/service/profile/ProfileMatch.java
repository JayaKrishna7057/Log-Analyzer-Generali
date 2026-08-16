package com.loganalyzer.service.profile;

/**
 * The dialect chosen for a log and how sure the analyzer is.
 *
 * <p>{@link #NONE} means nothing matched confidently: the generic analysis still runs, but the
 * report says so, because a thin report must never be mistaken for a healthy job.
 */
public record ProfileMatch(LogProfile profile, double confidence) {

    public static final ProfileMatch NONE = new ProfileMatch(null, 0);

    public boolean matched() {
        return profile != null;
    }

    public String id() {
        return matched() ? profile.id() : ProfileRegistry.GENERIC_ID;
    }

    public String displayName() {
        return matched() ? profile.displayName() : "Generic (no format matched)";
    }
}
