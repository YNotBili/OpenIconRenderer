package com.lingmarket.openiconrenderer.vector

/**
 * TGFX PathRasterizer / path-cache ideas (coverage scratch, deferred path work).
 * Circle mask row-span caching lives in ApkIconExtractor; analytic path raster stays in
 * [VectorRasterizer]. Tile blit for tiny solids was measured as a net loss on Fenix and
 * is not wired into the hot path.
 */
internal object PathCoverageAtlas {
    const val MAX_TILE_PIXELS = 48 * 48

    private var tilePixels: IntArray? = null

    fun obtainTile(width: Int, height: Int): IntArray {
        val n = width * height
        val cur = tilePixels
        if (cur != null && cur.size >= n) {
            cur.fill(0, 0, n)
            return cur
        }
        val next = IntArray(n)
        tilePixels = next
        return next
    }
}
