import type { TimedMetadata } from '../types/Events';

/** Synthetic `TimedMetadataObject.identifier` for the active HLS segment URL (Android). */
export const RNV_MANIFEST_SEGMENT_URL = 'rnv-manifest-segment-url';

/** Synthetic `TimedMetadataObject.identifier` for segment `relativeStartTimeUs` as a decimal string. */
export const RNV_MANIFEST_SEGMENT_START_US = 'rnv-manifest-segment-start-us';

/** Playlist/window duration in microseconds (legacy `onManifestFileChange.duration`). */
export const RNV_MANIFEST_DURATION_US = 'rnv-manifest-duration-us';

/**
 * Shape close to the legacy `onManifestFileChange` / `nativeEvent` payload from pre–v7 forks.
 */
export type ManifestFileChangePayload = {
  filename: string;
  /**
   * Segment start on the media playlist timeline, in **microseconds**.
   */
  filetime: number;
  /**
   * Media playlist duration in **microseconds** (legacy ExoPlayer `durationUs`).
   */
  duration?: number;
};

/**
 * Matches legacy {@link ExoPlayerView} filename extraction for LL-HLS segments.
 * @see Unacademy fork ExoPlayerView.java (ts / m4s URL parsing)
 */
export function legacyFilenameFromSegmentUrl(url: string): string {
  try {
    if (url.includes('ts')) {
      const urlSplit = url.split('-');
      return urlSplit[urlSplit.length - 1].replace('.ts', '');
    }
    if (url.includes('m4s')) {
      return url.replace('.m4s', '');
    }
    return url;
  } catch {
    return url;
  }
}

/**
 * If `data` carries the synthetic HLS segment entries from Android, returns
 * legacy `{ filename, filetime, duration }` for use with live-native handlers.
 */
export function mapTimedMetadataToManifestFileChange(
  data: TimedMetadata
): ManifestFileChangePayload | null {
  let url: string | undefined;
  let startUsRaw: string | undefined;
  let durationUsRaw: string | undefined;

  for (const entry of data.metadata) {
    if (entry.identifier === RNV_MANIFEST_SEGMENT_URL) {
      url = entry.value;
    } else if (entry.identifier === RNV_MANIFEST_SEGMENT_START_US) {
      startUsRaw = entry.value;
    } else if (entry.identifier === RNV_MANIFEST_DURATION_US) {
      durationUsRaw = entry.value;
    }
  }

  if (url === undefined || startUsRaw === undefined) {
    return null;
  }

  const filetime = Number(startUsRaw);
  if (!Number.isFinite(filetime)) {
    return null;
  }

  const payload: ManifestFileChangePayload = {
    filename: legacyFilenameFromSegmentUrl(url),
    filetime,
  };

  if (durationUsRaw !== undefined) {
    const duration = Number(durationUsRaw);
    if (Number.isFinite(duration)) {
      payload.duration = duration;
    }
  }

  return payload;
}
