#!/usr/bin/env python3
"""
Simple WOL Server - High-Res Store Screenshot Generator
Configures sample devices on Fire TV and captures pixel-perfect 1920x1080 store screenshots.
"""

import os
import sys
import time
import json
import subprocess
import urllib.request

FIRETV_IP = "192.168.0.51:5555"
SERVER_URL = "http://192.168.0.51:8085"
ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS_DIR = os.path.join(ROOT_DIR, "store_metadata", "assets")
os.makedirs(ASSETS_DIR, exist_ok=True)


def adb_cmd(*args):
    return subprocess.run(["adb", "-s", FIRETV_IP] + list(args), capture_output=True, text=True)


def setup_sample_devices():
    print("[*] Connecting to Fire TV and setting up sample devices...")
    subprocess.run(["adb", "connect", FIRETV_IP], capture_output=True)

    # 1. Login to get token
    login_data = json.dumps({"password": "admin123"}).encode("utf-8")
    req = urllib.request.Request(f"{SERVER_URL}/login", data=login_data, headers={"Content-Type": "application/json"}, method="POST")
    
    token = None
    try:
        with urllib.request.urlopen(req, timeout=5) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            token = data.get("authToken")
    except Exception as e:
        print(f"[!] Login error: {e}")

    if not token:
        print("[!] Trying with empty auth or reading config...")

    devices = [
        {"name": "Gaming PC", "macAddress": "AA:BB:CC:11:22:33", "broadcastAddress": "255.255.255.255", "port": 9, "iconType": "desktop"},
        {"name": "Home Server / NAS", "macAddress": "AA:BB:CC:44:55:66", "broadcastAddress": "192.168.0.255", "port": 9, "iconType": "server"},
        {"name": "PlayStation 5", "macAddress": "AA:BB:CC:77:88:99", "broadcastAddress": "255.255.255.255", "port": 9, "iconType": "console"}
    ]

    for dev in devices:
        try:
            req_data = json.dumps(dev).encode("utf-8")
            headers = {"Content-Type": "application/json"}
            if token:
                headers["Authorization"] = f"Bearer {token}"
            dev_req = urllib.request.Request(f"{SERVER_URL}/api/devices", data=req_data, headers=headers, method="POST")
            urllib.request.urlopen(dev_req, timeout=5)
        except Exception as e:
            print(f"[!] Device add note: {e}")

    print("[+] Devices configured!")


def capture_screenshots():
    print("[*] Capturing 1080p screenshots from Fire TV...")
    # Restart MainActivity to load devices
    adb_cmd("shell", "am", "force-stop", "com.vtstv.wolserver")
    time.sleep(1)
    adb_cmd("shell", "am", "start", "-n", "com.vtstv.wolserver/.MainActivity")
    time.sleep(3)

    # 1. Main Screen
    adb_cmd("shell", "screencap", "-p", "/sdcard/screen1_main.png")
    adb_cmd("pull", "/sdcard/screen1_main.png", os.path.join(ASSETS_DIR, "screenshot_1_firetv_main.png"))
    print("[+] Captured: screenshot_1_firetv_main.png")

    # 2. Open Scanner dialog and wait for scan
    adb_cmd("shell", "input", "keyevent", "KEYCODE_DPAD_UP")
    time.sleep(0.3)
    adb_cmd("shell", "input", "keyevent", "KEYCODE_DPAD_RIGHT")
    time.sleep(0.3)
    adb_cmd("shell", "input", "keyevent", "KEYCODE_DPAD_CENTER")
    time.sleep(11) # Wait for subnet scan to complete
    adb_cmd("shell", "screencap", "-p", "/sdcard/screen2_scan.png")
    adb_cmd("pull", "/sdcard/screen2_scan.png", os.path.join(ASSETS_DIR, "screenshot_3_network_scanner.png"))
    print("[+] Captured: screenshot_3_network_scanner.png")
    adb_cmd("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.5)

    # 3. Open Settings dialog
    adb_cmd("shell", "input", "keyevent", "KEYCODE_DPAD_RIGHT")
    time.sleep(0.3)
    adb_cmd("shell", "input", "keyevent", "KEYCODE_DPAD_CENTER")
    time.sleep(1.5)
    adb_cmd("shell", "screencap", "-p", "/sdcard/screen3_settings.png")
    adb_cmd("pull", "/sdcard/screen3_settings.png", os.path.join(ASSETS_DIR, "screenshot_2_server_settings.png"))
    print("[+] Captured: screenshot_2_server_settings.png")
    adb_cmd("shell", "input", "keyevent", "KEYCODE_BACK")
    time.sleep(0.5)


if __name__ == "__main__":
    setup_sample_devices()
    capture_screenshots()
    print("\n[i] Fresh high-res screenshots updated in: store_metadata/assets/")
