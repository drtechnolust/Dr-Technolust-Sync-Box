#!/usr/bin/env python3
"""Prints the device ID and local key of your own Tuya-based devices.

Uses your own Tuya developer project (see docs/GET_LOCAL_KEY.md). Credentials are
read with hidden prompts and are never stored. The local key is a secret: do not
paste the output anywhere public.

    pip install tinytuya
    python tools/get_local_key.py --region us
"""
import argparse
import getpass
import os
import sys

REGIONS = ("us", "us-e", "eu", "eu-w", "cn", "in", "sg")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--region", required=True, choices=REGIONS, help="data center of your Tuya cloud project")
    args = ap.parse_args()

    try:
        import tinytuya
    except ImportError:
        print("Install the helper first:  pip install tinytuya", file=sys.stderr)
        return 2

    access_id = os.environ.get("TUYA_ACCESS_ID") or getpass.getpass("Project Access ID (hidden): ").strip()
    access_secret = os.environ.get("TUYA_ACCESS_SECRET") or getpass.getpass("Project Access Secret (hidden): ").strip()
    if not access_id or not access_secret:
        print("Both values are required.", file=sys.stderr)
        return 2

    cloud = tinytuya.Cloud(apiRegion=args.region, apiKey=access_id, apiSecret=access_secret)
    devices = cloud.getdevices(verbose=False)
    if not isinstance(devices, list):
        detail = devices.get("Error") if isinstance(devices, dict) else "unexpected reply"
        print(f"Could not list devices: {detail}", file=sys.stderr)
        print("Check the Access ID/Secret, the region, and that IoT Core is subscribed.", file=sys.stderr)
        return 1
    if not devices:
        print("No devices found. Link your app account to the project (Devices > Link Tuya App Account).")
        return 1

    for device in devices:
        print(f"{device.get('name', '(unnamed)')}")
        print(f"  device ID : {device.get('id')}")
        print(f"  local key : {device.get('key')}")
        print(f"  product   : {device.get('product_name', device.get('product_id', ''))}")
    print("\nKeep these private. Enter the device ID and local key on the TV during setup.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
