package io.github.randomf.safedriveway.ui.driving

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.randomf.safedriveway.passage.CarOnScreen
import io.github.randomf.safedriveway.passage.DrivingView
import io.github.randomf.safedriveway.passage.GateOnScreen
import io.github.randomf.safedriveway.passage.UnitOnScreen
import io.github.randomf.safedriveway.ui.AppColors
import io.github.randomf.safedriveway.ui.Digits
import io.github.randomf.safedriveway.ui.color
import io.github.randomf.safedriveway.ui.gapNumber

/** The walls take this share of the view's width from each side; the passage is between them. */
private const val WALL_INSET = 0.3f

/** The car's width as a share of the passage's: what's left over is the gaps. */
private const val CAR_WIDTH = 0.6f

/**
 * The passage seen from above: both walls, each gate as a line across at its real spacing, each
 * unit's gap outside its wall, and the car between them.
 */
@Composable
fun PassageView(view: DrivingView, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val labelWidth = maxWidth * WALL_INSET - 12.dp
        val digits = min(36.dp, labelWidth / 3)
        Canvas(Modifier.fillMaxSize()) {
            val left = size.width * WALL_INSET
            val right = size.width * (1 - WALL_INSET)
            view.gates.forEach { gate(it, left, right) }
            view.car?.let { car(it, left, right) }
            walls(left, right)
        }
        view.gates.forEach { gate ->
            val y = maxHeight * (1 - gate.y)
            gate.left?.let {
                GapLabel(
                    it,
                    Modifier.offset(x = 0.dp, y = y - LABEL_HEIGHT / 2).width(labelWidth),
                    digits
                )
            }
            gate.right?.let {
                GapLabel(
                    it,
                    Modifier.offset(
                        x = maxWidth - labelWidth,
                        y = y - LABEL_HEIGHT / 2
                    ).width(labelWidth),
                    digits,
                    TextAlign.Start
                )
            }
        }
    }
}

private val LABEL_HEIGHT = 48.dp

@Composable
private fun GapLabel(
    unit: UnitOnScreen,
    modifier: Modifier,
    size: Dp,
    align: TextAlign = TextAlign.End
) {
    // In dp, not sp: the label's width is fixed, so a larger system font would cut the digits off.
    val fontSize = with(LocalDensity.current) { size.toSp() }
    Box(modifier.height(LABEL_HEIGHT), contentAlignment = Alignment.Center) {
        Text(
            gapNumber(unit.gap),
            Modifier.fillMaxWidth(),
            color = unit.gap.level.color(),
            style = Digits,
            fontSize = fontSize,
            textAlign = align,
            maxLines = 1
        )
    }
}

private fun DrawScope.walls(left: Float, right: Float) {
    val stroke = 4.dp.toPx()
    drawLine(AppColors.Wall, Offset(left, 0f), Offset(left, size.height), stroke)
    drawLine(AppColors.Wall, Offset(right, 0f), Offset(right, size.height), stroke)
}

private fun DrawScope.gate(gate: GateOnScreen, left: Float, right: Float) {
    val y = size.height * (1 - gate.y)
    drawLine(
        AppColors.NoData,
        Offset(left, y),
        Offset(right, y),
        strokeWidth = 2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
    )
    // Each unit is a block on its wall, in its gap's colour.
    val block = Size(10.dp.toPx(), 28.dp.toPx())
    gate.left?.let {
        drawRoundRect(
            it.gap.level.color(),
            Offset(left - block.width, y - block.height / 2),
            block,
            CornerRadius(3.dp.toPx())
        )
    }
    gate.right?.let {
        drawRoundRect(
            it.gap.level.color(),
            Offset(right, y - block.height / 2),
            block,
            CornerRadius(3.dp.toPx())
        )
    }
}

private fun DrawScope.car(car: CarOnScreen, left: Float, right: Float) {
    val passage = right - left
    val width = passage * CAR_WIDTH
    val room = passage - width
    val rearY = size.height * (1 - car.rearY)
    val frontY = size.height * (1 - car.frontY)
    val rearX = left + car.xAtRear * room
    val frontX = left + car.xAtFront * room
    drawPath(
        Path().apply {
            moveTo(rearX, rearY)
            lineTo(rearX + width, rearY)
            lineTo(frontX + width, frontY)
            lineTo(frontX, frontY)
            close()
        },
        AppColors.Car
    )
    // The windscreen, a quarter of the way back from the front.
    fun at(t: Float) = Offset(frontX + (rearX - frontX) * t, frontY + (rearY - frontY) * t)
    val screen = Path().apply {
        val a = at(0.18f)
        val b = at(0.3f)
        moveTo(a.x + width * 0.12f, a.y)
        lineTo(a.x + width * 0.88f, a.y)
        lineTo(b.x + width * 0.92f, b.y)
        lineTo(b.x + width * 0.08f, b.y)
        close()
    }
    drawPath(screen, AppColors.Background.copy(alpha = 0.7f))
}
