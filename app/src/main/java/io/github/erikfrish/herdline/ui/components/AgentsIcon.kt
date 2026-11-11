package io.github.erikfrish.herdline.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/*
 * Material Symbols `smart_toy`, the Agents destination's icon. The path data is
 * copied from material-icons-extended (Icons.Filled.SmartToy and
 * Icons.Outlined.SmartToy, Copyright The Android Open Source Project,
 * Apache-2.0), so the app keeps only the core icons dependency.
 */

/** Selected: a filled robot head. */
val AgentsIconFilled: ImageVector by lazy {
    agentsIcon(
        "AgentsFilled",
        "M20,9V7c0,-1.1 -0.9,-2 -2,-2h-3c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5H6C4.9,5 4,5.9 4,7v2c-1.66,0 -3,1.34 -3,3" +
            "s1.34,3 3,3v4c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2v-4c1.66,0 3,-1.34 3,-3S21.66,9 20,9z" +
            "M7.5,11.5C7.5,10.67 8.17,10 9,10s1.5,0.67 1.5,1.5S9.83,13 9,13S7.5,12.33 7.5,11.5z" +
            "M16,17H8v-2h8V17z" +
            "M15,13c-0.83,0 -1.5,-0.67 -1.5,-1.5S14.17,10 15,10s1.5,0.67 1.5,1.5S15.83,13 15,13z",
        PathFillType.NonZero,
    )
}

/** Unselected: the outlined robot head. */
val AgentsIconOutlined: ImageVector by lazy {
    agentsIcon(
        "AgentsOutlined",
        "M20,9V7c0,-1.1 -0.9,-2 -2,-2h-3c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5H6C4.9,5 4,5.9 4,7v2c-1.66,0 -3,1.34 -3,3" +
            "c0,1.66 1.34,3 3,3v4c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2v-4c1.66,0 3,-1.34 3,-3C23,10.34 21.66,9 20,9z" +
            "M18,19L6,19V7h12V19z" +
            "M9,13c-0.83,0 -1.5,-0.67 -1.5,-1.5S8.17,10 9,10s1.5,0.67 1.5,1.5S9.83,13 9,13z" +
            "M16.5,11.5c0,0.83 -0.67,1.5 -1.5,1.5s-1.5,-0.67 -1.5,-1.5S14.17,10 15,10S16.5,10.67 16.5,11.5z" +
            "M8,15h8v2H8V15z",
        PathFillType.EvenOdd,
    )
}

private fun agentsIcon(name: String, path: String, fill: PathFillType): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), pathFillType = fill, fill = SolidColor(Color.Black))
        .build()
