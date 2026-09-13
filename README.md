# react-native-video

> A <Video /> component for react-native

---

## Table of Contents

1. [Overview](#overview)
2. [Tech Stack](#tech-stack)
3. [Repository Structure](#repository-structure)
4. [Key Components](#key-components)
5. [Setup & Installation](#setup--installation)
6. [Running the Application](#running-the-application)
7. [Infrastructure & CI/CD](#infrastructure--cicd)
8. [Contributing](#contributing)

---

## Overview

**react-native-video** is part of the [Unacademy](https://github.com/unacademy) engineering ecosystem.

A <Video /> component for react-native

**Topics / Tags:** `approvers-1`

---

## Tech Stack

| Attribute | Value |
|-----------|-------|
| **Primary Language** | Java |
| **Framework / Platform** | React |
| **Package Manager** | Gradle |

### Dependencies

**Runtime Dependencies:**
- `keymirror`
- `prop-types`

**Dev Dependencies:**
- `eslint`
- `babel-eslint`
- `eslint-plugin-react`
- `eslint-config-airbnb`


---

## Repository Structure

```
react-native-video/
├── .eslintrc
├── .github
├── .gitignore
├── CHANGELOG.md
├── LICENSE
├── README.md
├── TextTrackType.js
├── Video.js
├── VideoResizeMode.js
├── ViewPropTypes
├── android/
│   ├── build.gradle
│   ├── src
├── android-exoplayer/
│   ├── README.md
│   ├── build.gradle
│   ├── src
├── docs/
│   ├── AppTransportSecuritySetting.png
│   ├── caching.md
│   ├── tvOS-step-1.jpg
│   ├── tvOS-step-2.jpg
│   ├── tvOS-step-3.jpg
├── dom/
│   ├── LICENSE.md
│   ├── RCTVideo.js
│   ├── RCTVideoEvent.js
│   ├── RCTVideoManager.js
│   ├── index.js
├── examples/
│   ├── basic
│   ├── video-caching
├── ios/
│   ├── RCTVideo.xcodeproj
│   ├── Video
│   ├── VideoCaching
... (truncated)
```

---

## Key Components

Below is an analysis of the key files and modules:

| File / Directory | Purpose |
|-----------------|---------|
| `CHANGELOG.md` | Source file |
| `LICENSE` | Source file |
| `README.md` | Source file |
| `TextTrackType.js` | Source file |
| `Video.js` | Source file |
| `VideoResizeMode.js` | Source file |
| `ViewPropTypes` | Source file |
| `android-exoplayer/README.md` | Source file |
| `android-exoplayer/build.gradle` | Source file |
| `android-exoplayer/src/main/AndroidManifest.xml` | Source file |
| `android-exoplayer/src/main/java/com/brentvatne/exoplayer/AspectRatioFrameLayout.java` | Source file |
| `android-exoplayer/src/main/java/com/brentvatne/exoplayer/DataSourceUtil.java` | Source file |
| `android-exoplayer/src/main/java/com/brentvatne/exoplayer/ExoPlayerView.java` | Source file |
| `android-exoplayer/src/main/java/com/brentvatne/exoplayer/RawResourceDataSourceFactory.java` | Source file |
| `android-exoplayer/src/main/java/com/brentvatne/exoplayer/ReactExoplayerView.java` | Source file |
| `android-exoplayer/src/main/java/com/brentvatne/exoplayer/ReactExoplayerViewManager.java` | Source file |
| `android-exoplayer/src/main/java/com/brentvatne/exoplayer/ResizeMode.java` | Source file |

> **Note:** Only the first 20 non-trivial files are listed. See the repository tree above for the complete structure.

---

## Setup & Installation

### Prerequisites

- Git (`git --version`)
- Java runtime installed



### Steps

```bash
# 1. Clone the repository
git clone git@github.com:unacademy/react-native-video.git
cd react-native-video

# 2. Install dependencies
npm install      # or yarn install
```

### Available Scripts

```bash
# test
npm run test   # node_modules/.bin/eslint *.js

```


---

## Running the Application

_Refer to the project's build system or CI configuration._

---

## Infrastructure & CI/CD

- Test suite present — run tests before submitting PRs

---

## Contributing

1. Create a feature branch: `git checkout -b feat/your-feature`
2. Commit your changes: `git commit -m "feat: describe your change"`
3. Push and open a PR targeting `master`
4. Ensure all CI checks pass before requesting review

---

## Original README (Excerpt)

> ## react-native-video

A `<Video>` component for react-native, as seen in
[react-native-login](https://github.com/brentvatne/react-native-login)!

Requires react-native >= 0.40.0

### Version 4.0.0 breaking changes
Version 4.0.0 now requires Android SDK 26 or higher to use ExoPlayer. This is the default version as of React Native 0.56 and will be required by Google for all apps in October 2018.

### Version 3.0.0 breaking changes
Version 3.0 features a number of changes to existing behavior. See [Updating](#updating) for changes.

## Table of Contents

* [Installation](#installation)
* [Usage]

---

*This README was auto-generated on 2026-09-14 by the Unacademy repo-summarizer tool.*
*For corrections or additions, edit this file directly or open an issue.*
