package de.alexandermora.sethome.data

// Kotlin data classes have no equivalent of a Java record's compact constructor, so normalizing the
// dimension before it becomes part of the (private) primary constructor's `val` - and therefore part of
// equals/hashCode/toString/copy - goes through a private constructor plus a companion `invoke`; callers
// still just write HomeLocation(...). @ConsistentCopyVisibility keeps the generated copy() private too, so
// it can't be used to bypass normalization.
@ConsistentCopyVisibility
data class HomeLocation private constructor(
    val dimension: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float
) {
    init {
        require(dimension.isNotBlank()) { "Dimension cannot be blank" }
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Home coordinates must be finite" }
        require(yaw.isFinite() && pitch.isFinite()) { "Home rotation must be finite" }
    }

    companion object {
        operator fun invoke(
            dimension: String,
            x: Double,
            y: Double,
            z: Double,
            yaw: Float,
            pitch: Float
        ): HomeLocation = HomeLocation(normalizeDimension(dimension), x, y, z, yaw, pitch)

        /**
         * Accepts both the current identifier form (minecraft:overworld) and the
         * legacy ResourceKey[minecraft:dimension / minecraft:overworld] form.
         */
        fun normalizeDimension(dimension: String?): String {
            var value = dimension?.trim() ?: ""

            if (value.startsWith("ResourceKey[") || value.startsWith("ResourceKey(")) {
                val slash = value.indexOf('/')
                val endSquare = value.lastIndexOf(']')
                val endRound = value.lastIndexOf(')')
                val end = maxOf(endSquare, endRound)
                if (slash >= 0 && end > slash) {
                    value = value.substring(slash + 1, end).trim()
                }
            }

            return value
        }
    }
}
