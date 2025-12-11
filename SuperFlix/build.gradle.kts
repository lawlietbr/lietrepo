android {
    compileSdk = 34
    namespace = "com.lietrepo.superflix"

    defaultConfig {
        minSdk = 21
    }
    
    // 🔥 ADICIONE ISSO:
    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false  // Desativa minificação
            isShrinkResources = false // Desativa remoção de recursos
        }
        getByName("release") {
            isMinifyEnabled = false  // TAMBÉM para release
            isShrinkResources = false
        }
    }
}