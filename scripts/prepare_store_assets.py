#!/usr/bin/env python3
"""
Simple WOL Server - Store Asset Generator
Prepares all Amazon Appstore required icons and 1920x1080 screenshots in store_metadata/assets/
"""

import os
import sys
import subprocess
from PIL import Image

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS_DIR = os.path.join(ROOT_DIR, "store_metadata", "assets")
ICON_SRC = os.path.join(ROOT_DIR, "app", "src", "main", "res", "mipmap-xxxhdpi", "ic_launcher.png")
BANNER_SRC = os.path.join(ROOT_DIR, "app", "src", "main", "res", "drawable-xhdpi", "banner.png")
FIRETV_IP = "192.168.0.51:5555"

os.makedirs(ASSETS_DIR, exist_ok=True)


def generate_icons():
    print("[*] Generating Amazon Appstore store icons...")
    if not os.path.exists(ICON_SRC):
        print(f"[X] Source icon not found at {ICON_SRC}")
        return

    icon_img = Image.open(ICON_SRC).convert("RGBA")

    # 1. 512 x 512 px PNG (with transparency)
    icon_512 = icon_img.resize((512, 512), Image.Resampling.LANCZOS)
    icon_512_path = os.path.join(ASSETS_DIR, "icon_512x512.png")
    icon_512.save(icon_512_path, "PNG")
    print(f"[+] Saved: {icon_512_path}")

    # 2. 114 x 114 px PNG (with transparency)
    icon_114 = icon_img.resize((114, 114), Image.Resampling.LANCZOS)
    icon_114_path = os.path.join(ASSETS_DIR, "icon_114x114.png")
    icon_114.save(icon_114_path, "PNG")
    print(f"[+] Saved: {icon_114_path}")

    # 3. Fire TV App Icon 1280 x 720 px PNG (no transparency)
    bg = Image.new("RGB", (1280, 720), (18, 20, 24))
    
    # Scale icon to fit nicely centered with title branding
    icon_scaled = icon_img.resize((360, 360), Image.Resampling.LANCZOS)
    icon_x = (1280 - 360) // 2
    icon_y = (720 - 360) // 2
    bg.paste(icon_scaled, (icon_x, icon_y), icon_scaled)

    firetv_icon_path = os.path.join(ASSETS_DIR, "firetv_icon_1280x720.png")
    bg.save(firetv_icon_path, "PNG")
    print(f"[+] Saved: {firetv_icon_path}")


def capture_firetv_screens():
    print("[*] Capturing 1080p screenshots from live Fire TV...")
    try:
        subprocess.run(["adb", "connect", FIRETV_IP], capture_output=True, timeout=5)
        
        # 1. Main screen
        subprocess.run(["adb", "-s", FIRETV_IP, "shell", "am", "start", "-n", "com.vtstv.wolserver/.MainActivity"], capture_output=True)
        subprocess.run(["adb", "-s", FIRETV_IP, "shell", "screencap", "-p", "/sdcard/screen1_main.png"], capture_output=True)
        subprocess.run(["adb", "-s", FIRETV_IP, "pull", "/sdcard/screen1_main.png", os.path.join(ASSETS_DIR, "screenshot_1_firetv_main.png")], capture_output=True)

        # 2. Scanner dialog
        subprocess.run(["adb", "-s", FIRETV_IP, "shell", "input", "keyevent", "KEYCODE_DPAD_RIGHT"], capture_output=True)
        subprocess.run(["adb", "-s", FIRETV_IP, "shell", "input", "keyevent", "KEYCODE_DPAD_CENTER"], capture_output=True)
        import time; time.sleep(1)
        subprocess.run(["adb", "-s", FIRETV_IP, "shell", "screencap", "-p", "/sdcard/screen2_scan.png"], capture_output=True)
        subprocess.run(["adb", "-s", FIRETV_IP, "pull", "/sdcard/screen2_scan.png", os.path.join(ASSETS_DIR, "screenshot_2_network_scanner.png")], capture_output=True)
        subprocess.run(["adb", "-s", FIRETV_IP, "shell", "input", "keyevent", "KEYCODE_BACK"], capture_output=True)

        # 3. Settings dialog
        subprocess.run(["adb", "-s", FIRETV_IP, "shell", "input", "keyevent", "KEYCODE_DPAD_RIGHT"], capture_output=True)
        subprocess.run(["adb", "-s", FIRETV_IP, "shell", "input", "keyevent", "KEYCODE_DPAD_CENTER"], capture_output=True)
        time.sleep(1)
        subprocess.run(["adb", "-s", FIRETV_IP, "shell", "screencap", "-p", "/sdcard/screen3_settings.png"], capture_output=True)
        subprocess.run(["adb", "-s", FIRETV_IP, "pull", "/sdcard/screen3_settings.png", os.path.join(ASSETS_DIR, "screenshot_3_server_settings.png")], capture_output=True)
        subprocess.run(["adb", "-s", FIRETV_IP, "shell", "input", "keyevent", "KEYCODE_BACK"], capture_output=True)

        print("[+] Fire TV screenshots captured successfully!")
    except Exception as e:
        print(f"[!] Note on ADB screenshot capture: {e}")


if __name__ == "__main__":
    generate_icons()
    capture_firetv_screens()
    print("\n[i] All store assets are ready in: store_metadata/assets/")
