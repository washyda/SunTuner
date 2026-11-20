#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <jni.h>

static PFNGLQUERYCOUNTEREXTPROC query_counter;
static PFNGLGETQUERYOBJECTUI64VEXTPROC query_result;
#define JNI_METHOD(name) Java_io_washy_sm8750_tuner_GameGpuClock_##name

JNIEXPORT jboolean JNICALL JNI_METHOD(init)(JNIEnv *env, jobject self) {
    (void) env;
    (void) self;
    query_counter = (PFNGLQUERYCOUNTEREXTPROC) eglGetProcAddress("glQueryCounterEXT");
    query_result = (PFNGLGETQUERYOBJECTUI64VEXTPROC) eglGetProcAddress("glGetQueryObjectui64vEXT");
    if (!query_counter || !query_result) return JNI_FALSE;
    GLint bits = 0;
    glGetQueryiv(GL_TIMESTAMP_EXT, GL_QUERY_COUNTER_BITS_EXT, &bits);
    return glGetError() == GL_NO_ERROR && bits >= 48;
}

JNIEXPORT void JNICALL JNI_METHOD(stamp)(JNIEnv *env, jobject self, jint id) {
    (void) env;
    (void) self;
    query_counter((GLuint) id, GL_TIMESTAMP_EXT);
}

JNIEXPORT jlong JNICALL JNI_METHOD(result)(JNIEnv *env, jobject self, jint id) {
    (void) env;
    (void) self;
    GLuint64 value = 0;
    query_result((GLuint) id, GL_QUERY_RESULT_EXT, &value);
    return (jlong) value;
}
