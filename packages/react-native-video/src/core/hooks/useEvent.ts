import { useEffect, useRef } from 'react';
import type { AllPlayerEvents } from '../types/Events';
import type { VideoPlayerBase } from '../types/VideoPlayerBase';

/**
 * Attaches an event listener to a `VideoPlayer` instance for a specified event.
 *
 * Callback identity is ignored for the subscription effect so inline handlers in
 * RNVideoView do not remove+re-add native listeners every render (BUFFER thrash
 * was dropping progress ticks and fighting the LLHLS stall watchdog).
 *
 * @param player - The player to attach the event to
 * @param event - The name of the event to attach the callback to
 * @param callback - The callback for the event
 */
export const useEvent = <T extends keyof AllPlayerEvents>(
  player: VideoPlayerBase,
  event: T,
  callback: AllPlayerEvents[T]
) => {
  const callbackRef = useRef(callback);
  callbackRef.current = callback;

  useEffect(() => {
    const subscription = player.addEventListener(event, ((...args: unknown[]) => {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (callbackRef.current as any)(...args);
    }) as AllPlayerEvents[T]);

    return () => {
      subscription.remove();
    };
  }, [player, event]);
};
