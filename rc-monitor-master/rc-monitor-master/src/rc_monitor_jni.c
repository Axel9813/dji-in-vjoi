/*
 * rc_monitor_jni.c - JNI bridge for Android
 *
 * Bridges the C rc_monitor library to Java/Kotlin via JNI.
 * The Java side reads raw USB bulk data and passes it here for parsing.
 */

#include <jni.h>
#include <stdlib.h>
#include <android/log.h>
#include "rc_monitor.h"

#define TAG "RcMonitor"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/* Per-instance context shared between JNI calls */
typedef struct {
    rcm_parser_t *parser;
    JavaVM       *jvm;
    jobject       listener_ref;  /* Global ref to Java listener */
    jmethodID    on_state_mid;   /* Cached method ID */
} jni_ctx_t;

/*
 * Global singleton context. Not protected by a mutex — concurrent calls to
 * nativeInit()/nativeDestroy() from different threads would race. In practice,
 * the Java RcMonitor class is used as a singleton from a single thread and
 * UsbRcReader manages the lifecycle, so this is acceptable.
 */
static jni_ctx_t *g_ctx = NULL;

/* Called from the C parser when an RC push packet is decoded */
static void jni_rc_callback(const rc_state_t *state, void *userdata) {
    jni_ctx_t *ctx = (jni_ctx_t *)userdata;
    if (!ctx || !ctx->listener_ref) return;

    JNIEnv *env = NULL;
    int need_detach = 0;

    /* Attach current thread to JVM if needed (USB read may be on a native thread) */
    jint status = (*ctx->jvm)->GetEnv(ctx->jvm, (void **)&env, JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        if ((*ctx->jvm)->AttachCurrentThread(ctx->jvm, &env, NULL) != JNI_OK) {
            LOGE("Failed to attach thread to JVM");
            return;
        }
        need_detach = 1;
    } else if (status != JNI_OK) {
        LOGE("GetEnv failed: %d", status);
        return;
    }

    /* Call listener.onRcState(
     *   pause, gohome, shutter, record,
     *   custom1, custom2, custom3,
     *   fiveDUp, fiveDDown, fiveDLeft, fiveDRight, fiveDCenter,
     *   flightMode,
     *   stickRightH, stickRightV, stickLeftH, stickLeftV,
     *   leftWheel, rightWheel, rightWheelDelta
     * )
     */
    (*env)->CallVoidMethod(env, ctx->listener_ref, ctx->on_state_mid,
        (jboolean)state->pause,
        (jboolean)state->gohome,
        (jboolean)state->shutter,
        (jboolean)state->record,
        (jboolean)state->custom1,
        (jboolean)state->custom2,
        (jboolean)state->custom3,
        (jboolean)state->five_d.up,
        (jboolean)state->five_d.down,
        (jboolean)state->five_d.left,
        (jboolean)state->five_d.right,
        (jboolean)state->five_d.center,
        (jint)state->flight_mode,
        (jint)state->stick_right.horizontal,
        (jint)state->stick_right.vertical,
        (jint)state->stick_left.horizontal,
        (jint)state->stick_left.vertical,
        (jint)state->left_wheel,
        (jint)state->right_wheel,
        (jint)state->right_wheel_delta
    );

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }

    if (need_detach) {
        (*ctx->jvm)->DetachCurrentThread(ctx->jvm);
    }
}

/*
 * Class:     com_dji_rcmonitor_RcMonitor
 * Method:    nativeInit
 * Signature: (Lcom/dji/rcmonitor/RcMonitor$RcStateListener;)Z
 */
JNIEXPORT jboolean JNICALL
Java_com_dji_rcmonitor_RcMonitor_nativeInit(JNIEnv *env, jobject thiz, jobject listener) {
    if (g_ctx) {
        LOGE("Already initialized");
        return JNI_FALSE;
    }

    /* Find listener method */
    jclass cls = (*env)->GetObjectClass(env, listener);
    if (!cls) {
        LOGE("Failed to get listener class");
        return JNI_FALSE;
    }

    jmethodID mid = (*env)->GetMethodID(env, cls, "onRcState",
        "(ZZZZZZZZZZZZIIIIIIII)V");
    if (!mid) {
        LOGE("Failed to find onRcState method");
        return JNI_FALSE;
    }

    jni_ctx_t *ctx = (jni_ctx_t *)calloc(1, sizeof(jni_ctx_t));
    if (!ctx) return JNI_FALSE;

    (*env)->GetJavaVM(env, &ctx->jvm);
    ctx->listener_ref = (*env)->NewGlobalRef(env, listener);
    ctx->on_state_mid = mid;

    ctx->parser = rcm_create(jni_rc_callback, ctx);
    if (!ctx->parser) {
        (*env)->DeleteGlobalRef(env, ctx->listener_ref);
        free(ctx);
        return JNI_FALSE;
    }

    g_ctx = ctx;
    LOGD("RC Monitor initialized");
    return JNI_TRUE;
}

/*
 * Class:     com_dji_rcmonitor_RcMonitor
 * Method:    nativeFeed
 * Signature: ([BI)I
 */
JNIEXPORT jint JNICALL
Java_com_dji_rcmonitor_RcMonitor_nativeFeed(JNIEnv *env, jobject thiz,
                                             jbyteArray data, jint length) {
    if (!g_ctx || !g_ctx->parser) return 0;

    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (!buf) return 0;

    int decoded = rcm_feed(g_ctx->parser, (const uint8_t *)buf, (size_t)length);

    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    return decoded;
}

/*
 * Class:     com_dji_rcmonitor_RcMonitor
 * Method:    nativeFeedDirect
 * Signature: ([BI)I
 *
 * Feed raw 17-byte payload directly (bypasses DUML framing).
 * Use this if you already extract the RC push payload elsewhere.
 */
JNIEXPORT jint JNICALL
Java_com_dji_rcmonitor_RcMonitor_nativeFeedDirect(JNIEnv *env, jobject thiz,
                                                    jbyteArray payload, jint length) {
    if (!g_ctx) return -1;

    jbyte *buf = (*env)->GetByteArrayElements(env, payload, NULL);
    if (!buf) return -1;

    rc_state_t state;
    int ret = rcm_parse_payload((const uint8_t *)buf, (size_t)length, &state);

    (*env)->ReleaseByteArrayElements(env, payload, buf, JNI_ABORT);

    if (ret == 0) {
        jni_rc_callback(&state, g_ctx);
        return 1;
    }
    return 0;
}

/*
 * Class:     com_dji_rcmonitor_RcMonitor
 * Method:    nativeReset
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_com_dji_rcmonitor_RcMonitor_nativeReset(JNIEnv *env, jobject thiz) {
    if (g_ctx && g_ctx->parser)
        rcm_reset(g_ctx->parser);
}

/*
 * Class:     com_dji_rcmonitor_RcMonitor
 * Method:    nativeDestroy
 * Signature: ()V
 */
JNIEXPORT void JNICALL
Java_com_dji_rcmonitor_RcMonitor_nativeDestroy(JNIEnv *env, jobject thiz) {
    if (!g_ctx) return;

    rcm_destroy(g_ctx->parser);

    if (g_ctx->listener_ref)
        (*env)->DeleteGlobalRef(env, g_ctx->listener_ref);

    free(g_ctx);
    g_ctx = NULL;
    LOGD("RC Monitor destroyed");
}

/*
 * Class:     com_dji_rcmonitor_RcMonitor
 * Method:    nativeBuildEnableCmd
 * Signature: (I)[B
 *
 * Build a DUML enable command packet. Does not require the parser to be initialized.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_dji_rcmonitor_RcMonitor_nativeBuildEnableCmd(JNIEnv *env, jclass clazz, jint seq) {
    uint8_t buf[DUML_MAX_FRAME_LEN];
    int len = rcm_build_enable_cmd(buf, sizeof(buf), (uint16_t)seq);
    if (len < 0) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, len);
    if (result)
        (*env)->SetByteArrayRegion(env, result, 0, len, (const jbyte *)buf);
    return result;
}

/*
 * Class:     com_dji_rcmonitor_RcMonitor
 * Method:    nativeBuildChannelRequest
 * Signature: (I)[B
 *
 * Build a DUML channel data request packet. Does not require the parser to be initialized.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_dji_rcmonitor_RcMonitor_nativeBuildChannelRequest(JNIEnv *env, jclass clazz, jint seq) {
    uint8_t buf[DUML_MAX_FRAME_LEN];
    int len = rcm_build_channel_request(buf, sizeof(buf), (uint16_t)seq);
    if (len < 0) return NULL;

    jbyteArray result = (*env)->NewByteArray(env, len);
    if (result)
        (*env)->SetByteArrayRegion(env, result, 0, len, (const jbyte *)buf);
    return result;
}
