#!/usr/bin/env bash
# Adds the missing Gradle scaffolding to your repo and fixes the build workflow.
# Run this from the ROOT of your 'car' repo (where MainActivity.kt's app/ folder already lives).
set -e

cat > "build.gradle" << 'ROVER_EOF'
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.5.0'
        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24'
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}
ROVER_EOF
echo "Created build.gradle"

cat > "settings.gradle" << 'ROVER_EOF'
rootProject.name = "AnimalRoverApp"
include ':app'
ROVER_EOF
echo "Created settings.gradle"

cat > "gradle.properties" << 'ROVER_EOF'
org.gradle.jvmargs=-Xmx2048m
android.useAndroidX=true
kotlin.code.style=official
ROVER_EOF
echo "Created gradle.properties"

mkdir -p "app"
cat > "app/build.gradle" << 'ROVER_EOF'
apply plugin: 'com.android.application'
apply plugin: 'kotlin-android'

android {
    namespace 'com.example.animalrover'
    compileSdk 34

    defaultConfig {
        applicationId "com.example.animalrover"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        debug {
            minifyEnabled false
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }
}

dependencies {
    implementation "androidx.core:core-ktx:1.13.1"
    implementation "androidx.appcompat:appcompat:1.7.0"

    implementation "androidx.camera:camera-core:1.3.4"
    implementation "androidx.camera:camera-camera2:1.3.4"
    implementation "androidx.camera:camera-lifecycle:1.3.4"
    implementation "androidx.camera:camera-view:1.3.4"

    implementation "com.google.mlkit:object-detection:17.0.1"
}
ROVER_EOF
echo "Created app/build.gradle"

mkdir -p "app/src/main"
cat > "app/src/main/AndroidManifest.xml" << 'ROVER_EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-feature android:name="android.hardware.camera.any" />

    <application
        android:allowBackup="true"
        android:label="Animal Rover"
        android:theme="@style/Theme.AppCompat">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>

</manifest>
ROVER_EOF
echo "Created app/src/main/AndroidManifest.xml"

mkdir -p ".github/workflows"
cat > ".github/workflows/build.yml" << 'ROVER_EOF'
name: Build Rover APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      - name: Generate Gradle wrapper
        run: gradle wrapper --gradle-version 8.7

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build debug APK
        run: ./gradlew assembleDebug

      - name: Upload APK as build artifact
        uses: actions/upload-artifact@v4
        with:
          name: animal-rover-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
ROVER_EOF
echo "Created .github/workflows/build.yml"

echo "Done. Now run: git add . && git commit -m 'Add gradle scaffold' && git push"
