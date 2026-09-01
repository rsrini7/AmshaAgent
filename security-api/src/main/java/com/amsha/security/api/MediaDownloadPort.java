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
     * Returns the effective maximum payload for one turn handled by this port.
     * Implementations with configurable limits should override this method so callers can
     * apply the same limit while composing the provider request.
     */
    default long maxTotalPayloadBytes() {
        return MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES;
    }

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
        long configuredMaxTotalBytes = maxTotalPayloadBytes();
        long maxTotalBytes = configuredMaxTotalBytes > 0
                ? configuredMaxTotalBytes
                : MediaBudgetLimits.DEFAULT_MAX_TOTAL_PAYLOAD_BYTES;
        List<DownloadedMedia> results = new ArrayList<>(attachments.size());
        try {
            for (MediaAttachment attachment : attachments) {
                DownloadedMedia downloaded = download(attachment, actorUserId, preApproved);
                if (downloaded == null) {
                    throw new IllegalStateException("Media download returned no result");
                }
                long sizeBytes = downloaded.sizeBytes();
                if (sizeBytes > maxTotalBytes - cumulativeBytes) {
                    downloaded.close();
                    long actualBytes =
                            cumulativeBytes > Long.MAX_VALUE - sizeBytes ? Long.MAX_VALUE : cumulativeBytes + sizeBytes;
                    throw new IllegalStateException("Cumulative turn media payload exceeded limit of "
                            + maxTotalBytes + " bytes (actual: " + actualBytes
                            + ")");
                }
                cumulativeBytes += sizeBytes;
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
