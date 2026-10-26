@file:Suppress("DEPRECATION")

package com.dot.trace.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsBike
import androidx.compose.material.icons.rounded.DirectionsBus
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.DirectionsSubway
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Train
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.dot.trace.trips.TransportMode

/** 이동 수단별 색과 아이콘. 지도 위에서는 테두리 선을 함께 그려 밝은·어두운 지도 모두에서 잘 보이게 한다. */
object ModeStyle {
    fun color(m: TransportMode): Color = when (m) {
        TransportMode.Walk -> Color(0xFF9A9AA4)
        TransportMode.Bicycle -> Color(0xFFD4FF3A)
        TransportMode.Bus -> Color(0xFF4DA3FF)
        TransportMode.Subway -> Color(0xFFB48CFF)
        TransportMode.Car -> Color(0xFFFF9F43)
        TransportMode.Train -> Color(0xFFFF6FB5)
        TransportMode.Unknown -> Color(0xFF8B8B90)
    }

    fun icon(m: TransportMode): ImageVector = when (m) {
        TransportMode.Walk -> Icons.Rounded.DirectionsWalk
        TransportMode.Bicycle -> Icons.Rounded.DirectionsBike
        TransportMode.Bus -> Icons.Rounded.DirectionsBus
        TransportMode.Subway -> Icons.Rounded.DirectionsSubway
        TransportMode.Car -> Icons.Rounded.DirectionsCar
        TransportMode.Train -> Icons.Rounded.Train
        TransportMode.Unknown -> Icons.Rounded.MoreHoriz
    }
}
