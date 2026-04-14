import type { TimedMetadata } from '../types/Events';

/** Synthetic `TimedMetadataObject.identifier` for the active HLS segment URL (Android). */
export const RNV_MANIFEST_SEGMENT_URL = 'rnv-manifest-segment-url';

/** Synthetic `TimedMetadataObject.identifier` for segment `relativeStartTimeUs` as a decimal string. */
export const RNV_MANIFEST_SEGMENT_START_US = 'rnv-manifest-segment-start-us';

/**
 * Shape close to the legacy `onManifestFileChange` / `nativeEvent` payload from pre–v7 forks.
 */
export type ManifestFileChangePayload = {
  filename: string;
  /**
   * Segment start on the media playlist timeline, in **microseconds**.
   *
   * @note Older forks sometimes sent a smaller relative delta here; this value is the absolute
   * `relativeStartTimeUs` from ExoPlayer’s HLS manifest, as emitted by {@link RNV_MANIFEST_SEGMENT_START_US}.
   */
  filetime: number;
};

/**
 * If `data` carries the synthetic HLS segment entries from Android, returns
 * `{ filename, filetime }` for use with legacy handlers. Otherwise returns `null`.
 *
 * @example
 * ```ts
 * player.addEventListener('onTimedMetadata', (e) => {
 *   const m = mapTimedMetadataToManifestFileChange(e);
 *   if (m) onManifestFileChange?.(m);
 * });
 * ```
 */
export function mapTimedMetadataToManifestFileChange(
  data: TimedMetadata
): ManifestFileChangePayload | null {
  let url: string | undefined;
  let startUsRaw: string | undefined;

  for (const entry of data.metadata) {
    if (entry.identifier === RNV_MANIFEST_SEGMENT_URL) {
      url = entry.value;
    } else if (entry.identifier === RNV_MANIFEST_SEGMENT_START_US) {
      startUsRaw = entry.value;
    }
  }

  if (url === undefined || startUsRaw === undefined) {
    return null;
  }

  const filetime = Number(startUsRaw);
  if (!Number.isFinite(filetime)) {
    return null;
  }

  return { filename: url, filetime };
}
