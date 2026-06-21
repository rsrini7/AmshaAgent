package com.arcclaw.core.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;

/**
 * Shared logic for computing next execution times using cron-utils.
 */
public class CronScheduleUtil {

    private static final CronParser UNIX_PARSER =
            new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));

    public static Instant computeNext(String cronExpression, String timezone) {
        ZonedDateTime next = ExecutionTime.forCron(UNIX_PARSER.parse(cronExpression))
                .nextExecution(ZonedDateTime.now(ZoneId.of(timezone)))
                .orElseThrow(() -> new IllegalStateException("No next execution for: " + cronExpression));
        return next.toInstant();
    }
}
