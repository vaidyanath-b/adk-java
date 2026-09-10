/** Browser instrumentation only. send receives a JSON string for the CUSTOM frontend protocol. */
export function playbackTrace(send, clock = { epoch: () => Date.now(), monotonic: () => performance.now() }, onLoggingError = () => {}) {
  let sequence = 0;
  let failedReports = 0;
  function emit(kind, detail = {}) {
    try {
      send(JSON.stringify({trace: {...detail, kind, clientSequence: ++sequence,
        clientEpochMs: clock.epoch(), clientMonotonicMs: clock.monotonic()}}));
      return true;
    } catch (error) {
      failedReports++;
      try { onLoggingError(error); } catch (_) { /* A logging error cannot block the real reset. */ }
      return false;
    }
  }
  return {
    emit,
    get failedReports() { return failedReports; },
    checkHealthy() { if (failedReports) throw new Error(`Incomplete playback trace: ${failedReports} reports failed`); },
    /** action performs the application's actual reset and returns measured counters/text. */
    async observe(kind, action) {
      emit(kind + '.BEGIN');
      try {
        const result = await action();
        emit(kind + '.RETURNED', result || {});
        return result;
      } catch (error) {
        emit(kind + '.FAILED', {errorType: error?.name || 'Error'});
        throw error;
      }
    }
  };
}

// Example at the player's interruption callback (player APIs are application-specific):
// tracer.emit('INTERRUPTION.RECEIVED');
// await tracer.observe('PLAYBACK.RESET', () => {
//   const scheduledSourcesStopped = stopAndCountScheduledSources();
//   const queuedFramesCleared = clearAndCountQueuedPcmFrames();
//   return {scheduledSourcesStopped, queuedFramesCleared, lastPlayedSample, sampleRate: 24000};
// });
// await tracer.observe('TRANSCRIPT.TRUNCATE', () => {
//   const before = displayedTranscript;
//   displayedTranscript = truncateUsingActualPlaybackPosition();
//   return {transcriptBefore: before, transcriptAfter: displayedTranscript};
// });
