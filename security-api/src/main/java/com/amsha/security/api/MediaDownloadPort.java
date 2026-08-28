package com.amsha.security.api;

import java.util.ArrayList;
import java.util.List;

import com.amsha.channel.api.MediaAttachment;

/**
 * Port contract for governed, safety-validated media downloads.
 *
 * <p>All external media downloads must pass through implementations of this port
 * to enforce SSRF validation, JIT DNS revalidation, MIME checks, streaming size budgets,
 * turn-level cumulative payload limits, and HITL / outbound network policy gating.
 */
public interface MediaDownloadPort {

    /**
     * Downloads and validates an external media attachment within safety, budget, and SSRF guardrails.
     *
     * @param attachment the media attachment to download
     * @param actorUserId the user initiating the turn
     * @param preApproved true if operator/HITL approved
     * @return downloaded media container (AutoCloseable for deterministic temp file cleanup)
     */
    DownloadedMedia download(MediaAttachment attachment, String actorUserId, boolean preApproved);

    /**
     * Downloads and validates a list of media attachments within turn-level cumulative budgets.
     *
     * @param attachments list of media attachments to download
     * @param actorUserId the user initiating the turn
     * @param preApproved true if operator/HITL approved
     * @return list of downloaded media containers
     */
    default List<DownloadedMedia> downloadAll(
            List<MediaAttachment> attachments, String actorUserId, boolean preApproved) {
        if (attachments == null || attachments.isEmpty()) {
            return List.of();
        }
        long cumulativeBytes = 0;
        List<DownloadedMedia> results = new ArrayList<>(attachments.size());
        try {
            for (MediaAttachment attachment : attachments) {
                DownloadedMedia downloaded = download(attachment, actorUserId, preApproved);
                cumulativeBytes += downloaded.sizeBytes();
                if (cumulativeBytes > MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES) {
                    downloaded.close();
                    throw new IllegalStateException("Cumulative turn media payload exceeded limit of "
                            + MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES + " bytes (actual: " + cumulativeBytes
                            + ")");
                }
                results.add(downloaded);
            }
            return List.copyOf(results);
        } catch (Exception e) {
            for (DownloadedMedia m : results) {
                m.close();
            }
            throw e;
        }
    }
}
