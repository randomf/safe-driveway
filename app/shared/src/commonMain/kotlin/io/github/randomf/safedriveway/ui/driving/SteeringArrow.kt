package io.github.randomf.safedriveway.ui.driving

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import io.github.randomf.safedriveway.passage.Steer

/**
 * The steering suggestion: a straight arrow up, or one that bends left or right. It says which way
 * to turn the steering wheel, forwards and reversing alike.
 */
@Composable
fun SteeringArrow(steer: Steer, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        when (steer) {
            Steer.Straight -> straightArrow(color)

            Steer.Left -> bentArrow(color)

            // The right arrow is the left one in a mirror.
            Steer.Right -> scale(scaleX = -1f, scaleY = 1f) { bentArrow(color) }
        }
    }
}

private fun DrawScope.straightArrow(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = w * 0.13f
    drawLine(
        color,
        Offset(w / 2, h * 0.95f),
        Offset(w / 2, h * 0.3f),
        strokeWidth = stroke,
        cap = StrokeCap.Round
    )
    drawPath(
        Path().apply {
            moveTo(w / 2, h * 0.04f)
            lineTo(w * 0.24f, h * 0.36f)
            lineTo(w * 0.76f, h * 0.36f)
            close()
        },
        color
    )
}

private fun DrawScope.bentArrow(color: Color) {
    val w = size.width
    val h = size.height
    val stroke = w * 0.13f
    drawPath(
        Path().apply {
            moveTo(w * 0.62f, h * 0.95f)
            lineTo(w * 0.62f, h * 0.6f)
            cubicTo(w * 0.62f, h * 0.38f, w * 0.52f, h * 0.3f, w * 0.34f, h * 0.3f)
        },
        color,
        style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(
        Path().apply {
            moveTo(w * 0.04f, h * 0.3f)
            lineTo(w * 0.38f, h * 0.04f)
            lineTo(w * 0.38f, h * 0.56f)
            close()
        },
        color
    )
}
