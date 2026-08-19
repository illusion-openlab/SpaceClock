package tech.illusion.spaceclock

import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.dsl.DefaultWindowContainer
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import tech.illusion.spaceclock.content.ClockVolume
import tech.illusion.spaceclock.theme.SpaceClockColorScheme

/** 全程只有这一个容器：不开第二个 WindowContainer、不开 Stage、不做 Full Space 切换。 */
fun mainApp(scope: SpatialAppScope) = with(scope) {
    DefaultWindowContainer {
        PicoTheme(colorScheme = SpaceClockColorScheme) { ClockVolume() }
    }
}
