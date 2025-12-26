#include <jni.h>
#include <android/log.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include <wasm.h>
#include <wasmtime.h>

#define LOG_TAG "WasmtimeBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static void throw_java(JNIEnv *env, const char *message) {
    jclass runtime_exc = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (runtime_exc != NULL) {
        (*env)->ThrowNew(env, runtime_exc, message);
    }
}

static bool handle_error(JNIEnv *env, wasmtime_error_t *error, wasm_trap_t *trap) {
    if (error != NULL) {
        wasm_message_t message;
        wasmtime_error_message(error, &message);
        LOGE("Wasmtime error: %.*s", (int) message.size, message.data);
        throw_java(env, message.data);
        wasm_byte_vec_delete(&message);
        wasmtime_error_delete(error);
        return true;
    }
    if (trap != NULL) {
        wasm_message_t message;
        wasm_trap_message(trap, &message);
        LOGE("Wasm trap: %.*s", (int) message.size, message.data);
        throw_java(env, message.data);
        wasm_byte_vec_delete(&message);
        wasm_trap_delete(trap);
        return true;
    }
    return false;
}

JNIEXPORT jint JNICALL
Java_ai_edgez_controller_WasmtimeRunner_runAdd(
        JNIEnv *env,
        jobject thiz,
        jbyteArray wasm_bytes,
        jint a,
        jint b) {
    (void) thiz;

    if (wasm_bytes == NULL) {
        throw_java(env, "wasmBytes is null");
        return -1;
    }

    jsize length = (*env)->GetArrayLength(env, wasm_bytes);
    if (length <= 0) {
        throw_java(env, "wasmBytes is empty");
        return -1;
    }

    jboolean is_copy = JNI_FALSE;
    jbyte *bytes = (*env)->GetByteArrayElements(env, wasm_bytes, &is_copy);
    if (bytes == NULL) {
        throw_java(env, "Unable to read wasmBytes");
        return -1;
    }

    jint result = -1;
    wasmtime_error_t *error = NULL;
    wasm_trap_t *trap = NULL;
    wasm_engine_t *engine = wasm_engine_new();
    if (engine == NULL) {
        throw_java(env, "Failed to create Wasmtime engine");
        goto cleanup_bytes;
    }

    wasmtime_store_t *store = wasmtime_store_new(engine, NULL, NULL);
    if (store == NULL) {
        throw_java(env, "Failed to create Wasmtime store");
        goto cleanup_engine;
    }

    wasmtime_context_t *context = wasmtime_store_context(store);

    wasmtime_module_t *module = NULL;
    error = wasmtime_module_new(engine, (const uint8_t *) bytes, (size_t) length, &module);
    if (handle_error(env, error, trap) || module == NULL) {
        goto cleanup_store;
    }

    // Instantiate the module (no imports expected for the demo module).
    wasmtime_instance_t instance;
    error = wasmtime_instance_new(context, module, NULL, 0, &instance, &trap);
    if (handle_error(env, error, trap)) {
        goto cleanup_module;
    }

    wasmtime_extern_t export_func;
    bool ok = wasmtime_instance_export_get(context, &instance, "add", strlen("add"), &export_func);
    if (!ok || export_func.kind != WASMTIME_EXTERN_FUNC) {
        throw_java(env, "Exported function 'add' not found in module");
        goto cleanup_module;
    }

    wasmtime_val_t params[2];
    params[0].kind = WASMTIME_I32;
    params[0].of.i32 = a;
    params[1].kind = WASMTIME_I32;
    params[1].of.i32 = b;

    wasmtime_val_t results[1];
    memset(results, 0, sizeof(results));

    error = wasmtime_func_call(context, &export_func.of.func, params, 2, results, 1, &trap);
    if (handle_error(env, error, trap)) {
        goto cleanup_module;
    }

    if (results[0].kind != WASMTIME_I32) {
        throw_java(env, "Unexpected return type; expected i32");
        goto cleanup_module;
    }

    result = results[0].of.i32;

cleanup_module:
    wasmtime_module_delete(module);

cleanup_store:
    wasmtime_store_delete(store);

cleanup_engine:
    wasm_engine_delete(engine);

cleanup_bytes:
    (*env)->ReleaseByteArrayElements(env, wasm_bytes, bytes, JNI_ABORT);

    return result;
}

JNIEXPORT jstring JNICALL
Java_ai_edgez_controller_WasmtimeRunner_runHello(
        JNIEnv *env,
        jobject thiz,
        jbyteArray wasm_bytes) {
    (void) thiz;

    if (wasm_bytes == NULL) {
        throw_java(env, "hello_wasm.wasm bytes are null");
        return NULL;
    }

    jsize length = (*env)->GetArrayLength(env, wasm_bytes);
    if (length <= 0) {
        throw_java(env, "hello_wasm.wasm is empty");
        return NULL;
    }

    jboolean is_copy = JNI_FALSE;
    jbyte *bytes = (*env)->GetByteArrayElements(env, wasm_bytes, &is_copy);
    if (bytes == NULL) {
        throw_java(env, "Unable to read hello_wasm.wasm");
        return NULL;
    }

    wasmtime_error_t *error = NULL;
    wasm_trap_t *trap = NULL;

    wasm_engine_t *engine = wasm_engine_new();
    if (engine == NULL) {
        throw_java(env, "Failed to create Wasmtime engine");
        (*env)->ReleaseByteArrayElements(env, wasm_bytes, bytes, JNI_ABORT);
        return NULL;
    }

    wasmtime_store_t *store = wasmtime_store_new(engine, NULL, NULL);
    if (store == NULL) {
        throw_java(env, "Failed to create Wasmtime store");
        wasm_engine_delete(engine);
        (*env)->ReleaseByteArrayElements(env, wasm_bytes, bytes, JNI_ABORT);
        return NULL;
    }
    wasmtime_context_t *context = wasmtime_store_context(store);

    wasmtime_module_t *module = NULL;
    error = wasmtime_module_new(engine, (const uint8_t *) bytes, (size_t) length, &module);
    if (handle_error(env, error, trap) || module == NULL) {
        (*env)->ReleaseByteArrayElements(env, wasm_bytes, bytes, JNI_ABORT);
        wasmtime_store_delete(store);
        wasm_engine_delete(engine);
        return NULL;
    }

    wasmtime_instance_t instance;
    error = wasmtime_instance_new(context, module, NULL, 0, &instance, &trap);
    if (handle_error(env, error, trap)) {
        wasmtime_module_delete(module);
        wasmtime_store_delete(store);
        wasm_engine_delete(engine);
        (*env)->ReleaseByteArrayElements(env, wasm_bytes, bytes, JNI_ABORT);
        return NULL;
    }

    wasmtime_extern_t export_mem;
    if (!wasmtime_instance_export_get(context, &instance, "memory", strlen("memory"), &export_mem) ||
        export_mem.kind != WASMTIME_EXTERN_MEMORY) {
        throw_java(env, "Exported memory not found");
        wasmtime_module_delete(module);
        wasmtime_store_delete(store);
        wasm_engine_delete(engine);
        (*env)->ReleaseByteArrayElements(env, wasm_bytes, bytes, JNI_ABORT);
        return NULL;
    }

    wasmtime_extern_t export_ptr;
    if (!wasmtime_instance_export_get(context, &instance, "hello_ptr", strlen("hello_ptr"), &export_ptr) ||
        export_ptr.kind != WASMTIME_EXTERN_FUNC) {
        throw_java(env, "Exported function 'hello_ptr' not found");
        wasmtime_module_delete(module);
        wasmtime_store_delete(store);
        wasm_engine_delete(engine);
        (*env)->ReleaseByteArrayElements(env, wasm_bytes, bytes, JNI_ABORT);
        return NULL;
    }

    wasmtime_extern_t export_len;
    if (!wasmtime_instance_export_get(context, &instance, "hello_len", strlen("hello_len"), &export_len) ||
        export_len.kind != WASMTIME_EXTERN_FUNC) {
        throw_java(env, "Exported function 'hello_len' not found");
        wasmtime_module_delete(module);
        wasmtime_store_delete(store);
        wasm_engine_delete(engine);
        (*env)->ReleaseByteArrayElements(env, wasm_bytes, bytes, JNI_ABORT);
        return NULL;
    }

    wasmtime_val_t results[1];

    memset(results, 0, sizeof(results));
    error = wasmtime_func_call(context, &export_ptr.of.func, NULL, 0, results, 1, &trap);
    if (handle_error(env, error, trap) || results[0].kind != WASMTIME_I32) {
        wasmtime_module_delete(module);
        wasmtime_store_delete(store);
        wasm_engine_delete(engine);
        (*env)->ReleaseByteArrayElements(env, wasm_bytes, bytes, JNI_ABORT);
        return NULL;
    }
    int32_t ptr = results[0].of.i32;

    memset(results, 0, sizeof(results));
    error = wasmtime_func_call(context, &export_len.of.func, NULL, 0, results, 1, &trap);
    if (handle_error(env, error, trap) || results[0].kind != WASMTIME_I32) {
        wasmtime_module_delete(module);
        wasmtime_store_delete(store);
        wasm_engine_delete(engine);
        (*env)->ReleaseByteArrayElements(env, wasm_bytes, bytes, JNI_ABORT);
        return NULL;
    }
    int32_t len = results[0].of.i32;

    uint8_t *data = wasmtime_memory_data(context, &export_mem.of.memory);
    size_t data_size = wasmtime_memory_data_size(context, &export_mem.of.memory);

    if (ptr < 0 || len < 0 || (uint64_t) ptr + (uint64_t) len > data_size) {
        throw_java(env, "Hello string is out of bounds in Wasm memory");
        wasmtime_module_delete(module);
        wasmtime_store_delete(store);
        wasm_engine_delete(engine);
        (*env)->ReleaseByteArrayElements(env, wasm_bytes, bytes, JNI_ABORT);
        return NULL;
    }

    char *buffer = (char *) malloc((size_t) len + 1);
    if (buffer == NULL) {
        throw_java(env, "Failed to allocate buffer for Wasm string");
        wasmtime_module_delete(module);
        wasmtime_store_delete(store);
        wasm_engine_delete(engine);
        (*env)->ReleaseByteArrayElements(env, wasm_bytes, bytes, JNI_ABORT);
        return NULL;
    }
    memcpy(buffer, data + ptr, (size_t) len);
    buffer[len] = '\0';

    jstring result = (*env)->NewStringUTF(env, buffer);
    free(buffer);

    wasmtime_module_delete(module);
    wasmtime_store_delete(store);
    wasm_engine_delete(engine);
    (*env)->ReleaseByteArrayElements(env, wasm_bytes, bytes, JNI_ABORT);

    return result;
}
