import java.util.Properties

plugins {
    id("com.android.library")
}

android {
    buildFeatures {
        buildConfig = true
    }
    
    namespace = "com.StreamFlix"
    compileSdk = 33

    defaultConfig {
        minSdk = 21

        val tmdbApiKey = project.findProperty("TMDB_API_KEY") as? String
            ?: System.getenv("TMDB_API_KEY")
            ?: getLocalProperty("TMDB_API_KEY")
            ?: "dummy_api_key"

        val tmdbAccessToken = project.findProperty("TMDB_ACCESS_TOKEN") as? String
            ?: System.getenv("TMDB_ACCESS_TOKEN")
            ?: getLocalProperty("TMDB_ACCESS_TOKEN")
            ?: "dummy_access_token"

        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "TMDB_ACCESS_TOKEN", "\"$tmdbAccessToken\"")
    }
}

fun getLocalProperty(key: String): String? {
    val localProperties = Properties()
    val localPropertiesFile = project.rootProject.file("local.properties")
    return if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
        localProperties.getProperty(key)
    } else null
}

cloudstream {
    version = 2
    description = "StreamFlix - Filmes e Séries Online em Português | Lançamentos e Clássicos"
    language = "pt-br"
    authors = listOf("lawlietbr")
    status = 0 // site fora do ar (domínio morto) - 2026-11
    tvTypes = listOf("Movies", "Series")
    iconUrl = "https://openclipart.org/image/2400px/svg_to_png/193323/-S.png"
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
