package android.content

/** DESKTOP STAND-IN, not Android: the part of SharedPreferences the shared phone files use (strings, and booleans kept as "true"/"false"). */
interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun getAll(): Map<String, *>
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        /** Written to disk before it returns (the desktop has no background writer); true if it worked. */
        fun commit(): Boolean
        fun apply()
    }
}
