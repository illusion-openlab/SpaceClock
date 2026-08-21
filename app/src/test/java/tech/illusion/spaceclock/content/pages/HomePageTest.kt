package tech.illusion.spaceclock.content.pages

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePageTest {
    @Test fun `non current-location city keeps original title`() {
        assertEquals(
            "北京 · 今日天气",
            homeTitleLine(cityName = "北京", isCurrentLocation = false, displayName = null, isLocating = false),
        )
    }

    @Test fun `current-location with displayName shows real city name regardless of locating`() {
        assertEquals(
            "当前位置：上海 · 今日天气",
            homeTitleLine(cityName = "当前位置", isCurrentLocation = true, displayName = "上海", isLocating = false),
        )
        assertEquals(
            "当前位置：上海 · 今日天气",
            homeTitleLine(cityName = "当前位置", isCurrentLocation = true, displayName = "上海", isLocating = true),
        )
    }

    @Test fun `current-location never resolved and locating shows locating text`() {
        assertEquals(
            "当前位置 · 定位中…",
            homeTitleLine(cityName = "当前位置", isCurrentLocation = true, displayName = null, isLocating = true),
        )
    }

    @Test fun `current-location never resolved and not locating falls back to plain title`() {
        assertEquals(
            "当前位置 · 今日天气",
            homeTitleLine(cityName = "当前位置", isCurrentLocation = true, displayName = null, isLocating = false),
        )
    }
}
