package tech.illusion.spaceclock.time

/**
 * 一个内置城市条目。经纬度是 Task 4 地球标记点的输入，不是展示用的装饰字段，不能缺。
 */
data class City(val name: String, val zoneId: String, val lat: Float, val lon: Float)
