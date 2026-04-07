package com.arcclaw.core.cron;

/**
 * Fired when a new cron task is scheduled. 
 * Allows the background scheduler to pick up the task for precise one-shot execution
 * without waiting for the next poll cycle.
 */
public record CronTaskScheduled(String jobId) {
}
