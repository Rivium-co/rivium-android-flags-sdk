plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.vanniktech.maven.publish")
}

android {
    namespace = "co.rivium.flags"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    lint {
        targetSdk = 34
    }

    testOptions {
        targetSdk = 34
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

mavenPublishing {
    publishToMavenCentral(
        com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL,
        automaticRelease = true
    )
    signAllPublications()

    coordinates("co.rivium", "rivium-flags-android", "0.2.0")

    pom {
        name.set("Rivium Flags Android SDK")
        description.set("Rivium Flags client SDK for Android - server-evaluated feature flags with offline cache, typed getters and reasons")
        inceptionYear.set("2026")
        url.set("https://rivium.co")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }

        developers {
            developer {
                id.set("rivium")
                name.set("Rivium")
                email.set("founder@rivium.co")
                url.set("https://rivium.co")
            }
        }

        scm {
            url.set("https://github.com/Rivium-co/rivium-android-flags-sdk")
            connection.set("scm:git:git://github.com/Rivium-co/rivium-android-flags-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/Rivium-co/rivium-android-flags-sdk.git")
        }
    }
}
