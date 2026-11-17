package moe.chenxy.rosepods.core

/**
 * 极简反射工具，替代经典 Xposed API 的 XposedHelpers。
 *
 * 迁移到 libxposed 新 API 后不再依赖 `de.robv.android.xposed`，
 * 但插件的核心工作仍是调用系统蓝牙栈的隐藏方法，因此保留这一层。
 *
 * 只做两件事：按名字 + 参数类型找方法并调用、读静态 int 字段。
 */
object Reflect {

    fun callMethod(target: Any, name: String, vararg args: Any?): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val method = type.declaredMethods.firstOrNull {
                it.name == name && matches(it.parameterTypes, args)
            }
            if (method != null) {
                method.isAccessible = true
                return method.invoke(target, *args)
            }
            type = type.superclass
        }
        throw NoSuchMethodException("${target.javaClass.name}#$name(${args.joinToString { it?.javaClass?.simpleName ?: "null" }})")
    }

    fun getStaticIntField(clazz: Class<*>, name: String): Int {
        var type: Class<*>? = clazz
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                return field.getInt(null)
            }
            type = type.superclass
        }
        throw NoSuchFieldException("${clazz.name}#$name")
    }

    /** 读隐藏的静态对象字段（如 UserHandle.ALL） */
    fun getStaticField(clazz: Class<*>, name: String): Any? {
        var type: Class<*>? = clazz
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                return field.get(null)
            }
            type = type.superclass
        }
        throw NoSuchFieldException("${clazz.name}#$name")
    }

    private fun matches(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        for (i in types.indices) {
            val expected = box(types[i])
            val actual = args[i]?.javaClass ?: return false
            if (!expected.isAssignableFrom(actual)) return false
        }
        return true
    }

    private fun box(type: Class<*>): Class<*> = when (type) {
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        else -> type
    }
}