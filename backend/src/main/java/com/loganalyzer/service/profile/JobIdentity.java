package com.loganalyzer.service.profile;

/** How a dialect names the thing that ran. Any field may be {@code null}. */
public record JobIdentity(String jobName, String dagName, String taskName) {

    public static final JobIdentity EMPTY = new JobIdentity(null, null, null);

    public static JobIdentity ofJob(String jobName) {
        return new JobIdentity(jobName, null, null);
    }

    /** This identity's fields, falling back to {@code other}'s wherever this one is silent. */
    public JobIdentity orElse(JobIdentity other) {
        return new JobIdentity(
                jobName != null ? jobName : other.jobName(),
                dagName != null ? dagName : other.dagName(),
                taskName != null ? taskName : other.taskName());
    }
}
