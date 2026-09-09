# // NEONFLUX: PROTOCOL INITIATION

### **MANUAL OVERRIDE REQUIRED**

You are attempting to install **NeonFlux**, an experimental haptic synchronization protocol. This software is not available on standard distribution channels (Play Store). To proceed, you must bypass Android's standard security protocols.

---

## ⚠️ SECURITY WARNING: READ BEFORE PROCEEDING

**By enabling "Developer Options" and "USB/Wireless Debugging," you are lowering your device's shields.**

1.  **Sideloading Risk:** Installing APKs from unknown sources can expose your device to malicious code. Only install files obtained directly from the [Official NeonFlux Release Page](https://github.com/ASPDesignLabs/NEONFLUX/releases).
2.  **Open Ports:** ADB (Android Debug Bridge) allows external computers to send commands to your device. Always **disable USB/Wireless Debugging** when you are finished installing NeonFlux.
3.  **Warranty Void:** While rare, improper use of developer tools can destabilize your device. **Proceed at your own risk.**

---

## 01 // ACQUIRE THE BRIDGE (ADB)

You cannot install NeonFlux without **ADB** (Android Debug Bridge). This is a command-line tool that lets your computer talk to your Android devices.

### **:: WINDOWS**
1.  Download [SDK Platform Tools for Windows](https://developer.android.com/studio/releases/platform-tools).
2.  Extract the ZIP file to a folder you can easily access (e.g., `C:\adb`).
3.  Open the folder. Right-click anywhere in the empty white space and select **"Open in Terminal"** or **"Open PowerShell window here"**.

### **:: macOS**
1.  Open **Terminal** (Command + Space, type "Terminal").
2.  Install via Homebrew (Recommended):
    ```bash
    brew install android-platform-tools
    ```
    *If you don't have Homebrew, download the [Mac SDK Platform Tools](https://developer.android.com/studio/releases/platform-tools), extract it, and navigate to that folder in Terminal using `cd /path/to/extracted/folder`.*

### **:: LINUX**
1.  Open your terminal.
2.  Install via your package manager:
    *   **Debian/Ubuntu:** `sudo apt install android-sdk-platform-tools-common`
    *   **Fedora/RHEL:** `sudo dnf install android-tools`
    *   **Arch:** `sudo pacman -S android-tools`

---

## 02 // PREPARE THE HOST (PHONE)

1.  Open your Phone **Settings**.
2.  Scroll down to **About Phone**.
3.  Find **Build Number** (often inside "Software Information").
4.  **Tap "Build Number" 7 times.**
    *   *System Response:* "You are now a developer."
5.  Go back to **Settings > System > Developer Options**.
6.  Enable **USB Debugging**.
7.  Connect your phone to your computer via USB cable.
8.  On your computer terminal, type:
    ```bash
    adb devices
    ```
9.  **Look at your phone:** A popup will ask "Allow USB Debugging?". Tap **Allow**.

---

## 03 // PREPARE THE NODE (WATCH)

Wear OS watches rarely have data ports. We must inject the protocol over the air (Wi-Fi).

1.  **Ensure Watch and Computer are on the SAME Wi-Fi network.**
2.  On Watch: **Settings > System > About**.
3.  Tap **Build Number** 7 times to enable Developer Options.
4.  Go back to **Settings > Developer Options**.
5.  Enable **ADB Debugging**.
6.  Enable **Wireless Debugging**.
    *   *Wait for a moment. It should display an IP address and Port (e.g., `192.168.1.50:5555`).*
7.  On your computer terminal, connect to the watch:
    ```bash
    adb connect 192.168.1.XX:5555
    ```
    *(Replace `192.168.1.XX:5555` with the specific IP shown on your watch).*
8.  **Look at your watch:** Tap **Allow** when prompted.

---

## 04 // INJECT PAYLOAD (INSTALLATION)

1.  Download `neonflux-phone-release.apk` and `neonflux-watch-release.apk` from GitHub Releases.
2.  Move both files into your ADB folder (or know their file path).

### **Install on Phone:**
```bash
adb -d install neonflux-phone-release.apk
```
*(`-d` sends the command to the USB-connected device)*

### **Install on Watch:**
```bash
adb -e install neonflux-watch-release.apk
```
*(`-e` sends the command to the Emulator/Wireless device. If this fails, use `adb -s <IP_ADDRESS> install...`)*

---

## 05 // SYSTEM LOCKDOWN

Once installation shows **"Success"**:

1.  **Disable** Wireless Debugging on your Watch.
2.  **Disable** ADB Debugging on your Watch.
3.  **Disable** USB Debugging on your Phone.

**The protocol is now installed. Launch NeonFlux to begin synchronization.**
