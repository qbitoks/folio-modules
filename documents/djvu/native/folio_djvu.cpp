/*
 * Folio Документы: JNI bridge to DjVuLibre's ddjvuapi. GPL-2.0-or-later (as DjVuLibre).
 * One handle = one ddjvu context and document; the Kotlin side serializes calls on a handle.
 */
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <string>
#include <vector>
#include "libdjvu/ddjvuapi.h"
#include "libdjvu/miniexp.h"

#define TAG "FolioDjvu"

struct Doc {
    ddjvu_context_t *ctx;
    ddjvu_document_t *doc;
};

/* Waits for the next message (when [wait]) and drops all queued ones, logging errors. */
static void pump(ddjvu_context_t *ctx, bool wait) {
    if (wait) ddjvu_message_wait(ctx);
    const ddjvu_message_t *msg;
    while ((msg = ddjvu_message_peek(ctx))) {
        if (msg->m_any.tag == DDJVU_ERROR) {
            __android_log_print(ANDROID_LOG_WARN, TAG, "%s", msg->m_error.message ? msg->m_error.message : "error");
        }
        ddjvu_message_pop(ctx);
    }
}

static ddjvu_page_t *openPage(Doc *d, int page) {
    ddjvu_page_t *p = ddjvu_page_create_by_pageno(d->doc, page);
    if (!p) return nullptr;
    while (!ddjvu_page_decoding_done(p)) pump(d->ctx, true);
    if (ddjvu_page_decoding_error(p)) {
        ddjvu_page_release(p);
        return nullptr;
    }
    return p;
}

/* Renders the [x, y, w, h] tile of [page] scaled to [fullW] × [fullH] into [buf]; white where nothing renders. */
static bool render(Doc *d, int page, ddjvu_format_t *fmt, int fullW, int fullH, int x, int y, int w, int h,
        char *buf, size_t stride, int bpp) {
    ddjvu_page_t *p = openPage(d, page);
    if (!p) return false;
    ddjvu_rect_t prect = {0, 0, (unsigned) fullW, (unsigned) fullH};
    ddjvu_rect_t rrect = {x, y, (unsigned) w, (unsigned) h};
    if (!ddjvu_page_render(p, DDJVU_RENDER_COLOR, &prect, &rrect, fmt, (unsigned long) stride, buf)) {
        for (int row = 0; row < h; row++) memset(buf + row * stride, 0xFF, (size_t) w * bpp);
    }
    ddjvu_page_release(p);
    return true;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_app_folio_documents_djvu_DjvuDocument_nativeOpen(JNIEnv *env, jclass, jstring path) {
    ddjvu_context_t *ctx = ddjvu_context_create("folio");
    if (!ctx) return 0;
    const char *p = env->GetStringUTFChars(path, nullptr);
    ddjvu_document_t *doc = ddjvu_document_create_by_filename_utf8(ctx, p, TRUE);
    env->ReleaseStringUTFChars(path, p);
    if (!doc) {
        ddjvu_context_release(ctx);
        return 0;
    }
    while (!ddjvu_document_decoding_done(doc)) pump(ctx, true);
    if (ddjvu_document_decoding_error(doc) || ddjvu_document_get_pagenum(doc) <= 0) {
        ddjvu_document_release(doc);
        ddjvu_context_release(ctx);
        return 0;
    }
    Doc *d = new Doc{ctx, doc};
    return (jlong) (intptr_t) d;
}

JNIEXPORT void JNICALL
Java_app_folio_documents_djvu_DjvuDocument_nativeClose(JNIEnv *, jclass, jlong handle) {
    Doc *d = (Doc *) (intptr_t) handle;
    if (!d) return;
    ddjvu_document_release(d->doc);
    ddjvu_context_release(d->ctx);
    delete d;
}

JNIEXPORT jint JNICALL
Java_app_folio_documents_djvu_DjvuDocument_nativePageCount(JNIEnv *, jclass, jlong handle) {
    return ddjvu_document_get_pagenum(((Doc *) (intptr_t) handle)->doc);
}

/* out = {width, height, dpi}; false when the page info can't be read. */
JNIEXPORT jboolean JNICALL
Java_app_folio_documents_djvu_DjvuDocument_nativePageInfo(JNIEnv *env, jclass, jlong handle, jint page, jintArray out) {
    Doc *d = (Doc *) (intptr_t) handle;
    ddjvu_pageinfo_t info;
    ddjvu_status_t s;
    while ((s = ddjvu_document_get_pageinfo(d->doc, page, &info)) < DDJVU_JOB_OK) pump(d->ctx, true);
    if (s != DDJVU_JOB_OK || info.width <= 0 || info.height <= 0) return JNI_FALSE;
    int w = info.width, h = info.height;
    if (info.rotation % 2) std::swap(w, h);
    jint v[3] = {w, h, info.dpi > 0 ? info.dpi : 300};
    env->SetIntArrayRegion(out, 0, 3, v);
    return JNI_TRUE;
}

/* Colour tile into an ARGB_8888 [bitmap] (its size is the tile's). */
JNIEXPORT jboolean JNICALL
Java_app_folio_documents_djvu_DjvuDocument_nativeRender(JNIEnv *env, jclass, jlong handle, jint page, jobject bitmap,
        jint fullW, jint fullH, jint x, jint y) {
    Doc *d = (Doc *) (intptr_t) handle;
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    void *pixels;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    // Android's RGBA_8888 is R, G, B, A in memory: little-endian words 0xAABBGGRR.
    unsigned int masks[4] = {0x000000FF, 0x0000FF00, 0x00FF0000, 0xFF000000};
    ddjvu_format_t *fmt = ddjvu_format_create(DDJVU_FORMAT_RGBMASK32, 4, masks);
    ddjvu_format_set_row_order(fmt, 1);
    ddjvu_format_set_y_direction(fmt, 1);
    bool ok = render(d, page, fmt, fullW, fullH, x, y, (int) info.width, (int) info.height, (char *) pixels, info.stride, 4);
    ddjvu_format_release(fmt);
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/* The whole page as 8-bit grey (255 = paper), [w] × [h]; null when it fails. */
JNIEXPORT jbyteArray JNICALL
Java_app_folio_documents_djvu_DjvuDocument_nativeRenderGrey(JNIEnv *env, jclass, jlong handle, jint page, jint w, jint h) {
    Doc *d = (Doc *) (intptr_t) handle;
    if (w <= 0 || h <= 0) return nullptr;
    std::vector<char> buf((size_t) w * h);
    ddjvu_format_t *fmt = ddjvu_format_create(DDJVU_FORMAT_GREY8, 0, nullptr);
    ddjvu_format_set_row_order(fmt, 1);
    ddjvu_format_set_y_direction(fmt, 1);
    bool ok = render(d, page, fmt, w, h, 0, 0, w, h, buf.data(), (size_t) w, 1);
    ddjvu_format_release(fmt);
    if (!ok) return nullptr;
    jbyteArray out = env->NewByteArray(w * h);
    if (out) env->SetByteArrayRegion(out, 0, w * h, (const jbyte *) buf.data());
    return out;
}

static void walk(miniexp_t list, int level, std::vector<std::string> &out) {
    for (; miniexp_consp(list); list = miniexp_cdr(list)) {
        miniexp_t item = miniexp_car(list);
        if (!miniexp_consp(item)) continue;
        miniexp_t title = miniexp_car(item);
        miniexp_t url = miniexp_cadr(item);
        if (miniexp_stringp(title) && miniexp_stringp(url)) {
            out.emplace_back(std::to_string(level));
            out.emplace_back(miniexp_to_str(title));
            out.emplace_back(miniexp_to_str(url));
        }
        if (level < 8) walk(miniexp_cddr(item), level + 1, out);
    }
}

/* The outline flattened as {level, title, url} triples; empty without one. */
JNIEXPORT jobjectArray JNICALL
Java_app_folio_documents_djvu_DjvuDocument_nativeOutline(JNIEnv *env, jclass, jlong handle) {
    Doc *d = (Doc *) (intptr_t) handle;
    miniexp_t outline;
    while ((outline = ddjvu_document_get_outline(d->doc)) == miniexp_dummy) pump(d->ctx, true);
    std::vector<std::string> items;
    if (miniexp_consp(outline) && miniexp_car(outline) == miniexp_symbol("bookmarks")) walk(miniexp_cdr(outline), 0, items);
    ddjvu_miniexp_release(d->doc, outline);
    jobjectArray out = env->NewObjectArray((jsize) items.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; out && i < items.size(); i++) {
        jstring s = env->NewStringUTF(items[i].c_str());
        env->SetObjectArrayElement(out, (jsize) i, s);
        env->DeleteLocalRef(s);
    }
    return out;
}

/* Zero-based page of a page name or id from an outline link; −1 when unknown. */
JNIEXPORT jint JNICALL
Java_app_folio_documents_djvu_DjvuDocument_nativeSearchPage(JNIEnv *env, jclass, jlong handle, jstring name) {
    Doc *d = (Doc *) (intptr_t) handle;
    const char *n = env->GetStringUTFChars(name, nullptr);
    int page = ddjvu_document_search_pageno(d->doc, n);
    env->ReleaseStringUTFChars(name, n);
    return page;
}

}
