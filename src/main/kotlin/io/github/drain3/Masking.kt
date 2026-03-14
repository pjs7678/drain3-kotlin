// SPDX-License-Identifier: MIT

package io.github.drain3

import java.util.regex.Pattern

abstract class AbstractMaskingInstruction(val maskWith: String) {
    abstract fun mask(content: String, maskPrefix: String, maskSuffix: String): String
}

open class MaskingInstruction(pattern: String, maskWith: String) : AbstractMaskingInstruction(maskWith) {

    val regex: Pattern = Pattern.compile(pattern)

    val pattern: String
        get() = regex.pattern()

    override fun mask(content: String, maskPrefix: String, maskSuffix: String): String {
        val mask = maskPrefix + maskWith + maskSuffix
        return regex.matcher(content).replaceAll(mask)
    }
}

// Alias for MaskingInstruction
typealias RegexMaskingInstruction = MaskingInstruction

class LogMasker(
    val maskingInstructions: Collection<AbstractMaskingInstruction>,
    val maskPrefix: String,
    val maskSuffix: String
) {
    val maskNameToInstructions: Map<String, List<AbstractMaskingInstruction>>

    init {
        val map = mutableMapOf<String, MutableList<AbstractMaskingInstruction>>()
        for (mi in maskingInstructions) {
            map.getOrPut(mi.maskWith) { mutableListOf() }.add(mi)
        }
        maskNameToInstructions = map
    }

    fun mask(content: String): String {
        var result = content
        for (mi in maskingInstructions) {
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
