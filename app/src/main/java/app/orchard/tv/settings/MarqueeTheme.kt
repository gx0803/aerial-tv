package app.orchard.tv.settings

/**
 * The light around Home's edge. tvOS has no such thing; this is the projector's own decoration,
 * so it lives in Control Center > Tools rather than in Personalize.
 *
 * Each theme is a hue window walked by the phase, so one set of drawing code covers all of them.
 */
enum class MarqueeTheme(
    val key: String,
    val label: String,
    /** Hue window in degrees, walked from start to end every lap. */
    val hueFrom: Float,
    val hueTo: Float,
    val saturation: Float,
) {
    Rainbow("rainbow", "流光溢彩", 0f, 360f, 0.82f),
    Spring("spring", "春节红金", 350f, 420f, 0.95f),
    Snow("snow", "雪夜蓝白", 185f, 232f, 0.35f),
    Aurora("aurora", "极光青绿", 96f, 300f, 0.72f),
    Neon("neon", "赛博霓虹", 282f, 200f, 1f),
    Off("off", "关闭", 0f, 360f, 0f);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Rainbow
    }
}
