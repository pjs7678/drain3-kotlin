// SPDX-License-Identifier: MIT

package io.github.drain3

import java.util.regex.Pattern

abstract class AbstractMaskingInstruction(val maskWith: String) {
    abstract fun mask(content: String, maskPrefix: String, maskSuffix: String): String
}

open class MaskingInstruction(pattern: String, maskWith: String) : AbstractMaskingInstruction(maskWith) {

    val regex: Pattern = Pattern.compile(pattern)

    // Pre-computed mask strings per prefix/suffix pair to avoid concatenation in hot path
    @Volatile
    private var cachedMask: String? = null
    @Volatile
    private var cachedPrefix: String? = null
    @Volatile
    private var cachedSuffix: String? = null

    val pattern: String
        get() = regex.pattern()

    override fun mask(content: String, maskPrefix: String, maskSuffix: String): String {
        val mask = getMask(maskPrefix, maskSuffix)
        return regex.matcher(content).replaceAll(mask)
    }

    private fun getMask(prefix: String, suffix: String): String {
        // Fast path: same prefix/suffix as last call (common case — always the same masker)
        if (prefix === cachedPrefix && suffix === cachedSuffix) return cachedMask!!
        if (prefix == cachedPrefix && suffix == cachedSuffix) return cachedMask!!
        val mask = prefix + maskWith + suffix
        cachedPrefix = prefix
        cachedSuffix = suffix
        cachedMask = mask
        return mask
    }
}

typealias RegexMaskingInstruction = MaskingInstruction

class LogMasker(
    val maskingInstructions: Collection<AbstractMaskingInstruction>,
    val maskPrefix: String,
    val maskSuffix: String
) {
    val maskNameToInstructions: Map<String, List<AbstractMaskingInstruction>>

    // Pre-materialized as array for faster iteration in hot path
    private val instructionsArray: Array<AbstractMaskingInstruction> = maskingInstructions.toTypedArray()

    init {
        val map = HashMap<String, MutableList<AbstractMaskingInstruction>>()
        for (mi in maskingInstructions) {
            map.getOrPut(mi.maskWith) { ArrayList() }.add(mi)
        }
        maskNameToInstructions = map
    }

    fun mask(content: String): String {
        var result = content
        for (mi in instructionsArray) {
            result = mi.mask(result, maskPrefix, maskSuffix)
        }
        return result
    }

    val maskNames: Collection<String>
        get() = maskNameToInstructions.keys

    fun instructionsByMaskName(maskName: String): Collection<AbstractMaskingInstruction> {
        return maskNameToInstructions[maskName] ?: emptyList()
    }
}
