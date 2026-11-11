package com.whistlesong.music

/**
 * A musical style controls both how the composer writes the continuation
 * (interval / rhythm tendencies, harmony) and how the arranger + synth render it.
 */
enum class Style(
    val label: String,
    val emoji: String,
    val description: String,
    /** Where the melody's median pitch should sit (MIDI). */
    val melodyCenter: Int,
    /** Preferred tempo range; the whistled tempo is folded/clamped into it. */
    val minBpm: Double,
    val maxBpm: Double,
    /** 0.5 = straight eighths, ~0.66 = triplet swing. */
    val swing: Double,
    /**
     * Weights for melodic intervals in scale steps, index 0 = unison, 1 = step, 2 = third...
     * up to 7 = octave. Applied symmetrically up/down.
     */
    val intervalPrior: DoubleArray,
    /** Probability mass multiplier for notes that are chord tones on strong beats. */
    val chordToneBias: Double,
    /** Avoid these scale degrees (0-based) on strong beats, e.g. pentatonic rock avoids 4th/7th. */
    val avoidDegrees: Set<Int>,
    /** Typical progressions (scale degrees, 0 = I) used for newly composed sections. */
    val majorProgressions: List<IntArray>,
    val minorProgressions: List<IntArray>,
    val useSevenths: Boolean,
    /** How much busier/sparser the composer's rhythms get vs. the whistled ones (-1..1). */
    val rhythmDensity: Double,
) {
    CHIPTUNE(
        "8-Bit", "👾", "Square-wave lead, triangle bass, arpeggios",
        melodyCenter = 76, minBpm = 120.0, maxBpm = 170.0, swing = 0.5,
        intervalPrior = doubleArrayOf(0.8, 3.0, 2.4, 1.2, 1.0, 0.4, 0.2, 0.6),
        chordToneBias = 2.5, avoidDegrees = emptySet(),
        majorProgressions = listOf(intArrayOf(0, 5, 3, 4), intArrayOf(0, 3, 4, 0), intArrayOf(5, 3, 0, 4)),
        minorProgressions = listOf(intArrayOf(0, 5, 6, 0), intArrayOf(0, 3, 4, 0), intArrayOf(0, 6, 5, 4)),
        useSevenths = false, rhythmDensity = 0.4,
    ),
    ROCK(
        "Rock", "🎸", "Distorted guitars, power chords, live drums",
        melodyCenter = 67, minBpm = 100.0, maxBpm = 150.0, swing = 0.5,
        intervalPrior = doubleArrayOf(2.0, 3.0, 1.6, 0.9, 0.7, 0.2, 0.1, 0.3),
        chordToneBias = 2.0, avoidDegrees = setOf(3, 6),
        majorProgressions = listOf(intArrayOf(0, 4, 5, 3), intArrayOf(0, 3, 0, 4), intArrayOf(0, 6, 3, 0)),
        minorProgressions = listOf(intArrayOf(0, 5, 6, 0), intArrayOf(0, 6, 5, 6), intArrayOf(0, 3, 6, 4)),
        useSevenths = false, rhythmDensity = 0.1,
    ),
    LOFI(
        "Lo-Fi", "☕", "Dusty e-piano, lazy swing, vinyl crackle",
        melodyCenter = 72, minBpm = 70.0, maxBpm = 92.0, swing = 0.6,
        intervalPrior = doubleArrayOf(0.8, 3.5, 1.8, 0.8, 0.5, 0.3, 0.1, 0.1),
        chordToneBias = 1.6, avoidDegrees = emptySet(),
        majorProgressions = listOf(intArrayOf(1, 4, 0, 5), intArrayOf(3, 2, 1, 4), intArrayOf(0, 2, 3, 4)),
        minorProgressions = listOf(intArrayOf(0, 3, 6, 2), intArrayOf(5, 4, 0, 0), intArrayOf(3, 4, 0, 5)),
        useSevenths = true, rhythmDensity = -0.3,
    ),
    SYNTHWAVE(
        "Synthwave", "🌆", "Big saw leads, pulsing bass, gated drums",
        melodyCenter = 72, minBpm = 90.0, maxBpm = 125.0, swing = 0.5,
        intervalPrior = doubleArrayOf(1.0, 3.0, 2.0, 1.0, 1.0, 0.4, 0.2, 0.7),
        chordToneBias = 2.0, avoidDegrees = emptySet(),
        majorProgressions = listOf(intArrayOf(5, 3, 0, 4), intArrayOf(0, 4, 5, 3), intArrayOf(3, 4, 5, 5)),
        minorProgressions = listOf(intArrayOf(0, 5, 2, 6), intArrayOf(0, 6, 5, 6), intArrayOf(0, 5, 3, 4)),
        useSevenths = false, rhythmDensity = 0.0,
    ),
    JAZZ(
        "Jazz", "🎷", "Swing ride, walking bass, 7th-chord comping",
        melodyCenter = 70, minBpm = 110.0, maxBpm = 170.0, swing = 0.66,
        intervalPrior = doubleArrayOf(0.4, 3.5, 2.2, 1.2, 0.8, 0.6, 0.4, 0.3),
        chordToneBias = 1.8, avoidDegrees = emptySet(),
        majorProgressions = listOf(intArrayOf(1, 4, 0, 0), intArrayOf(0, 5, 1, 4), intArrayOf(2, 5, 1, 4)),
        minorProgressions = listOf(intArrayOf(1, 4, 0, 0), intArrayOf(0, 5, 1, 4), intArrayOf(0, 3, 1, 4)),
        useSevenths = true, rhythmDensity = 0.3,
    ),
    MUSIC_BOX(
        "Music Box", "🎠", "Twinkly bells and gentle broken chords",
        melodyCenter = 81, minBpm = 72.0, maxBpm = 108.0, swing = 0.5,
        intervalPrior = doubleArrayOf(0.8, 3.5, 2.0, 0.8, 0.6, 0.3, 0.1, 0.3),
        chordToneBias = 2.2, avoidDegrees = emptySet(),
        majorProgressions = listOf(intArrayOf(0, 3, 4, 0), intArrayOf(0, 5, 3, 4), intArrayOf(0, 4, 5, 3)),
        minorProgressions = listOf(intArrayOf(0, 3, 4, 0), intArrayOf(0, 5, 3, 4), intArrayOf(0, 6, 2, 4)),
        useSevenths = false, rhythmDensity = -0.2,
    );

    fun progressions(mode: Mode) = if (mode == Mode.MAJOR) majorProgressions else minorProgressions

    /** Scale degrees to avoid on strong beats. Pentatonic avoidance differs between major and minor. */
    fun avoid(mode: Mode): Set<Int> =
        if (avoidDegrees.isNotEmpty() && mode == Mode.MINOR) setOf(1, 5) else avoidDegrees
}
