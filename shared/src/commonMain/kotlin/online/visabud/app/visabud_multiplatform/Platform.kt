package online.visabud.app.visabud_multiplatform

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform