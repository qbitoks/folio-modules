// Folio's glass surface without the blur: pages here are a View (PDFView) that Compose can't blur.
package app.folio.documents.ui.folio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Glass background clipped to [shape]; [tint] overrides the theme glass colour. */
@Composable
fun Modifier.glass(shape: Shape, tint: Color? = null, elevation: Dp = 0.dp): Modifier {
    val c = Folio.colors
    val hairline = if (c.isDark) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.06f)
    val shadowed = if (elevation > 0.dp) {
        shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
    } else {
        this
    }
    return shadowed.clip(shape).background(tint?.copy(alpha = 0.95f) ?: c.glassFallback).border(0.5.dp, hairline, shape)
}

@Composable
fun GlassSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    elevation: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, tint, elevation), content = content)
}
