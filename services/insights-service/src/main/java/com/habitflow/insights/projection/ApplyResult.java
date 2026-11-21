package com.habitflow.insights.projection;

/** Outcome of {@link ProjectionService#apply}. */
public enum ApplyResult {
    /** First time this consumer saw the event_id: recorded, and the projection updated if the type counts. */
    APPLIED,
    /** This consumer already recorded the event_id: nothing written. */
    DUPLICATE
}
