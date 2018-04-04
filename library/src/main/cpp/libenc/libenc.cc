#include <jni.h>

#include <android/log.h>
#include <libyuv.h>
//#include <x264.h>
#include <string.h>

#define LIBENC_LOGD(...) ((void)__android_log_print(ANDROID_LOG_DEBUG, "libenc", __VA_ARGS__))
#define LIBENC_LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO , "libenc", __VA_ARGS__))
#define LIBENC_LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN , "libenc", __VA_ARGS__))
#define LIBENC_LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, "libenc", __VA_ARGS__))

#define LIBENC_ARRAY_ELEMS(a)  (sizeof(a) / sizeof(a[0]))

using namespace libyuv;

struct YuvFrame {
    int width;
    int height;
    uint8_t *data;
    uint8_t *y;
    uint8_t *u;
    uint8_t *v;
    uint8_t *alpha;
};

typedef struct x264_context {
    // encode parameter
//    x264_param_t params;
//    x264_t *encoder;
//    x264_picture_t picture;
    bool global_nal_header;
    // input
    int width;
    int height;
    int bitrate;
    int fps;
    int gop;
    char preset[16];
    // output
    int64_t pts;
    int dts;
    bool is_key_frame;
} x264_context;

static JavaVM *jvm;
static JNIEnv *jenv;

static struct x264_context x264_ctx;
static uint8_t h264_es[1024 * 1024];

static const int SRC_COLOR_FMT = FOURCC_RGBA;
static const int DST_COLOR_FMT = FOURCC_NV12;

static struct YuvFrame i420_rotated_frame;
static struct YuvFrame i420_scaled_frame;
static struct YuvFrame i420_overlay_frame_a;
static struct YuvFrame i420_overlay_frame_b;
static struct YuvFrame nv12_frame;
static struct YuvFrame i420_src_frame;

static struct YuvFrame* overlay = NULL;

/**
 * Convert frames to I420
 * @param src_frame
 * @param src_width
 * @param src_height
 * @param crop_x
 * @param crop_y
 * @param crop_width
 * @param crop_height
 * @param need_flip
 * @param rotate_degree
 * @param format
 * @return
 */
static bool convert_to_i420(uint8_t *src_frame, jint src_width, jint src_height,
                            jint crop_x, jint crop_y, jint crop_width,
                            jint crop_height,
                            jboolean need_flip, jint rotate_degree, int format) {

    int y_size = src_width * src_height;
    int r_crop_width = crop_width;
    int r_crop_height = crop_height;

    if (rotate_degree % 180 == 0) {
        if (i420_rotated_frame.width != src_width || i420_rotated_frame.height != src_height) {
            free(i420_rotated_frame.data);
            i420_rotated_frame.data = (uint8_t *) malloc(y_size * 3 / 2);
            i420_rotated_frame.y = i420_rotated_frame.data;
            i420_rotated_frame.u = i420_rotated_frame.y + y_size;
            i420_rotated_frame.v = i420_rotated_frame.u + y_size / 4;

            i420_rotated_frame.width = src_width;
            i420_rotated_frame.height = src_height;
        }
    } else {
        if (i420_rotated_frame.width != src_height || i420_rotated_frame.height != src_width) {
            free(i420_rotated_frame.data);
            i420_rotated_frame.data = (uint8_t *) malloc(y_size * 3 / 2);
            i420_rotated_frame.y = i420_rotated_frame.data;
            i420_rotated_frame.u = i420_rotated_frame.y + y_size;
            i420_rotated_frame.v = i420_rotated_frame.u + y_size / 4;

            i420_rotated_frame.width = src_height;
            i420_rotated_frame.height = src_width;

            r_crop_width = crop_height;
            r_crop_height = crop_width;
        }
    }

    jint ret = ConvertToI420(src_frame, y_size,
                             i420_rotated_frame.y, r_crop_width,
                             i420_rotated_frame.u, r_crop_width / 2,
                             i420_rotated_frame.v, r_crop_width / 2,
                             crop_x, crop_y,
                             src_width, need_flip ? -src_height : src_height,
                             r_crop_width, r_crop_height,
                             (RotationMode) rotate_degree, format);
    if (ret < 0) {
        LIBENC_LOGE("ConvertToI420 failure");
        return false;
    }

    ret = I420Scale(i420_rotated_frame.y, i420_rotated_frame.width,
                    i420_rotated_frame.u, r_crop_width / 2,
                    i420_rotated_frame.v, r_crop_width / 2,
                    r_crop_width, r_crop_height,
                    i420_scaled_frame.y, i420_scaled_frame.width,
                    i420_scaled_frame.u, i420_scaled_frame.width / 2,
                    i420_scaled_frame.v, i420_scaled_frame.width / 2,
                    i420_scaled_frame.width, i420_scaled_frame.height,
                    kFilterNone);

    if (ret < 0) {
        LIBENC_LOGE("I420Scale failure");
        return false;
    }

    return true;
}

/**
 * Crop/scale I420 frames
 * @param src_y
 * @param src_u
 * @param src_v
 * @param src_width
 * @param src_height
 * @param crop_x
 * @param crop_y
 * @param crop_width
 * @param crop_height
 * @param need_flip
 * @param rotate_degree
 * @param format
 * @return
 */
static bool
YUV420_888toI420(uint8_t *src_y, uint8_t *src_u, uint8_t *src_v, jint src_width, jint src_height,
                 jint crop_x, jint crop_y, jint crop_width,
                 jint crop_height,
                 jboolean need_flip, jint rotate_degree) {

    int y_size = src_width * src_height;
    int r_crop_width = crop_width;
    int r_crop_height = crop_height;

    if (rotate_degree % 180 == 0) {
        if (i420_rotated_frame.width != src_width || i420_rotated_frame.height != src_height) {
            free(i420_rotated_frame.data);
            i420_rotated_frame.data = (uint8_t *) malloc(y_size * 3 / 2);
            i420_rotated_frame.y = i420_rotated_frame.data;
            i420_rotated_frame.u = i420_rotated_frame.y + y_size;
            i420_rotated_frame.v = i420_rotated_frame.u + y_size / 4;

            i420_rotated_frame.width = src_width;
            i420_rotated_frame.height = src_height;
        }
    } else {
        if (i420_rotated_frame.width != src_height || i420_rotated_frame.height != src_width) {
            free(i420_rotated_frame.data);
            i420_rotated_frame.data = (uint8_t *) malloc(y_size * 3 / 2);
            i420_rotated_frame.y = i420_rotated_frame.data;
            i420_rotated_frame.u = i420_rotated_frame.y + y_size;
            i420_rotated_frame.v = i420_rotated_frame.u + y_size / 4;

            i420_rotated_frame.width = src_height;
            i420_rotated_frame.height = src_width;
        }

        r_crop_width = crop_height;
        r_crop_height = crop_width;
    }

    int halfwidth = src_width / 2;
    int halfheight = src_height / 2;

    // Convert pixel stride of 2 to 1 for UV planes
    if (i420_src_frame.width != src_width || i420_src_frame.height != src_height) {
        free(i420_src_frame.u);
        free(i420_src_frame.v);
        i420_src_frame.u = (uint8_t *) malloc(y_size / 4);
        i420_src_frame.v = (uint8_t *) malloc(y_size / 4);
        i420_src_frame.width = src_width;
        i420_src_frame.height = src_height;
    }
    int i = 0;
    int j;
    for (int y = 0; y < halfheight; y++) {
        for (int x = 0; x < halfwidth; x++) {
            j = y * src_width + x * 2;
            i420_src_frame.u[i] = src_u[j];
            i420_src_frame.v[i] = src_v[j];
            i++;
        }
    }

    // Crop frame
    j = src_width * (crop_y >> 2) + (crop_x >> 1);
    const uint8 *src_yc = src_y + (src_width * crop_y + crop_x);
    const uint8 *src_uc = i420_src_frame.u + j;
    const uint8 *src_vc = i420_src_frame.v + j;

    // Rotate frame
    jint ret = I420Rotate(src_yc, src_width,
                          src_uc, src_width / 2,
                          src_vc, src_width / 2,
                          i420_rotated_frame.y, r_crop_width,
                          i420_rotated_frame.u, r_crop_width / 2,
                          i420_rotated_frame.v, r_crop_width / 2,
                          r_crop_width, r_crop_height, (RotationMode) rotate_degree);

    if (ret < 0) {
        LIBENC_LOGE("I420Rotate failure");
        return false;
    }

    // Scale frame
    ret = I420Scale(i420_rotated_frame.y, r_crop_width,
                    i420_rotated_frame.u, r_crop_width / 2,
                    i420_rotated_frame.v, r_crop_width / 2,
                    r_crop_width, r_crop_height,
                    i420_scaled_frame.y, i420_scaled_frame.width,
                    i420_scaled_frame.u, i420_scaled_frame.width / 2,
                    i420_scaled_frame.v, i420_scaled_frame.width / 2,
                    i420_scaled_frame.width, i420_scaled_frame.height,
                    kFilterNone);

    if (ret < 0) {
        LIBENC_LOGE("I420Scale failure");
        return false;
    }

    if (overlay!=NULL) {
        ret = I420Blend(overlay->y, overlay->width,
                        overlay->u, overlay->width / 2,
                        overlay->v, overlay->width / 2,
                        i420_scaled_frame.y, i420_scaled_frame.width,
                        i420_scaled_frame.u, i420_scaled_frame.width / 2,
                        i420_scaled_frame.v, i420_scaled_frame.width / 2,
                        overlay->alpha, overlay->width,
                        i420_scaled_frame.y, i420_scaled_frame.width,
                        i420_scaled_frame.u, i420_scaled_frame.width / 2,
                        i420_scaled_frame.v, i420_scaled_frame.width / 2,
                        i420_scaled_frame.width,
                        i420_scaled_frame.height
        );

        if (ret < 0) {
            LIBENC_LOGE("I420Blend failure");
            return false;
        }
    }

    return true;
}

static void libenc_setEncoderBitrate(JNIEnv *env, jobject thiz, jint bitrate) {
    x264_ctx.bitrate = bitrate / 1000;  // kbps
}

static void libenc_setEncoderFps(JNIEnv *env, jobject thiz, jint fps) {
    x264_ctx.fps = fps;
}

static void libenc_setEncoderGop(JNIEnv *env, jobject thiz, jint gop_size) {
    x264_ctx.gop = gop_size;
}

static void libenc_setEncoderPreset(JNIEnv *env, jobject thiz, jstring preset) {
    const char *enc_preset = env->GetStringUTFChars(preset, NULL);
    strcpy(x264_ctx.preset, enc_preset);
    env->ReleaseStringUTFChars(preset, enc_preset);
}

static void
libenc_setEncoderResolution(JNIEnv *env, jobject thiz, jint out_width, jint out_height) {
    int y_size = out_width * out_height;

    if (i420_scaled_frame.width != out_width || i420_scaled_frame.height != out_height) {
        free(i420_scaled_frame.data);
        i420_scaled_frame.width = out_width;
        i420_scaled_frame.height = out_height;
        i420_scaled_frame.data = (uint8_t *) malloc(y_size * 3 / 2);
        i420_scaled_frame.y = i420_scaled_frame.data;
        i420_scaled_frame.u = i420_scaled_frame.y + y_size;
        i420_scaled_frame.v = i420_scaled_frame.u + y_size / 4;
    }

    if (i420_overlay_frame_a.width != out_width || i420_overlay_frame_a.height != out_height) {
        free(i420_overlay_frame_a.data);
        i420_overlay_frame_a.width = out_width;
        i420_overlay_frame_a.height = out_height;
        i420_overlay_frame_a.data = (uint8_t *) malloc(y_size * 3 / 2);
        i420_overlay_frame_a.y = i420_overlay_frame_a.data;
        i420_overlay_frame_a.u = i420_overlay_frame_a.y + y_size;
        i420_overlay_frame_a.v = i420_overlay_frame_a.u + y_size / 4;
        i420_overlay_frame_a.alpha = (uint8_t *) malloc(y_size);
    }

    if (i420_overlay_frame_b.width != out_width || i420_overlay_frame_b.height != out_height) {
        free(i420_overlay_frame_b.data);
        i420_overlay_frame_b.width = out_width;
        i420_overlay_frame_b.height = out_height;
        i420_overlay_frame_b.data = (uint8_t *) malloc(y_size * 3 / 2);
        i420_overlay_frame_b.y = i420_overlay_frame_b.data;
        i420_overlay_frame_b.u = i420_overlay_frame_b.y + y_size;
        i420_overlay_frame_b.v = i420_overlay_frame_b.u + y_size / 4;
        i420_overlay_frame_b.alpha = (uint8_t *) malloc(y_size);
    }

    if (nv12_frame.width != out_width || nv12_frame.height != out_height) {
        free(nv12_frame.data);
        nv12_frame.width = out_width;
        nv12_frame.height = out_height;
        nv12_frame.data = (uint8_t *) malloc(y_size * 3 / 2);
        nv12_frame.y = nv12_frame.data;
        nv12_frame.u = nv12_frame.y + y_size;
        nv12_frame.v = nv12_frame.u + y_size / 4;
    }

    x264_ctx.width = out_width;
    x264_ctx.height = out_height;
}

// For COLOR_FormatYUV420Planar
static jbyteArray libenc_RGBAToI420(JNIEnv *env, jobject thiz, jbyteArray frame, jint src_width,
                                    jint src_height, jboolean need_flip, jint rotate_degree) {
    jbyte *rgba_frame = env->GetByteArrayElements(frame, NULL);

    if (!convert_to_i420((uint8_t *) rgba_frame, src_width, src_height, 0, 0, src_width, src_height,
                         need_flip,
                         rotate_degree,
                         FOURCC_RGBA)) {
        return NULL;
    }

    int y_size = i420_scaled_frame.width * i420_scaled_frame.height;
    jbyteArray i420Frame = env->NewByteArray(y_size * 3 / 2);
    env->SetByteArrayRegion(i420Frame, 0, y_size * 3 / 2, (jbyte *) i420_scaled_frame.data);

    env->ReleaseByteArrayElements(frame, rgba_frame, JNI_ABORT);
    return i420Frame;
}

static jbyteArray
libenc_NV21ToNV12(JNIEnv *env, jobject thiz, jbyteArray frame, jint src_width,
                  jint src_height, jboolean need_flip, jint rotate_degree,
                  jint crop_x, jint crop_y, jint crop_width, jint crop_height) {
    jbyte *rgba_frame = env->GetByteArrayElements(frame, NULL);

    if (!convert_to_i420((uint8_t *) rgba_frame, src_width, src_height,
                         crop_x, crop_y, crop_width, crop_height,
                         need_flip, rotate_degree, FOURCC_NV21)) {
        return NULL;
    }

    int ret = ConvertFromI420(i420_scaled_frame.y, i420_scaled_frame.width,
                              i420_scaled_frame.u, i420_scaled_frame.width / 2,
                              i420_scaled_frame.v, i420_scaled_frame.width / 2,
                              nv12_frame.data, nv12_frame.width,
                              nv12_frame.width, nv12_frame.height,
                              FOURCC_NV12);
    if (ret < 0) {
        LIBENC_LOGE("ConvertFromI420 failure");
        return NULL;
    }

    int y_size = nv12_frame.width * nv12_frame.height;
    jbyteArray nv12Frame = env->NewByteArray(y_size * 3 / 2);
    env->SetByteArrayRegion(nv12Frame, 0, y_size * 3 / 2, (jbyte *) nv12_frame.data);

    env->ReleaseByteArrayElements(frame, rgba_frame, JNI_ABORT);
    return nv12Frame;
}

static jbyteArray
libenc_NV21ToI420(JNIEnv *env, jobject thiz, jbyteArray frame, jint src_width,
                  jint src_height, jboolean need_flip, jint rotate_degree,
                  jint crop_x, jint crop_y, jint crop_width, jint crop_height) {
    jbyte *argb_frame = env->GetByteArrayElements(frame, NULL);

    if (!convert_to_i420((uint8_t *) argb_frame, src_width, src_height,
                         crop_x, crop_y, crop_width, crop_height,
                         need_flip, rotate_degree, FOURCC_NV21)) {
        return NULL;
    }

    int y_size = i420_scaled_frame.width * i420_scaled_frame.height;
    jbyteArray i420Frame = env->NewByteArray(y_size * 3 / 2);
    env->SetByteArrayRegion(i420Frame, 0, y_size * 3 / 2, (jbyte *) i420_scaled_frame.data);

    env->ReleaseByteArrayElements(frame, argb_frame, JNI_ABORT);
    return i420Frame;
}

// For Bitmap.getPixels() ARGB_8888
static jbyteArray
libenc_ARGBToI420(JNIEnv *env, jobject thiz, jintArray frame, jint src_width,
                  jint src_height, jboolean need_flip, jint rotate_degree,
                  jint crop_x, jint crop_y, jint crop_width, jint crop_height) {
    jint *argb_frame = env->GetIntArrayElements(frame, NULL);

    if (!convert_to_i420((uint8_t *) argb_frame, src_width, src_height,
                         crop_x, crop_y, crop_width, crop_height,
                         need_flip, rotate_degree, FOURCC_ARGB)) {
        return NULL;
    }

    int y_size = i420_scaled_frame.width * i420_scaled_frame.height;
    jbyteArray i420Frame = env->NewByteArray(y_size * 3 / 2);
    env->SetByteArrayRegion(i420Frame, 0, y_size * 3 / 2, (jbyte *) i420_scaled_frame.data);

    env->ReleaseIntArrayElements(frame, argb_frame, JNI_ABORT);
    return i420Frame;
}

// For ImageReader frame
static jbyteArray
libenc_YUV420_888toI420(JNIEnv *env, jobject thiz, jbyteArray y_frame, jbyteArray u_frame,
                        jbyteArray v_frame, jint src_width,
                        jint src_height, jboolean need_flip, jint rotate_degree,
                        jint crop_x, jint crop_y, jint crop_width, jint crop_height) {

    jbyte *y_framed = env->GetByteArrayElements(y_frame, NULL);
    jbyte *u_framed = env->GetByteArrayElements(u_frame, NULL);
    jbyte *v_framed = env->GetByteArrayElements(v_frame, NULL);

    jbyteArray i420Frame = env->NewByteArray(0);

    if (YUV420_888toI420((uint8_t *) y_framed,
                         (uint8_t *) u_framed,
                         (uint8_t *) v_framed,
                         src_width,
                         src_height,
                         crop_x,
                         crop_y,
                         crop_width,
                         crop_height,
                         need_flip,
                         rotate_degree)) {

        int y_size = i420_scaled_frame.width * i420_scaled_frame.height;
        i420Frame = env->NewByteArray(y_size * 3 / 2);
        env->SetByteArrayRegion(i420Frame, 0, y_size * 3 / 2, (jbyte *) i420_scaled_frame.data);
    }

    env->ReleaseByteArrayElements(y_frame, y_framed, JNI_ABORT);
    env->ReleaseByteArrayElements(u_frame, u_framed, JNI_ABORT);
    env->ReleaseByteArrayElements(v_frame, v_framed, JNI_ABORT);

    return i420Frame;
}

// Set overlay
static void
libenc_ARGBToOverlay(JNIEnv *env, jobject thiz, jintArray frame, jint src_width,
                     jint src_height, jboolean need_flip, jint rotate_degree) {

    if (frame == NULL) {
        overlay = NULL;
        return;
    }

    jint *argb_frame = env->GetIntArrayElements(frame, NULL);
    uint8_t *data = (uint8_t *) argb_frame;
    int y_size = src_width * src_height;
    struct YuvFrame* new_overlay;

    if (overlay==&i420_overlay_frame_a) new_overlay = &i420_overlay_frame_b;
    else new_overlay = &i420_overlay_frame_a;

    jint ret = ConvertToI420(data, y_size,
                             new_overlay->y, src_width,
                             new_overlay->u, src_width / 2,
                             new_overlay->v, src_width / 2,
                             0, 0,
                             src_width, need_flip ? -src_height : src_height,
                             src_width, src_height,
                             (RotationMode) rotate_degree, FOURCC_ARGB);

    // Copy alpha
    for (int i = 0; i < y_size; i++) {
        new_overlay->alpha[i] = argb_frame[i] >> 24;
    }

    if (ret < 0) {
        LIBENC_LOGE("ConvertOverlayToI420 failure");
    } else {
        overlay = new_overlay;
    }

    env->ReleaseIntArrayElements(frame, argb_frame, JNI_ABORT);
}

// For COLOR_FormatYUV420SemiPlanar
static jbyteArray libenc_RGBAToNV12(JNIEnv *env, jobject thiz, jbyteArray frame, jint src_width,
                                    jint src_height, jboolean need_flip, jint rotate_degree) {
    jbyte *rgba_frame = env->GetByteArrayElements(frame, NULL);

    if (!convert_to_i420((uint8_t *) rgba_frame, src_width, src_height, 0, 0, src_width,
                         src_height,
                         need_flip,
                         rotate_degree,
                         FOURCC_RGBA)) {
        return NULL;
    }

    int ret = ConvertFromI420(i420_scaled_frame.y, i420_scaled_frame.width,
                              i420_scaled_frame.u, i420_scaled_frame.width / 2,
                              i420_scaled_frame.v, i420_scaled_frame.width / 2,
                              nv12_frame.data, nv12_frame.width,
                              nv12_frame.width, nv12_frame.height,
                              DST_COLOR_FMT);
    if (ret < 0) {
        LIBENC_LOGE("ConvertFromI420 failure");
        return NULL;
    }

    int y_size = nv12_frame.width * nv12_frame.height;
    jbyteArray nv12Frame = env->NewByteArray(y_size * 3 / 2);
    env->SetByteArrayRegion(nv12Frame, 0, y_size * 3 / 2, (jbyte *) nv12_frame.data);

    env->ReleaseByteArrayElements(frame, rgba_frame, JNI_ABORT);
    return nv12Frame;
}

// For Bitmap.getPixels() ARGB_8888
static jbyteArray
libenc_ARGBToNV12(JNIEnv *env, jobject thiz, jintArray frame, jint src_width,
                  jint src_height, jboolean need_flip, jint rotate_degree,
                  jint crop_x, jint crop_y, jint crop_width, jint crop_height) {
    jint *argb_frame = env->GetIntArrayElements(frame, NULL);

    if (!convert_to_i420((uint8_t *) argb_frame, src_width, src_height,
                         crop_x, crop_y, crop_width, crop_height,
                         need_flip, rotate_degree, FOURCC_ARGB)) {
        return NULL;
    }

    int ret = ConvertFromI420(i420_scaled_frame.y, i420_scaled_frame.width,
                              i420_scaled_frame.u, i420_scaled_frame.width / 2,
                              i420_scaled_frame.v, i420_scaled_frame.width / 2,
                              nv12_frame.data, nv12_frame.width,
                              nv12_frame.width, nv12_frame.height,
                              DST_COLOR_FMT);
    if (ret < 0) {
        LIBENC_LOGE("ConvertFromI420 failure");
        return NULL;
    }

    int y_size = nv12_frame.width * nv12_frame.height;
    jbyteArray nv12Frame = env->NewByteArray(y_size * 3 / 2);
    env->SetByteArrayRegion(nv12Frame, 0, y_size * 3 / 2, (jbyte *) nv12_frame.data);

    env->ReleaseIntArrayElements(frame, argb_frame, JNI_ABORT);
    return nv12Frame;
}

//static int encode_nals(const x264_nal_t *nals, int nnal) {
//    int i;
//    uint8_t *p = h264_es;
//
//    for (i = 0; i < nnal; i++) {
//        memcpy(p, nals[i].p_payload, nals[i].i_payload);
//        p += nals[i].i_payload;
//    }
//
//    return p - h264_es;
//}

//static int encode_global_nal_header() {
//    int nnal;
//    x264_nal_t *nals;
//
//    x264_ctx.global_nal_header = false;
//    x264_encoder_headers(x264_ctx.encoder, &nals, &nnal);
//    return encode_nals(nals, nnal);
//}

//static int x264_encode(struct YuvFrame *i420_frame, int64_t pts) {
//    int out_len, nnal;
//    x264_nal_t *nal;
//    x264_picture_t pic_out;
//    int y_size = i420_frame->width * i420_frame->height;
//
//    x264_ctx.picture.img.i_csp = X264_CSP_I420;
//    x264_ctx.picture.img.i_plane = 3;
//    x264_ctx.picture.img.plane[0] = i420_frame->y;
//    x264_ctx.picture.img.i_stride[0] = i420_frame->width;
//    x264_ctx.picture.img.plane[1] = i420_frame->u;
//    x264_ctx.picture.img.i_stride[1] = i420_frame->width / 2;
//    x264_ctx.picture.img.plane[2] = i420_frame->v;
//    x264_ctx.picture.img.i_stride[2] = i420_frame->width / 2;
//    x264_ctx.picture.i_pts = pts;
//    x264_ctx.picture.i_type = X264_TYPE_AUTO;
//
//    if (x264_encoder_encode(x264_ctx.encoder, &nal, &nnal, &x264_ctx.picture, &pic_out) < 0) {
//        LIBENC_LOGE("Fail to encode in x264");
//        return -1;
//    }
//
//    x264_ctx.pts = pic_out.i_pts;
//    x264_ctx.dts = pic_out.i_dts;
//    x264_ctx.is_key_frame = pic_out.i_type == X264_TYPE_IDR;
//
//    return encode_nals(nal, nnal);
//}

static jint libenc_RGBASoftEncode(JNIEnv *env, jobject thiz, jbyteArray frame, jint src_width,
                                  jint src_height, jboolean need_flip, jint rotate_degree,
                                  jlong pts) {
//    jbyte *rgba_frame = env->GetByteArrayElements(frame, NULL);
//
//    if (!convert_to_i420((uint8_t *) rgba_frame, src_width, src_height, 0, 0, src_width, src_height,
//                         need_flip,
//                         rotate_degree,
//                         FOURCC_RGBA)) {
//        return JNI_ERR;
//    }
//
//    int es_len = x264_ctx.global_nal_header ? encode_global_nal_header() : x264_encode(
//            &i420_scaled_frame, pts);
//    if (es_len <= 0) {
//        LIBENC_LOGE("Fail to encode nalu");
//        return JNI_ERR;
//    }
//
//    jbyteArray outputFrame = env->NewByteArray(es_len);
//    env->SetByteArrayRegion(outputFrame, 0, es_len, (jbyte *) h264_es);
//
//    jclass clz = env->GetObjectClass(thiz);
//    jmethodID mid = env->GetMethodID(clz, "onSoftEncodedData", "([BJZ)V");
//    env->CallVoidMethod(thiz, mid, outputFrame, x264_ctx.pts, x264_ctx.is_key_frame);
//
//    env->ReleaseByteArrayElements(frame, rgba_frame, JNI_ABORT);
    return JNI_OK;
}

static void libenc_closeSoftEncoder(JNIEnv *env, jobject thiz) {
//    int nnal;
//    x264_nal_t *nal;
//    x264_picture_t pic_out;
//
//    if (x264_ctx.encoder != NULL) {
//        while (x264_encoder_delayed_frames(x264_ctx.encoder)) {
//            x264_encoder_encode(x264_ctx.encoder, &nal, &nnal, NULL, &pic_out);
//        }
//        x264_encoder_close(x264_ctx.encoder);
//        x264_ctx.encoder = NULL;
//    }
}

static jboolean libenc_openSoftEncoder(JNIEnv *env, jobject thiz) {
    // presetting
//    x264_param_default_preset(&x264_ctx.params, x264_ctx.preset, "zerolatency");
//
//    x264_ctx.params.b_repeat_headers = 0;
//    x264_ctx.global_nal_header = true;
//
//    // resolution
//    x264_ctx.params.i_width = x264_ctx.width;
//    x264_ctx.params.i_height = x264_ctx.height;
//
//    // bitrate
//    x264_ctx.params.rc.i_bitrate = x264_ctx.bitrate;  // kbps
//    x264_ctx.params.rc.i_rc_method = X264_RC_ABR;
//
//    // fps
//    x264_ctx.params.i_fps_num = x264_ctx.fps;
//    x264_ctx.params.i_fps_den = 1;
//
//    // gop
//    x264_ctx.params.i_keyint_max = x264_ctx.gop;
//
//    if (x264_param_apply_profile(&x264_ctx.params, "baseline") < 0) {
//        LIBENC_LOGE("Fail to apply profile");
//        return JNI_FALSE;
//    }
//
//    x264_ctx.encoder = x264_encoder_open(&x264_ctx.params);
//    if (x264_ctx.encoder == NULL) {
//        LIBENC_LOGE("Fail to open x264 encoder!");
//        return JNI_FALSE;
//    }

    return JNI_TRUE;
}

static JNINativeMethod libenc_methods[] = {
        {"setEncoderResolution", "(II)V",                 (void *) libenc_setEncoderResolution},
        {"setEncoderFps",        "(I)V",                  (void *) libenc_setEncoderFps},
        {"setEncoderGop",        "(I)V",                  (void *) libenc_setEncoderGop},
        {"setEncoderBitrate",    "(I)V",                  (void *) libenc_setEncoderBitrate},
        {"setEncoderPreset",     "(Ljava/lang/String;)V", (void *) libenc_setEncoderPreset},
        {"RGBAToI420",           "([BIIZI)[B",            (void *) libenc_RGBAToI420},
        {"RGBAToNV12",           "([BIIZI)[B",            (void *) libenc_RGBAToNV12},
        {"ARGBToNV12",           "([IIIZIIIII)[B",        (void *) libenc_ARGBToNV12},
        {"ARGBToI420",           "([IIIZIIIII)[B",        (void *) libenc_ARGBToI420},
        {"ARGBToOverlay",        "([IIIZI)V",             (void *) libenc_ARGBToOverlay},
        {"YUV420_888toI420",     "([B[B[BIIZIIIII)[B",    (void *) libenc_YUV420_888toI420},
        {"NV21ToNV12",           "([BIIZIIIII)[B",        (void *) libenc_NV21ToNV12},
        {"NV21ToI420",           "([BIIZIIIII)[B",        (void *) libenc_NV21ToI420},
        {"openSoftEncoder",      "()Z",                   (void *) libenc_openSoftEncoder},
        {"closeSoftEncoder",     "()V",                   (void *) libenc_closeSoftEncoder},
        {"RGBASoftEncode",       "([BIIZIJ)I",            (void *) libenc_RGBASoftEncode},
};

jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    jvm = vm;

    if (jvm->GetEnv((void **) &jenv, JNI_VERSION_1_6) != JNI_OK) {
        LIBENC_LOGE("Env not got");
        return JNI_ERR;
    }

    jclass clz = jenv->FindClass("net/ossrs/yasea/SrsAvcEncoder");
    if (clz == NULL) {
        LIBENC_LOGE("Class \"net/ossrs/yasea/SrsAvcEncoder\" not found");
        return JNI_ERR;
    }

    if (jenv->RegisterNatives(clz, libenc_methods, LIBENC_ARRAY_ELEMS(libenc_methods))) {
        LIBENC_LOGE("methods not registered");
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
