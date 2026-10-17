/*
 * Folio Документы: JNI bridge to k2pdfopt's page reflow (KOReader's libk2pdfopt, koptreflow.c). AGPL-3.0.
 * Takes one page as 8-bit grey pixels and returns the reflowed column as grey pixels of the device width.
 */
#include <jni.h>
#include <string.h>
#include "setting.h"
#include "koptreflow.h"

JNIEXPORT jbyteArray JNICALL
Java_app_folio_documents_reflow_Reflow_nativeReflow(JNIEnv *env, jclass cls, jbyteArray src, jint width, jint height,
        jint devWidth, jint devHeight, jint dpi, jfloat margin, jfloat lineSpacing, jfloat wordSpacing, jint columns,
        jboolean wrap, jboolean straighten, jintArray outSize) {
    (void) cls;
    if (width <= 0 || height <= 0 || (*env)->GetArrayLength(env, src) < width * height) return NULL;

    KOPTContext kctx;
    memset(&kctx, 0, sizeof(kctx));
    kctx.trim = 1;
    kctx.wrap = wrap ? 1 : 0;
    kctx.indent = 1;
    kctx.columns = columns;
    kctx.straighten = straighten ? 1 : 0;
    kctx.justification = -1;
    kctx.white_threshold = -1;
    kctx.paint_white_threshold = -1;
    kctx.dev_dpi = dpi;
    kctx.dev_width = devWidth;
    kctx.dev_height = devHeight;
    kctx.page_width = devWidth;
    kctx.page_height = devHeight;
    kctx.zoom = 1.0;
    kctx.margin = margin;
    kctx.quality = 1.0;
    kctx.contrast = 1.0;
    kctx.defect_size = 1.0;
    kctx.line_spacing = lineSpacing;
    kctx.word_spacing = wordSpacing;
    bmp_init(&kctx.src);
    bmp_init(&kctx.dst);
    wrectmaps_init(&kctx.rectmaps);

    WILLUSBITMAP *s = &kctx.src;
    s->width = width;
    s->height = height;
    s->bpp = 8;
    bmp_alloc(s);
    for (int i = 0; i < 256; i++) s->red[i] = s->green[i] = s->blue[i] = i;
    jbyte *pixels = (*env)->GetPrimitiveArrayCritical(env, src, NULL);
    if (pixels == NULL) {
        bmp_free(s);
        return NULL;
    }
    for (int y = 0; y < height; y++) memcpy(bmp_rowptr_from_top(s, y), pixels + (size_t) y * width, width);
    (*env)->ReleasePrimitiveArrayCritical(env, src, pixels, JNI_ABORT);

    k2pdfopt_reflow_bmp(&kctx);

    jbyteArray result = NULL;
    WILLUSBITMAP *d = &kctx.dst;
    if (d->bpp == 8 && d->width > 0 && d->height > 0) {
        result = (*env)->NewByteArray(env, d->width * d->height);
        if (result != NULL) {
            jbyte *out = (*env)->GetPrimitiveArrayCritical(env, result, NULL);
            for (int y = 0; y < d->height; y++) memcpy(out + (size_t) y * d->width, bmp_rowptr_from_top(d, y), d->width);
            (*env)->ReleasePrimitiveArrayCritical(env, result, out, 0);
            jint size[2] = { d->width, d->height };
            (*env)->SetIntArrayRegion(env, outSize, 0, 2, size);
        }
    }
    bmp_free(s);
    bmp_free(d);
    if (kctx.rboxa) boxaDestroy(&kctx.rboxa);
    if (kctx.nboxa) boxaDestroy(&kctx.nboxa);
    if (kctx.rnai) numaDestroy(&kctx.rnai);
    if (kctx.nnai) numaDestroy(&kctx.nnai);
    wrectmaps_free(&kctx.rectmaps);
    return result;
}
