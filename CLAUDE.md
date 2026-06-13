# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Mihon is an open-source Android manga/webtoon reader app (formerly Tachiyomi). It's a multi-module Kotlin project using Jetpack Compose for UI and clean architecture principles.

## Build & Development Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build specific variants
./gradlew assembleStandardDebug    # Standard flavor debug
./gradlew assembleFossRelease      # FOSS flavor release
./gradlew assemblePreviewRelease   # Preview release

# Run all tests
./gradlew test

# Run unit tests for specific module
./gradlew :domain:test
./gradlew :data:test
./gradlew :app:testStandardDebugUnitTest

# Run single test class
./gradlew :domain:test --tests "tachiyomi.domain.chapter.service.ChapterRecognitionTest"

# Code formatting (Spotless with ktlint)
./gradlew spotlessCheck    # Check formatting
./gradlew spotlessApply    # Auto-fix formatting

# Clean build
./gradlew clean
```

## Architecture

### Module Structure

- **`:app`** - Main Android application with UI screens, DI setup, and app entry points
- **`:domain`** - Business logic layer with interactors/use cases and repository interfaces
- **`:data`** - Data layer implementations using SQLDelight for database operations
- **`:presentation-core`** - Shared Compose UI components, theme, and utilities
- **`:presentation-widget`** - Android app widgets (Glance-based)
- **`:source-api`** - Public API for manga source extensions (Kotlin Multiplatform)
- **`:source-local`** - Local manga source implementation
- **`:core:common`** - Common utilities and preference system
- **`:core:archive`** - Archive file handling (CBZ, ZIP, etc.)
- **`:core-metadata`** - Metadata parsing for manga files
- **`:i18n`** - Internationalization resources (Moko Resources)
- **`:telemetry`** - Firebase analytics/crashlytics integration

### Key Patterns

**Navigation**: Uses [Voyager](https://github.com/adrielcafe/voyager) for screen navigation. Screens follow `Screen → ScreenModel → UI` pattern.

**Dependency Injection**: Uses [Injekt](https://github.com/kohesive/injekt) (Kotlin DI). Modules registered in `AppModule.kt` and `DomainModule.kt`.

**Database**: SQLDelight with migrations in `data/src/main/sqldelight/tachiyomi/migrations/`. Type-safe queries generated from `.sq` files.

**Image Loading**: Coil 3 with custom fetchers for manga covers in `data/coil/`.

**Preferences**: Custom preference system in `core:common` with type-safe accessors.

### Package Organization

```
eu.kanade.tachiyomi/
├── ui/              # Screens and ViewModels (by feature)
│   ├── library/
│   ├── manga/
│   ├── reader/
│   └── ...
├── data/            # Data managers (download, cache, tracking)
├── di/              # DI modules
├── extension/       # Extension system
└── source/          # Source management

tachiyomi.domain/    # Domain layer (interactors, models, repositories)
tachiyomi.data/      # Data layer (SQLDelight implementations)
```

## Build Configuration

### Build Variants

- **Flavors**: `standard` (with telemetry), `foss` (no proprietary services)
- **Build types**: `debug`, `release`, `preview`, `foss`, `benchmark`

### Build Properties

Control build behavior with Gradle properties:
- `include-telemetry` - Include Firebase analytics
- `enable-updater` - Enable app update checker
- `disable-code-shrink` - Disable R8/ProGuard

Example: `./gradlew assembleDebug -Pinclude-telemetry`

## Code Style

- Kotlin with official code style
- Enforced via [Spotless](https://github.com/diffplug/spotless) + [ktlint](https://github.com/pinterest/ktlint)
- Run `./gradlew spotlessApply` before committing
- XML resources also formatted by Spotless

## Key Dependencies

- **UI**: Jetpack Compose with Material 3
- **Navigation**: Voyager
- **Database**: SQLDelight (async with coroutines)
- **Networking**: OkHttp 5
- **Serialization**: kotlinx.serialization (JSON, ProtoBuf, XML)
- **Image Loading**: Coil 3
- **DI**: Injekt
- **Coroutines**: kotlinx.coroutines with Flow

## Testing

Tests are located in standard Gradle test directories. Domain layer has the most test coverage. Run specific test suites with:

```bash
./gradlew :domain:test --tests "tachiyomi.domain.*"
```

## Extension System

Manga sources are implemented as extensions (separate APKs). The `source-api` module defines the interface that extensions implement. Extensions are loaded dynamically at runtime.

## Translations

Managed externally via [Weblate](https://hosted.weblate.org/engage/mihon/). String resources in `i18n/src/commonMain/resources/MR/base/`.
