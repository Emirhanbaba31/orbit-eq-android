package com.orbiteq.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object OrbitIcons {

    private const val STROKE_WIDTH = 1.8f
    private val DefaultBrush = SolidColor(Color.White)

    val OrbitalLogo: ImageVector by lazy {
        hudIcon("OrbitalLogo") {
            strokePath(strokeAlpha = 0.45f) {
                moveTo(12f, 4f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 11.99f, 4f)
                close()
            }
            group(rotate = -24f, pivotX = 12f, pivotY = 12f) {
                strokePath {
                    moveTo(2.5f, 12f)
                    arcTo(9.5f, 3.8f, 0f, isMoreThanHalf = true, isPositiveArc = false, 21.5f, 12f)
                    arcTo(9.5f, 3.8f, 0f, isMoreThanHalf = true, isPositiveArc = false, 2.5f, 12f)
                    close()
                }
                path(fill = DefaultBrush) {
                    moveTo(20.3f, 12f)
                    arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 22.7f, 12f)
                    arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20.3f, 12f)
                    close()
                }
            }
            strokePath {
                moveTo(6.5f, 12f)
                lineTo(9f, 12f)
                lineTo(10.7f, 8.5f)
                lineTo(12.8f, 15.5f)
                lineTo(14.6f, 10f)
                lineTo(16f, 12f)
                lineTo(17.5f, 12f)
            }
        }
    }

    val Power: ImageVector by lazy {
        hudIcon("Power") {
            strokePath {
                moveTo(12f, 3.2f)
                lineTo(12f, 11.2f)
                moveTo(6.98f, 6.93f)
                arcTo(7.5f, 7.5f, 0f, isMoreThanHalf = true, isPositiveArc = false, 17.02f, 6.93f)
            }
        }
    }

    val Headphones: ImageVector by lazy {
        hudIcon("Headphones") {
            strokePath {
                moveTo(4f, 13f)
                arcTo(8f, 8f, 0f, isMoreThanHalf = false, isPositiveArc = true, 20f, 13f)
                moveTo(4f, 13f)
                verticalLineTo(17.5f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 6f, 19.5f)
                horizontalLineTo(7f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 8.5f, 18f)
                verticalLineTo(14.5f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 7f, 13f)
                horizontalLineTo(4f)
                close()
                moveTo(20f, 13f)
                verticalLineTo(17.5f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 18f, 19.5f)
                horizontalLineTo(17f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15.5f, 18f)
                verticalLineTo(14.5f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17f, 13f)
                horizontalLineTo(20f)
                close()
            }
        }
    }

    val Bluetooth: ImageVector by lazy {
        hudIcon("Bluetooth") {
            strokePath {
                moveTo(7f, 7.5f)
                lineTo(16.5f, 16.5f)
                lineTo(12f, 20.5f)
                lineTo(12f, 3.5f)
                lineTo(16.5f, 7.5f)
                lineTo(7f, 16.5f)
            }
        }
    }

    val Speaker: ImageVector by lazy {
        hudIcon("Speaker") {
            strokePath {
                moveTo(4f, 9.5f)
                horizontalLineTo(8f)
                lineTo(13f, 5.5f)
                verticalLineTo(18.5f)
                lineTo(8f, 14.5f)
                horizontalLineTo(4f)
                close()
                moveTo(15.68f, 9.75f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 15.68f, 14.25f)
                moveTo(17.6f, 7.4f)
                arcTo(6.5f, 6.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17.6f, 16.6f)
            }
        }
    }

    val WaveStudio: ImageVector by lazy {
        hudIcon("WaveStudio") {
            strokePath {
                moveTo(3f, 12f)
                lineTo(5.8f, 12f)
                lineTo(8.3f, 6.5f)
                lineTo(11.3f, 17.5f)
                lineTo(14.3f, 5f)
                lineTo(17.2f, 14.8f)
                lineTo(19f, 12f)
                lineTo(21f, 12f)
            }
        }
    }

    val MixerSliders: ImageVector by lazy {
        hudIcon("MixerSliders") {
            strokePath {
                moveTo(5.5f, 4f)
                verticalLineTo(8.2f)
                moveTo(5.5f, 11.8f)
                verticalLineTo(20f)
                moveTo(3.7f, 10f)
                arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 7.3f, 10f)
                arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 3.7f, 10f)
                close()

                moveTo(12f, 4f)
                verticalLineTo(13.2f)
                moveTo(12f, 16.8f)
                verticalLineTo(20f)
                moveTo(10.2f, 15f)
                arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 13.8f, 15f)
                arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10.2f, 15f)
                close()

                moveTo(18.5f, 4f)
                verticalLineTo(6.2f)
                moveTo(18.5f, 9.8f)
                verticalLineTo(20f)
                moveTo(16.7f, 8f)
                arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20.3f, 8f)
                arcTo(1.8f, 1.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16.7f, 8f)
                close()
            }
        }
    }

    val ActivityOrbit: ImageVector by lazy {
        hudIcon("ActivityOrbit") {
            group(rotate = -32f, pivotX = 12f, pivotY = 12f) {
                strokePath {
                    moveTo(2.5f, 12f)
                    arcTo(9.5f, 4.2f, 0f, isMoreThanHalf = true, isPositiveArc = false, 21.5f, 12f)
                    arcTo(9.5f, 4.2f, 0f, isMoreThanHalf = true, isPositiveArc = false, 2.5f, 12f)
                    close()
                }
            }
            strokePath {
                moveTo(10f, 12f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14f, 12f)
                arcTo(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10f, 12f)
                close()
            }
            path(fill = DefaultBrush) {
                moveTo(17.5f, 7.2f)
                arcTo(1.3f, 1.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20.1f, 7.2f)
                arcTo(1.3f, 1.3f, 0f, isMoreThanHalf = true, isPositiveArc = true, 17.5f, 7.2f)
                close()
            }
        }
    }

    val PinFilled: ImageVector by lazy {
        hudIcon("PinFilled") {
            strokePath(fill = DefaultBrush) {
                moveTo(14.5f, 4f)
                lineTo(20f, 9.5f)
                lineTo(18.5f, 11f)
                lineTo(17.2f, 10.4f)
                lineTo(13.6f, 14f)
                lineTo(14.5f, 16.2f)
                lineTo(13.3f, 17.4f)
                lineTo(6.6f, 10.7f)
                lineTo(7.8f, 9.5f)
                lineTo(10f, 10.4f)
                lineTo(13.6f, 6.8f)
                lineTo(13f, 5.5f)
                close()
            }
            strokePath {
                moveTo(9.9f, 14.1f)
                lineTo(4.5f, 19.5f)
            }
        }
    }

    val PinOutline: ImageVector by lazy {
        hudIcon("PinOutline") {
            strokePath {
                moveTo(14.5f, 4f)
                lineTo(20f, 9.5f)
                lineTo(18.5f, 11f)
                lineTo(17.2f, 10.4f)
                lineTo(13.6f, 14f)
                lineTo(14.5f, 16.2f)
                lineTo(13.3f, 17.4f)
                lineTo(6.6f, 10.7f)
                lineTo(7.8f, 9.5f)
                lineTo(10f, 10.4f)
                lineTo(13.6f, 6.8f)
                lineTo(13f, 5.5f)
                close()
                moveTo(9.9f, 14.1f)
                lineTo(4.5f, 19.5f)
            }
        }
    }

    val ShieldLimiter: ImageVector by lazy {
        hudIcon("ShieldLimiter") {
            strokePath {
                moveTo(12f, 3f)
                lineTo(19f, 6f)
                verticalLineTo(11.5f)
                curveTo(19f, 16.2f, 16f, 19.6f, 12f, 21f)
                curveTo(8f, 19.6f, 5f, 16.2f, 5f, 11.5f)
                verticalLineTo(6f)
                close()
                moveTo(8.5f, 10f)
                horizontalLineTo(15.5f)
                moveTo(9.2f, 15.2f)
                lineTo(12f, 12.2f)
                lineTo(14.8f, 15.2f)
            }
        }
    }

    val SparkBolt: ImageVector by lazy {
        hudIcon("SparkBolt") {
            strokePath {
                moveTo(13f, 2.8f)
                lineTo(6f, 13f)
                horizontalLineTo(11.5f)
                lineTo(10.5f, 21.2f)
                lineTo(18f, 11f)
                horizontalLineTo(12.5f)
                close()
            }
        }
    }

    val CheckCircle: ImageVector by lazy {
        hudIcon("CheckCircle") {
            strokePath {
                moveTo(3.5f, 12f)
                arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20.5f, 12f)
                arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 3.5f, 12f)
                close()
                moveTo(8.6f, 12.2f)
                lineTo(10.9f, 14.5f)
                lineTo(15.6f, 9.6f)
            }
        }
    }

    val WarningInfo: ImageVector by lazy {
        hudIcon("WarningInfo") {
            strokePath {
                moveTo(12f, 3.8f)
                lineTo(20.4f, 18.6f)
                curveTo(20.8f, 19.3f, 20.3f, 20f, 19.5f, 20f)
                horizontalLineTo(4.5f)
                curveTo(3.7f, 20f, 3.2f, 19.3f, 3.6f, 18.6f)
                close()
                moveTo(12f, 9.2f)
                verticalLineTo(13.6f)
                moveTo(12f, 16.6f)
                verticalLineTo(16.8f)
            }
        }
    }

    val Reset: ImageVector by lazy {
        hudIcon("Reset") {
            strokePath {
                moveTo(4.5f, 5.5f)
                verticalLineTo(10f)
                horizontalLineTo(9f)
                moveTo(5.2f, 9.6f)
                arcTo(7.8f, 7.8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5.4f, 15.8f)
            }
        }
    }

    val Save: ImageVector by lazy {
        hudIcon("Save") {
            strokePath {
                moveTo(5f, 4f)
                horizontalLineTo(16f)
                lineTo(19f, 7f)
                verticalLineTo(19f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 18f, 20f)
                horizontalLineTo(6f)
                arcTo(1f, 1f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5f, 19f)
                close()
                moveTo(8.5f, 4f)
                verticalLineTo(8.5f)
                horizontalLineTo(14.5f)
                verticalLineTo(4f)
                moveTo(8f, 20f)
                verticalLineTo(14f)
                horizontalLineTo(16f)
                verticalLineTo(20f)
            }
        }
    }

    val Delete: ImageVector by lazy {
        hudIcon("Delete") {
            strokePath {
                moveTo(4.5f, 6.5f)
                horizontalLineTo(19.5f)
                moveTo(9.5f, 6.5f)
                verticalLineTo(4.5f)
                horizontalLineTo(14.5f)
                verticalLineTo(6.5f)
                moveTo(6.5f, 6.5f)
                lineTo(7.4f, 19f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 8.6f, 20f)
                horizontalLineTo(15.4f)
                arcTo(1.2f, 1.2f, 0f, isMoreThanHalf = false, isPositiveArc = false, 16.6f, 19f)
                lineTo(17.5f, 6.5f)
                moveTo(10.2f, 10.5f)
                verticalLineTo(16.2f)
                moveTo(13.8f, 10.5f)
                verticalLineTo(16.2f)
            }
        }
    }

    val Palette: ImageVector by lazy {
        hudIcon("Palette") {
            strokePath {
                moveTo(12f, 20.5f)
                arcTo(8.5f, 8.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 20.5f, 12f)
                curveTo(20.5f, 13.7f, 19.3f, 14.8f, 17.6f, 14.8f)
                horizontalLineTo(15.6f)
                arcTo(1.9f, 1.9f, 0f, isMoreThanHalf = false, isPositiveArc = false, 13.7f, 16.7f)
                curveTo(13.7f, 17.5f, 14.2f, 18f, 14.2f, 18.9f)
                arcTo(1.5f, 1.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 12.7f, 20.5f)
                horizontalLineTo(12f)
                close()
            }
            path(fill = DefaultBrush) {
                moveTo(7.5f, 10.2f)
                arcTo(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.7f, 10.2f)
                arcTo(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 7.5f, 10.2f)
                close()
                moveTo(10.6f, 7.2f)
                arcTo(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 12.8f, 7.2f)
                arcTo(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 10.6f, 7.2f)
                close()
                moveTo(14.5f, 8.8f)
                arcTo(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16.7f, 8.8f)
                arcTo(1.1f, 1.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14.5f, 8.8f)
                close()
            }
        }
    }

    val MusicNote: ImageVector by lazy {
        hudIcon("MusicNote") {
            strokePath {
                moveTo(9.2f, 17.2f)
                verticalLineTo(6.2f)
                lineTo(19f, 4.2f)
                verticalLineTo(15.2f)
                moveTo(5f, 17.2f)
                arcTo(2.1f, 2.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 9.2f, 17.2f)
                arcTo(2.1f, 2.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 5f, 17.2f)
                close()
                moveTo(14.8f, 15.2f)
                arcTo(2.1f, 2.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 19f, 15.2f)
                arcTo(2.1f, 2.1f, 0f, isMoreThanHalf = true, isPositiveArc = true, 14.8f, 15.2f)
                close()
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        hudIcon("ChevronRight") {
            strokePath {
                moveTo(9.5f, 6.5f)
                lineTo(15f, 12f)
                lineTo(9.5f, 17.5f)
            }
        }
    }

    private fun hudIcon(
        name: String,
        block: ImageVector.Builder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(block).build()

    private fun ImageVector.Builder.strokePath(
        fill: SolidColor? = null,
        strokeAlpha: Float = 1f,
        pathBuilder: PathBuilder.() -> Unit,
    ) {
        path(
            fill = fill,
            stroke = DefaultBrush,
            strokeAlpha = strokeAlpha,
            strokeLineWidth = STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = pathBuilder,
        )
    }
}
