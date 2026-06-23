package letmutex.compose.nativehost

import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.DirectContext
import kotlin.test.Test

class ReflectionTest {
    @Test
    fun testReflection() {
        println("=== org.jetbrains.skia.DirectContext methods ===")
        DirectContext::class.java.methods.forEach { method ->
            if (method.name.contains("GL", ignoreCase = true) || 
                method.name.contains("Metal", ignoreCase = true) || 
                method.name.contains("D3D", ignoreCase = true) || 
                method.name.contains("Direct", ignoreCase = true)) {
                println(method.toString())
            }
        }
        DirectContext.Companion::class.java.methods.forEach { method ->
            if (method.name.contains("GL", ignoreCase = true) || 
                method.name.contains("Metal", ignoreCase = true) || 
                method.name.contains("D3D", ignoreCase = true) || 
                method.name.contains("Direct", ignoreCase = true)) {
                println("Companion: " + method.toString())
            }
        }

        println("=== org.jetbrains.skia.BackendRenderTarget methods ===")
        BackendRenderTarget::class.java.methods.forEach { method ->
            if (method.name.contains("GL", ignoreCase = true) || 
                method.name.contains("Metal", ignoreCase = true) || 
                method.name.contains("D3D", ignoreCase = true) || 
                method.name.contains("Direct", ignoreCase = true)) {
                println(method.toString())
            }
        }
        BackendRenderTarget.Companion::class.java.methods.forEach { method ->
            if (method.name.contains("GL", ignoreCase = true) || 
                method.name.contains("Metal", ignoreCase = true) || 
                method.name.contains("D3D", ignoreCase = true) || 
                method.name.contains("Direct", ignoreCase = true)) {
                println("Companion: " + method.toString())
            }
        }
        println("=== Java Reflection Parameter Names ===")
        try {
            val makeD3DMethod = BackendRenderTarget.Companion::class.java.methods.firstOrNull { 
                it.name == "makeDirect3D" && it.parameterCount == 6 
            }
            if (makeD3DMethod != null) {
                val params = makeD3DMethod.parameters.map { "${it.name}: ${it.type.simpleName}" }
                println("BackendRenderTarget.makeDirect3D parameters: " + params.joinToString(", "))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
