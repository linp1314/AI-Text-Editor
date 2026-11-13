package com.aieditor.text.data

/**
 * 把「任务 + 原文 + 分段信息」拼成发给模型的消息。
 *
 * 两种形态：
 * - 编辑：带着原文，模型在原文上改；
 * - 生成：编辑区为空时，指令本身就是创作要求，没有原文可改。
 */
object PromptBuilder {

    /** 从零生成：不带原文，只把任务当成创作要求。 */
    fun generate(system: String, instruction: String): List<ChatMessage> {
        val user = buildString {
            append("【任务】\n").append(instruction).append("\n\n")
            append("【说明】\n编辑区没有原文，这是一次从零创作。请直接按上面的任务生成完整内容，")
            append("要求：只输出成品正文，不要输出“以下是”“希望对你有帮助”之类的说明语，")
            append("不要解释你的思路，也不要重复任务要求本身。")
        }
        return listOf(ChatMessage("system", system), ChatMessage("user", user))
    }

    /** 编辑既有文本；[segmentTotal] > 1 时按分段处理本段。 */
    fun edit(
        system: String,
        instruction: String,
        context: String,
        isRefine: Boolean,
        segmentIndex: Int = 0,
        segmentTotal: Int = 1,
    ): List<ChatMessage> {
        val user = buildString {
            append("【任务】\n").append(instruction).append("\n\n")
            if (isRefine) {
                append("【说明】\n下面这段文本是上一轮 AI 编辑后的结果，请直接在它的基础上按本次任务继续修改，")
                append("保留没有被要求改动的部分。\n\n")
            }
            if (segmentTotal > 1) {
                append("【分段说明】\n")
                append("原文较长，已分成 ").append(segmentTotal).append(" 段依次处理，本段是第 ")
                append(segmentIndex + 1).append(" 段（共 ").append(segmentTotal).append(" 段）。\n")
                append("请只处理本段内容并直接输出结果：不要输出“第 x 段”“以下为处理结果”等任何说明文字，")
                append("不要补写原文中不存在的内容，也不要重复本段之外的文字。\n\n")
            }
            append("【原文】\n").append(context)
        }
        return listOf(ChatMessage("system", system), ChatMessage("user", user))
    }
}
