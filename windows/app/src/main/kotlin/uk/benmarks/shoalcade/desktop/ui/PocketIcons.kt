package uk.benmarks.shoalcade.desktop.ui

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The nine Material icons the app uses that are not in Compose's small core icon set. They were the only reason for
 * material-icons-extended, a 37.7 MB jar in the installer; these are the same drawings, path for path, as that jar
 * (version 1.7.3, the Material Icons by Google, Apache-2.0), built with the same `materialIcon` / `materialPath`.
 */
object PocketIcons {
    val CloudUpload: ImageVector by lazy {
        materialIcon(name = "Filled.CloudUpload") {
            materialPath {
                moveTo(19.35f, 10.04f)
                curveTo(18.67f, 6.59f, 15.64f, 4f, 12f, 4f)
                curveTo(9.11f, 4f, 6.6f, 5.64f, 5.35f, 8.04f)
                curveTo(2.34f, 8.36f, 0f, 10.91f, 0f, 14f)
                curveToRelative(0f, 3.31f, 2.69f, 6f, 6f, 6f)
                horizontalLineToRelative(13f)
                curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f)
                curveToRelative(0f, -2.64f, -2.05f, -4.78f, -4.65f, -4.96f)
                close()
                moveTo(14f, 13f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(-4f)
                verticalLineToRelative(-4f)
                horizontalLineTo(7f)
                lineToRelative(5f, -5f)
                lineToRelative(5f, 5f)
                horizontalLineToRelative(-3f)
                close()
            }
        }
    }
    val Code: ImageVector by lazy {
        materialIcon(name = "Outlined.Code") {
            materialPath {
                moveTo(9.4f, 16.6f)
                lineTo(4.8f, 12f)
                lineToRelative(4.6f, -4.6f)
                lineTo(8f, 6f)
                lineToRelative(-6f, 6f)
                lineToRelative(6f, 6f)
                lineToRelative(1.4f, -1.4f)
                close()
                moveTo(14.6f, 16.6f)
                lineToRelative(4.6f, -4.6f)
                lineToRelative(-4.6f, -4.6f)
                lineTo(16f, 6f)
                lineToRelative(6f, 6f)
                lineToRelative(-6f, 6f)
                lineToRelative(-1.4f, -1.4f)
                close()
            }
        }
    }
    val Download: ImageVector by lazy {
        materialIcon(name = "Outlined.Download") {
            materialPath {
                moveTo(19f, 9f)
                horizontalLineToRelative(-4f)
                lineTo(15f, 3f)
                lineTo(9f, 3f)
                verticalLineToRelative(6f)
                lineTo(5f, 9f)
                lineToRelative(7f, 7f)
                lineToRelative(7f, -7f)
                close()
                moveTo(11f, 11f)
                lineTo(11f, 5f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(1.17f)
                lineTo(12f, 13.17f)
                lineTo(9.83f, 11f)
                lineTo(11f, 11f)
                close()
                moveTo(5f, 18f)
                horizontalLineToRelative(14f)
                verticalLineToRelative(2f)
                lineTo(5f, 20f)
                close()
            }
        }
    }
    val Link: ImageVector by lazy {
        materialIcon(name = "Outlined.Link") {
            materialPath {
                moveTo(17f, 7f)
                horizontalLineToRelative(-4f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(4f)
                curveToRelative(1.65f, 0f, 3f, 1.35f, 3f, 3f)
                reflectiveCurveToRelative(-1.35f, 3f, -3f, 3f)
                horizontalLineToRelative(-4f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(4f)
                curveToRelative(2.76f, 0f, 5f, -2.24f, 5f, -5f)
                reflectiveCurveToRelative(-2.24f, -5f, -5f, -5f)
                close()
                moveTo(11f, 15f)
                lineTo(7f, 15f)
                curveToRelative(-1.65f, 0f, -3f, -1.35f, -3f, -3f)
                reflectiveCurveToRelative(1.35f, -3f, 3f, -3f)
                horizontalLineToRelative(4f)
                lineTo(11f, 7f)
                lineTo(7f, 7f)
                curveToRelative(-2.76f, 0f, -5f, 2.24f, -5f, 5f)
                reflectiveCurveToRelative(2.24f, 5f, 5f, 5f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(-2f)
                close()
                moveTo(8f, 11f)
                horizontalLineToRelative(8f)
                verticalLineToRelative(2f)
                lineTo(8f, 13f)
                close()
            }
        }
    }
    val Monitor: ImageVector by lazy {
        materialIcon(name = "Outlined.Monitor") {
            materialPath {
                moveTo(20f, 3f)
                horizontalLineTo(4f)
                curveTo(2.9f, 3f, 2f, 3.9f, 2f, 5f)
                verticalLineToRelative(11f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(3f)
                lineToRelative(-1f, 1f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(12f)
                verticalLineToRelative(-2f)
                lineToRelative(-1f, -1f)
                horizontalLineToRelative(3f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(5f)
                curveTo(22f, 3.9f, 21.1f, 3f, 20f, 3f)
                close()
                moveTo(20f, 16f)
                horizontalLineTo(4f)
                verticalLineTo(5f)
                horizontalLineToRelative(16f)
                verticalLineTo(16f)
                close()
            }
        }
    }
    val Save: ImageVector by lazy {
        materialIcon(name = "Outlined.Save") {
            materialPath {
                moveTo(17f, 3f)
                lineTo(5f, 3f)
                curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(14f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(21f, 7f)
                lineToRelative(-4f, -4f)
                close()
                moveTo(19f, 19f)
                lineTo(5f, 19f)
                lineTo(5f, 5f)
                horizontalLineToRelative(11.17f)
                lineTo(19f, 7.83f)
                lineTo(19f, 19f)
                close()
                moveTo(12f, 12f)
                curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
                reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
                reflectiveCurveToRelative(3f, -1.34f, 3f, -3f)
                reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
                close()
                moveTo(6f, 6f)
                horizontalLineToRelative(9f)
                verticalLineToRelative(4f)
                lineTo(6f, 10f)
                close()
            }
        }
    }
    val Speed: ImageVector by lazy {
        materialIcon(name = "Outlined.Speed") {
            materialPath {
                moveTo(20.38f, 8.57f)
                lineToRelative(-1.23f, 1.85f)
                arcToRelative(8f, 8f, 0f, false, true, -0.22f, 7.58f)
                horizontalLineTo(5.07f)
                arcTo(8f, 8f, 0f, false, true, 15.58f, 6.85f)
                lineToRelative(1.85f, -1.23f)
                arcTo(10f, 10f, 0f, false, false, 3.35f, 19f)
                arcToRelative(2f, 2f, 0f, false, false, 1.72f, 1f)
                horizontalLineToRelative(13.85f)
                arcToRelative(2f, 2f, 0f, false, false, 1.74f, -1f)
                arcToRelative(10f, 10f, 0f, false, false, -0.27f, -10.44f)
                close()
            }
            materialPath {
                moveTo(10.59f, 15.41f)
                arcToRelative(2f, 2f, 0f, false, false, 2.83f, 0f)
                lineToRelative(5.66f, -8.49f)
                lineToRelative(-8.49f, 5.66f)
                arcToRelative(2f, 2f, 0f, false, false, 0f, 2.83f)
                close()
            }
        }
    }
    val SportsEsports: ImageVector by lazy {
        materialIcon(name = "Outlined.SportsEsports") {
            materialPath {
                moveTo(21.58f, 16.09f)
                lineToRelative(-1.09f, -7.66f)
                curveTo(20.21f, 6.46f, 18.52f, 5f, 16.53f, 5f)
                horizontalLineTo(7.47f)
                curveTo(5.48f, 5f, 3.79f, 6.46f, 3.51f, 8.43f)
                lineToRelative(-1.09f, 7.66f)
                curveTo(2.2f, 17.63f, 3.39f, 19f, 4.94f, 19f)
                horizontalLineToRelative(0f)
                curveToRelative(0.68f, 0f, 1.32f, -0.27f, 1.8f, -0.75f)
                lineTo(9f, 16f)
                horizontalLineToRelative(6f)
                lineToRelative(2.25f, 2.25f)
                curveToRelative(0.48f, 0.48f, 1.13f, 0.75f, 1.8f, 0.75f)
                horizontalLineToRelative(0f)
                curveTo(20.61f, 19f, 21.8f, 17.63f, 21.58f, 16.09f)
                close()
                moveTo(19.48f, 16.81f)
                curveTo(19.4f, 16.9f, 19.27f, 17f, 19.06f, 17f)
                curveToRelative(-0.15f, 0f, -0.29f, -0.06f, -0.39f, -0.16f)
                lineTo(15.83f, 14f)
                horizontalLineTo(8.17f)
                lineToRelative(-2.84f, 2.84f)
                curveTo(5.23f, 16.94f, 5.09f, 17f, 4.94f, 17f)
                curveToRelative(-0.21f, 0f, -0.34f, -0.1f, -0.42f, -0.19f)
                curveToRelative(-0.08f, -0.09f, -0.16f, -0.23f, -0.13f, -0.44f)
                lineToRelative(1.09f, -7.66f)
                curveTo(5.63f, 7.74f, 6.48f, 7f, 7.47f, 7f)
                horizontalLineToRelative(9.06f)
                curveToRelative(0.99f, 0f, 1.84f, 0.74f, 1.98f, 1.72f)
                lineToRelative(1.09f, 7.66f)
                curveTo(19.63f, 16.58f, 19.55f, 16.72f, 19.48f, 16.81f)
                close()
            }
            materialPath {
                moveTo(9f, 8f)
                lineToRelative(-1f, 0f)
                lineToRelative(0f, 2f)
                lineToRelative(-2f, 0f)
                lineToRelative(0f, 1f)
                lineToRelative(2f, 0f)
                lineToRelative(0f, 2f)
                lineToRelative(1f, 0f)
                lineToRelative(0f, -2f)
                lineToRelative(2f, 0f)
                lineToRelative(0f, -1f)
                lineToRelative(-2f, 0f)
                close()
            }
            materialPath {
                moveTo(17f, 12f)
                moveToRelative(-1f, 0f)
                arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
                arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
            }
            materialPath {
                moveTo(15f, 9f)
                moveToRelative(-1f, 0f)
                arcToRelative(1f, 1f, 0f, true, true, 2f, 0f)
                arcToRelative(1f, 1f, 0f, true, true, -2f, 0f)
            }
        }
    }
    val StarOutline: ImageVector by lazy {
        materialIcon(name = "Outlined.StarOutline") {
            materialPath {
                moveTo(22f, 9.24f)
                lineToRelative(-7.19f, -0.62f)
                lineTo(12f, 2f)
                lineTo(9.19f, 8.63f)
                lineTo(2f, 9.24f)
                lineToRelative(5.46f, 4.73f)
                lineTo(5.82f, 21f)
                lineTo(12f, 17.27f)
                lineTo(18.18f, 21f)
                lineToRelative(-1.63f, -7.03f)
                lineTo(22f, 9.24f)
                close()
                moveTo(12f, 15.4f)
                lineToRelative(-3.76f, 2.27f)
                lineToRelative(1f, -4.28f)
                lineToRelative(-3.32f, -2.88f)
                lineToRelative(4.38f, -0.38f)
                lineTo(12f, 6.1f)
                lineToRelative(1.71f, 4.04f)
                lineToRelative(4.38f, 0.38f)
                lineToRelative(-3.32f, 2.88f)
                lineToRelative(1f, 4.28f)
                lineTo(12f, 15.4f)
                close()
            }
        }
    }
}
