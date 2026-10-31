package com.offlinemaps.core.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The "liquid glass" surface used by every floating control, the search bar, the bottom bar and the
 * sheets.
 *
 * Deliberately not a real backdrop blur. Blurring what is *behind* a view is not something Compose
 * exposes cheaply, and a live blur over a map that repaints every frame is exactly the kind of effect
 * that drains a phone's battery - which this app is meant to avoid. What sells the glass instead is
 * the layering: a translucent tint, a diagonal sheen, a specular highlight near the top edge, a
 * refractive rim drawn as a gradient border, and a soft multi-layer shadow for lift.
 *
 * Colour comes from the active [MaterialTheme.colorScheme], so light and dark both work without a
 * second set of tokens.
 */
@Composable
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 12.dp,
    tint: Color? = null,
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.35f

    val base = (tint ?: scheme.surface).copy(alpha = if (dark) 0.64f else 0.76f)
    val sheenTop = Color.White.copy(alpha = if (dark) 0.10f else 0.72f)
    val sheenBottom = Color.White.copy(alpha = if (dark) 0.02f else 0.30f)
    val rim = Color.White.copy(alpha = if (dark) 0.32f else 0.92f)
    val specular = Color.White.copy(alpha = if (dark) 0.13f else 0.50f)

    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            ambientColor = Color.Black.copy(alpha = if (dark) 0.50f else 0.10f),
            spotColor = Color.Black.copy(alpha = if (dark) 0.62f else 0.18f),
        )
        .clip(shape)
        .drawBehind {
            drawRect(base)
            drawRect(
                Brush.linearGradient(
                    colors = listOf(sheenTop, sheenBottom),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
            // Specular highlight, offset toward the top-left like a light source above the screen.
            drawRect(
                Brush.radialGradient(
                    colors = listOf(specular, Color.Transparent),
                    center = Offset(size.width * 0.30f, -size.height * 0.22f),
                    radius = size.maxDimension * 1.05f,
                ),
            )
        }
        .border(
            BorderStroke(
                1.dp,
                Brush.linearGradient(
                    colors = listOf(rim, rim.copy(alpha = 0.10f), rim.copy(alpha = 0.55f)),
                    start = Offset.Zero,
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                ),
            ),
            shape,
        )
}

/** A glass panel. Keeps Surface's content-colour handling while the glass does the drawing. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    elevation: Dp = 12.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.glassSurface(shape, elevation),
        shape = shape,
        color = Color.Transparent,
    ) {
        Box(Modifier.padding(contentPadding)) { content() }
    }
}
