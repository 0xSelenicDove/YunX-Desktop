package com.yunx.app.data.security

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import java.io.IOException
import java.util.UUID

/** Native Keychain access: secrets never pass through command arguments or shell output. */
internal object MacKeychain {
    val isMac: Boolean get() = System.getProperty("os.name").startsWith("Mac")
    private const val SERVICE = "com.yunx.desktop.credentials"
    private interface Security : Library {
        fun SecItemAdd(attributes: Pointer, result: PointerByReference?): Int
        fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int
        fun SecItemDelete(query: Pointer): Int
    }
    private interface CF : Library {
        fun CFDictionaryCreateMutable(allocator: Pointer?, capacity: NativeLong, keys: Pointer?, values: Pointer?): Pointer
        fun CFDictionarySetValue(dictionary: Pointer, key: Pointer, value: Pointer)
        fun CFStringCreateWithCString(allocator: Pointer?, value: String, encoding: Int): Pointer
        fun CFDataCreate(allocator: Pointer?, bytes: ByteArray, length: NativeLong): Pointer
        fun CFDataGetLength(data: Pointer): NativeLong
        fun CFDataGetBytePtr(data: Pointer): Pointer
        fun CFRelease(value: Pointer)
    }
    private val security by lazy { Native.load("Security", Security::class.java) }
    private val cf by lazy { Native.load("CoreFoundation", CF::class.java) }
    private val symbols by lazy { NativeLibrary.getInstance("Security") }
    private fun constant(name: String): Pointer = symbols.getGlobalVariableAddress(name).getPointer(0)

    // Treat denial, a locked keychain, and missing items as unavailable, never as permission to rotate a key.
    class Unavailable(message: String) : IOException(message)

    private fun check(status: Int) {
        if (status != 0) throw Unavailable("macOS Keychain unavailable (OSStatus $status); credential key unchanged")
    }

    private fun <T> query(id: String, key: ByteArray? = null, read: Boolean = false, action: (Pointer) -> T): T {
        if (!isMac) throw Unavailable("This credential key requires macOS Keychain")
        require(UUID.fromString(id).toString() == id) { "Invalid Keychain reference" }
        val dict = cf.CFDictionaryCreateMutable(null, NativeLong(0), null, null)
        val owned = mutableListOf<Pointer>()
        fun string(value: String) = cf.CFStringCreateWithCString(null, value, 0x08000100).also { owned.add(it) }
        fun put(name: String, value: Pointer) = cf.CFDictionarySetValue(dict, constant(name), value)
        try {
            put("kSecClass", constant("kSecClassGenericPassword"))
            put("kSecAttrService", string(SERVICE))
            put("kSecAttrAccount", string(id))
            if (key != null) {
                put("kSecAttrLabel", string("YunX Desktop credential key"))
                put("kSecValueData", cf.CFDataCreate(null, key, NativeLong(key.size.toLong())).also { owned.add(it) })
            }
            if (read) {
                val yes = NativeLibrary.getInstance("CoreFoundation").getGlobalVariableAddress("kCFBooleanTrue").getPointer(0)
                put("kSecReturnData", yes)
                put("kSecMatchLimit", constant("kSecMatchLimitOne"))
            }
            return action(dict)
        } finally {
            cf.CFRelease(dict)
            owned.forEach { cf.CFRelease(it) }
        }
    }

    fun store(key: ByteArray): String {
        require(key.size == 32)
        val id = UUID.randomUUID().toString()
        query(id, key) { check(security.SecItemAdd(it, null)) }
        return id
    }

    fun read(id: String): ByteArray = query(id, read = true) {
        val result = PointerByReference()
        check(security.SecItemCopyMatching(it, result))
        val data = result.value ?: throw Unavailable("Keychain returned no credential key")
        try {
            if (cf.CFDataGetLength(data).toLong() != 32L) throw Unavailable("Invalid Keychain credential key length")
            cf.CFDataGetBytePtr(data).getByteArray(0, 32)
        } finally {
            cf.CFRelease(data)
        }
    }

    // Only used to clean up the integration test's own random item. Keep real items for older database backups.
    internal fun deleteTestItem(id: String) = query(id) { check(security.SecItemDelete(it)) }
}
