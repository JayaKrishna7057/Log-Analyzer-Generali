package com.loganalyzer.service.profile;

import com.loganalyzer.model.DataTargetDto;
import com.loganalyzer.model.MetricDto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One unit of work, whatever the producing format calls it: an ETL layer, an Airflow task
 * instance, a Spark job, a dbt model. Profiles emit these; the analyzer normalises them into
 * the report without needing to know which format they came from.
 *
 * @param status the status exactly as the log printed it; {@code null} means still running
 */
public record ProcessingUnit(
        String name,
        String executionId,
        LocalDateTime start,
        LocalDateTime end,
        String status,
        boolean failed,
        List<MetricDto> metrics,
        List<DataTargetDto> targets,
        Integer processed,
        Integer total,
        String percent,
        String progress
) {

    public boolean completed() {
        return status != null;
    }

    /** The first metric playing the given role, or {@code null} when this unit reported none. */
    public Integer metric(MetricDto.Kind kind) {
        return metrics.stream()
                .filter(metric -> kind.name().equals(metric.kind()))
                .findFirst()
                .map(metric -> (int) Math.min(metric.value(), Integer.MAX_VALUE))
                .orElse(null);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    /** Keeps profiles readable - most of them set only a handful of these fields. */
    public static final class Builder {

        private final String name;
        private final java.util.List<MetricDto> metrics = new java.util.ArrayList<>();
        private final java.util.List<DataTargetDto> targets = new java.util.ArrayList<>();

        private String executionId;
        private LocalDateTime start;
        private LocalDateTime end;
        private String status;
        private boolean failed;
        private Integer processed;
        private Integer total;
        private String percent;
        private String progress;

        private Builder(String name) {
            this.name = name;
        }

        public Builder executionId(String value) {
            this.executionId = value;
            return this;
        }

        public Builder start(LocalDateTime value) {
            this.start = value;
            return this;
        }

        public Builder end(LocalDateTime value) {
            this.end = value;
            return this;
        }

        public Builder status(String value, boolean isFailure) {
            this.status = value;
            this.failed = isFailure;
            return this;
        }

        public Builder metric(String key, String label, Integer value, MetricDto.Kind kind) {
            if (value != null) {
                metrics.add(MetricDto.of(key, label, value, kind));
            }
            return this;
        }

        public Builder target(DataTargetDto target) {
            targets.add(target);
            return this;
        }

        public Builder progress(Integer processed, Integer total, String percent, String text) {
            this.processed = processed;
            this.total = total;
            this.percent = percent;
            this.progress = text;
            return this;
        }

        public ProcessingUnit build() {
            return new ProcessingUnit(name, executionId, start, end, status, failed,
                    List.copyOf(metrics), List.copyOf(targets), processed, total, percent, progress);
        }
    }
}
