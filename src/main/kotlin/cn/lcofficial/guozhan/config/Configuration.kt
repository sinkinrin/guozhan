package cn.lcofficial.guozhan.config

import cn.lcofficial.guozhan.Guozhan
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import kotlin.reflect.KProperty
interface StaticLazy {
    fun init(){}
}
open class Configuration(val name: String) {

    lateinit var file: File
    val config: YamlConfiguration = YamlConfiguration()

    private val delegates = mutableListOf<ConfigDelegate<*>>() // 用于批量初始化默认值

    open fun init(plugin: Guozhan) {
        file = plugin.dataFolder.resolve(name)
        if (!file.exists()) file.createNewFile()
        config.load(file)

        // 一次性写入所有默认值
        delegates.forEach { it.ensureDefault() }
        save()
    }

    protected fun save() {
        config.save(file)
    }

    // ========= 属性委托基类 =========
    abstract inner class ConfigDelegate<T>(
        private val path: String,
        private val default: T
    ) {
        init {
            delegates.add(this) // 注册委托，用于初始化默认值
        }

        // 确保配置里有默认值
        fun ensureDefault() {
            if (!config.contains(path)) {
                config.set(path, default())
            }
        }
        open fun default(): Any = default as Any

        protected abstract fun getValueFromConfig(): T
        protected abstract fun setValueToConfig(value: T)

        operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
            return getValueFromConfig()
        }

        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            setValueToConfig(value)
            save()
        }
    }

    // ========= 基础类型委托工厂 =========
    fun string(path: String, default: String) =
        object : ConfigDelegate<String>(path, default) {
            override fun getValueFromConfig() = config.getString(path) ?: default
            override fun setValueToConfig(value: String) = config.set(path, value)
        }

    fun int(path: String, default: Int) =
        object : ConfigDelegate<Int>(path, default) {
            override fun getValueFromConfig() = config.getInt(path, default)
            override fun setValueToConfig(value: Int) = config.set(path, value)
        }

    fun double(path: String, default: Double) =
        object : ConfigDelegate<Double>(path, default) {
            override fun getValueFromConfig() = config.getDouble(path, default)
            override fun setValueToConfig(value: Double) = config.set(path, value)
        }

    fun boolean(path: String, default: Boolean) =
        object : ConfigDelegate<Boolean>(path, default) {
            override fun getValueFromConfig() = config.getBoolean(path, default)
            override fun setValueToConfig(value: Boolean) = config.set(path, value)
        }

    fun bool(path: String, default: Boolean) = boolean(path, default)

    fun long(path: String, default: Long) =
        object : ConfigDelegate<Long>(path, default) {
            override fun getValueFromConfig() = config.getLong(path, default)
            override fun setValueToConfig(value: Long) = config.set(path, value)
        }

    fun stringList(path: String, default: List<String>) =
        object : ConfigDelegate<List<String>>(path, default) {
            override fun getValueFromConfig() = config.getStringList(path).ifEmpty { default }
            override fun setValueToConfig(value: List<String>) = config.set(path, value)
        }

    // 🔧 v1.3.31: 添加 intList 委托方法
    fun intList(path: String, default: List<Int>) =
        object : ConfigDelegate<List<Int>>(path, default) {
            override fun getValueFromConfig() = config.getIntegerList(path).ifEmpty { default }
            override fun setValueToConfig(value: List<Int>) = config.set(path, value)
        }

    inline fun <reified T : Enum<T>> enum(path: String, default: T) =
        object : ConfigDelegate<T>(path, default) {
            override fun getValueFromConfig(): T {
                val value = config.getString(path)
                return try {
                    if (value != null) enumValueOf<T>(value) else default
                } catch (e: IllegalArgumentException) {
                    default
                }
            }

            override fun setValueToConfig(value: T) {
                config.set(path, value.name)
            }

            override fun default(): Any = default.name
        }
}

