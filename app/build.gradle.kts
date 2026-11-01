plugins {
    id("com.android.application")
}

android {
    namespace = "com.huajiao.calendar"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.huajiao.calendar"
        minSdk = 24
        targetSdk = 35
        versionCode = 7
        versionName = "2.1"
    }

    buildTypes {
        debug {
            // 调试包也按 release 的规则做资源裁剪，避免两个包体积差太多误导判断
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 没有任何第三方依赖：WebView 是系统组件，网络用 HttpURLConnection。
// 不引依赖的好处是构建快、离线也能编，缺点是以后要用 Jetpack 得自己加。
dependencies {
}
