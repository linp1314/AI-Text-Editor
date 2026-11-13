package com.aieditor.text.data

import java.util.Locale

/**
 * API Key 按「接口地址」分别保存，切换服务商时互不影响。
 * 旧版本只存了一个全局 key，迁移时归到当时正在用的那个地址。
 */
object ApiKeySlots {

    /** SharedPreferences 里 Key 槽位的前缀。 */
    const val PREFIX = "api_key::"

    /** 归一化地址后作为存储槽位：忽略大小写、首尾空格与末尾斜杠。 */
    fun slot(baseUrl: String): String =
        PREFIX + baseUrl.trim().trimEnd('/').lowercase(Locale.ROOT)
}

/**
 * 设置页切换接口地址时的 Key 取值逻辑：
 * 离开旧地址前记住它当前的输入，进入新地址时取回该地址自己的 Key；
 * 该地址从未保存过 Key 就返回空，而不是沿用上一个服务商的 Key。
 *
 * 不依赖 Android，便于单元测试。
 */
class ApiKeySwitcher(
    initialBaseUrl: String,
    initialKey: String,
    private val lookup: (String) -> String,
) {
    private val cache = mutableMapOf(ApiKeySlots.slot(initialBaseUrl) to initialKey)

    var baseUrl: String = initialBaseUrl
        private set

    /** 当前地址应显示的 Key。 */
    var apiKey: String = initialKey
        private set

    /**
     * 切换到新地址。
     * @param currentKey 切换前输入框里的值，会记到旧地址名下。
     * @return 新地址应显示的 Key，没保存过则为空串。
     */
    fun moveTo(newBaseUrl: String, currentKey: String): String {
        cache[ApiKeySlots.slot(baseUrl)] = currentKey
        baseUrl = newBaseUrl
        apiKey = cache[ApiKeySlots.slot(newBaseUrl)]
            ?: lookup(newBaseUrl).also { cache[ApiKeySlots.slot(newBaseUrl)] = it }
        return apiKey
    }

    /**
     * 保存时提交：把当前输入也算进去，返回本次会话涉及的所有地址的 Key。
     * 空串表示用户清空了该地址的 Key，调用方应据此删除对应记录。
     */
    fun snapshot(currentKey: String): Map<String, String> =
        cache + (ApiKeySlots.slot(baseUrl) to currentKey)
}
