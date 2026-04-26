#include "audio_stream.h"
#include "wake_word.h"
#include <aaudio/AAudio.h>
#include <android/log.h>

#define LOG_TAG "KateAudio"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static AAudioStream *stream     = NULL;
static int           is_running = 0;

static aaudio_data_callback_result_t audio_callback(
        AAudioStream *s,
        void         *userData,
        void         *audioData,
        int32_t       numFrames) {
    (void)s; (void)userData;
    if (!is_running) return AAUDIO_CALLBACK_RESULT_STOP;
    float *data = (float *)audioData;
    process_audio_frame(data, numFrames);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

void start_audio_stream() {
    if (is_running) return;

    AAudioStreamBuilder *builder = NULL;
    aaudio_result_t result = AAudio_createStreamBuilder(&builder);
    if (result != AAUDIO_OK || !builder) {
        LOGE("Failed to create AAudio builder: %d", result);
        return;
    }

    AAudioStreamBuilder_setFormat(builder,       AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setChannelCount(builder,  1);
    AAudioStreamBuilder_setSampleRate(builder,    16000);
    AAudioStreamBuilder_setDirection(builder,     AAUDIO_DIRECTION_INPUT);
    AAudioStreamBuilder_setSharingMode(builder,   AAUDIO_SHARING_MODE_SHARED);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setDataCallback(builder,  audio_callback, NULL);

    result = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);

    if (result != AAUDIO_OK || !stream) {
        LOGE("Failed to open stream: %d", result);
        return;
    }

    result = AAudioStream_requestStart(stream);
    if (result != AAUDIO_OK) {
        LOGE("Failed to start stream: %d", result);
        AAudioStream_close(stream);
        stream = NULL;
        return;
    }

    is_running = 1;
    LOGD("Kate's ears are open!");
}

void stop_audio_stream() {
    if (!is_running || !stream) return;
    AAudioStream_requestStop(stream);
    AAudioStream_close(stream);
    stream     = NULL;
    is_running = 0;
    LOGD("Kate's ears closed.");
}
