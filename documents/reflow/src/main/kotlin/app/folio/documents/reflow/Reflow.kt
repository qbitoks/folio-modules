package app.folio.documents.reflow

/** A reflowed page: [width] × [height] 8-bit grey pixels, row by row, 255 = paper. */
class ReflowedPage(val width: Int, val height: Int, val pixels: ByteArray)

/**
 * k2pdfopt's reflow of one page image (KOReader's «Перекомпоновка»): finds columns, text rows and words and wraps
 * them into a single column [devWidth] pixels wide. Figures and formulas stay pictures inside the flow.
 * CPU-heavy (up to seconds per page) and not thread-safe: k2pdfopt keeps global state, so callers serialize.
 */
object Reflow {
    init {
        System.loadLibrary("folioreflow")
    }

    /**
     * [grey] is the page, [width] × [height], rendered at [dpi] × zoom; [dpi] is also the output's dots per inch,
     * so text comes out zoom times its printed size. [margin] in inches, [columns] the most columns to look for.
     */
    fun reflow(
        grey: ByteArray, width: Int, height: Int, devWidth: Int, devHeight: Int, dpi: Int,
        margin: Float = 0.12f, lineSpacing: Float = 1.2f, wordSpacing: Float = 0.375f, columns: Int = 2,
        wrap: Boolean = true, straighten: Boolean = false,
    ): ReflowedPage? {
        val size = IntArray(2)
        val pixels = nativeReflow(grey, width, height, devWidth, devHeight, dpi, margin, lineSpacing, wordSpacing, columns, wrap, straighten, size)
            ?: return null
        return ReflowedPage(size[0], size[1], pixels)
    }

    @JvmStatic
    private external fun nativeReflow(
        grey: ByteArray, width: Int, height: Int, devWidth: Int, devHeight: Int, dpi: Int,
        margin: Float, lineSpacing: Float, wordSpacing: Float, columns: Int, wrap: Boolean, straighten: Boolean,
        outSize: IntArray,
    ): ByteArray?
}
