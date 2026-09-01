#!/usr/bin/env python3
"""
Simple WOL Server - Amazon Appstore Automated Deployment Script
Allows 1-command build, upload, and publication to Amazon Developer Console without CI/CD.

Usage:
    python scripts/deploy_amazon.py [--build] [--publish] [--apk path/to/app-release.apk]

Prerequisites:
    Create 'secrets/amazon_credentials.json' with:
    {
        "client_id": "amzn1.application-oa2-client...",
        "client_secret": "...",
        "app_id": "amzn1.devportal.mobileapp..."
    }
"""

import os
import sys
import json
import argparse
import subprocess
import urllib.request
import urllib.parse

CREDENTIALS_FILE = os.path.join(os.path.dirname(__file__), "..", "secrets", "amazon_credentials.json")
DEFAULT_APK_PATH = os.path.join(os.path.dirname(__file__), "..", "app", "build", "outputs", "apk", "release", "app-release.apk")

LWA_TOKEN_URL = "https://api.amazon.com/auth/o2/token"
AMAZON_API_BASE = "https://developer.amazon.com/api-appstore/v1"


def load_credentials():
    client_id = os.environ.get("AMAZON_APPSTORE_CLIENT_ID")
    client_secret = os.environ.get("AMAZON_APPSTORE_CLIENT_SECRET")
    app_id = os.environ.get("AMAZON_APPSTORE_APP_ID")

    if os.path.exists(CREDENTIALS_FILE):
        try:
            with open(CREDENTIALS_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
                client_id = client_id or data.get("client_id")
                client_secret = client_secret or data.get("client_secret")
                app_id = app_id or data.get("app_id")
        except Exception as e:
            print(f"[!] Warning: Could not read {CREDENTIALS_FILE}: {e}")

    if not client_id or not client_secret or not app_id:
        print("[X] Error: Missing Amazon API credentials.")
        print(f"    Please create '{CREDENTIALS_FILE}' or set environment variables:")
        print("    AMAZON_APPSTORE_CLIENT_ID, AMAZON_APPSTORE_CLIENT_SECRET, AMAZON_APPSTORE_APP_ID")
        sys.exit(1)

    return client_id.strip(), client_secret.strip(), app_id.strip()


def build_release_apk():
    print("[*] Building release APK (./gradlew assembleRelease)...")
    gradle_cmd = "gradlew.bat" if os.name == "nt" else "./gradlew"
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    
    result = subprocess.run([os.path.join(root_dir, gradle_cmd), "assembleRelease"], cwd=root_dir)
    if result.returncode != 0:
        print("[X] Gradle build failed!")
        sys.exit(1)
    print("[✓] Build successful!")


def get_lwa_access_token(client_id, client_secret):
    print("[*] Authenticating with Login with Amazon (LWA)...")
    data = urllib.parse.urlencode({
        "grant_type": "client_credentials",
        "client_id": client_id,
        "client_secret": client_secret,
        "scope": "appstore:apps:readwrite"
    }).encode("utf-8")

    req = urllib.request.Request(
        LWA_TOKEN_URL,
        data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"}
    )

    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            token = body.get("access_token")
            if not token:
                print(f"[X] Failed to obtain access token: {body}")
                sys.exit(1)
            print("[✓] LWA Authentication successful.")
            return token
    except urllib.error.HTTPError as e:
        print(f"[X] LWA Authentication error ({e.code}): {e.read().decode('utf-8', 'ignore')}")
        sys.exit(1)


def get_or_create_edit(app_id, token):
    print("[*] Finding or creating active edit draft on Amazon Developer Console...")
    url = f"{AMAZON_API_BASE}/applications/{app_id}/edits"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json"
    }

    # 1. Check existing open edit
    req = urllib.request.Request(url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            edit_id = body.get("id")
            if edit_id:
                print(f"[✓] Found existing edit draft: {edit_id}")
                return edit_id
    except urllib.error.HTTPError as e:
        if e.code != 404:
            print(f"[!] Warning checking edits ({e.code}): {e.read().decode('utf-8', 'ignore')}")

    # 2. Create new edit
    req = urllib.request.Request(url, data=b"{}", headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            edit_id = body.get("id")
            print(f"[✓] Created new edit draft: {edit_id}")
            return edit_id
    except urllib.error.HTTPError as e:
        print(f"[X] Failed to create edit ({e.code}): {e.read().decode('utf-8', 'ignore')}")
        sys.exit(1)


def upload_apk(app_id, edit_id, apk_path, token):
    if not os.path.exists(apk_path):
        print(f"[X] APK file not found at: {apk_path}")
        sys.exit(1)

    apk_size_mb = os.path.getsize(apk_path) / (1024 * 1024)
    print(f"[*] Uploading APK ({apk_size_mb:.2f} MB): {apk_path}...")

    # Amazon App Submission API upload endpoint
    upload_url = f"{AMAZON_API_BASE}/applications/{app_id}/edits/{edit_id}/apks/upload"
    
    with open(apk_path, "rb") as f:
        apk_data = f.read()

    req = urllib.request.Request(
        upload_url,
        data=apk_data,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/vnd.android.package-archive"
        },
        method="POST"
    )

    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            print("[✓] APK uploaded successfully!")
            print(f"    APK ID: {body.get('id', 'N/A')}")
            return body
    except urllib.error.HTTPError as e:
        print(f"[X] APK upload failed ({e.code}): {e.read().decode('utf-8', 'ignore')}")
        sys.exit(1)


def publish_edit(app_id, edit_id, token):
    print("[*] Submitting edit for Amazon Appstore review & publishing...")
    url = f"{AMAZON_API_BASE}/applications/{app_id}/edits/{edit_id}/publish"
    req = urllib.request.Request(
        url,
        data=b"{}",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json"
        },
        method="POST"
    )

    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            print("[✓] Edit submitted successfully for publishing to Amazon Appstore!")
    except urllib.error.HTTPError as e:
        print(f"[!] Could not auto-publish ({e.code}): {e.read().decode('utf-8', 'ignore')}")
        print("    You can review the uploaded APK and click 'Submit App' in the Developer Console.")


def main():
    parser = argparse.ArgumentParser(description="Upload & Deploy Simple WOL Server to Amazon Developer Console")
    parser.add_argument("--build", action="store_true", help="Build release APK before uploading")
    parser.add_argument("--publish", action="store_true", help="Automatically submit app for review/publication after upload")
    parser.add_argument("--apk", type=str, default=DEFAULT_APK_PATH, help="Path to APK file to upload")
    args = parser.parse_args()

    client_id, client_secret, app_id = load_credentials()

    if args.build or not os.path.exists(args.apk):
        build_release_apk()

    token = get_lwa_access_token(client_id, client_secret)
    edit_id = get_or_create_edit(app_id, token)
    upload_apk(app_id, edit_id, args.apk, token)

    if args.publish:
        publish_edit(app_id, edit_id, token)
    else:
        print("\n[i] Done! APK uploaded to draft edit.")
        print("    Pass '--publish' to automatically submit for review, or submit manually via developer.amazon.com.")


if __name__ == "__main__":
    main()
