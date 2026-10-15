# Getting your Sync Box's local key

The box only accepts local commands from someone who knows its 16-character
**local key**. It is created when the box is paired and is a secret: treat it
like a password and never post it publicly.

Dr.Technolust Sync Box never contacts any cloud. You obtain the key separately, from
your own account or an authorized vendor-provided method, and type it into the TV.
You do not need to root the TV. Your FancyLEDs account password is not the local key.
Do not reset/unpair a working box just to follow these instructions: pairing can
replace the key and change how the manufacturer's app reaches the device.

## Option A: your own Tuya developer project (if your app account can be linked)

1. Make sure the box is paired in an app account you control (the FancyLEDs
   app or Tuya Smart / Smart Life).
2. Create your own account at <https://iot.tuya.com>, then **Cloud → Development →
   Create Cloud Project**. Pick the data center that matches your app account's
   region (for example *Western America* for US accounts).
3. In the project, enable the required **IoT Core** service entitlement and
   authorize the **Device Status Notification** and **Device
   Management** API groups if offered.
4. **Devices → Link Tuya App Account**, scan the QR code with a supported linked
   app, and confirm. Verify that your specific Sync Box appears in the project.
   Manufacturer-branded accounts are not guaranteed to support this linking flow.
   If you see only unrelated devices, stop: that project does not yet have access
   to your Sync Box. Ask the vendor about a supported local-key/account-linking route.
5. Open **Overview** and copy the project's **Access ID** and **Access Secret**.
6. Run the helper in this repository (it asks for the two values with hidden
   input and prints your device ID and local key):

   ```
   pip install tinytuya
   python tools/get_local_key.py --region us
   ```

   Regions: `us` (US West), `us-e` (US East), `eu`, `eu-w`, `cn`, `in`, `sg`.

Notes:

- If IoT Core access has expired, check the project's Service API page. Trial
  availability, approvals, pricing and account eligibility are controlled by Tuya;
  an expired entitlement can prevent key retrieval, but does not disable this app
  when a valid local key is already saved.
- A developer project does not grant access to a manufacturer's app account or
  devices by itself. Only use accounts/devices you own and can authorize. Never
  copy an app vendor's embedded cloud secret into this project.

## Option B: tinytuya's setup wizard

```
pip install tinytuya
python -m tinytuya wizard
```

It asks for the same Access ID and Secret and saves a `devices.json` with every
device's local key, and can save cloud configuration in `tinytuya.json`.
Both files are private and ignored by this repository. Do not upload them as
release attachments, screenshots or troubleshooting evidence.

## Troubleshooting

- *"The box rejected the local key"*: re-check all 16 characters. Keys are
  case-sensitive and may contain symbols.
- *The key stopped working*: re-pairing or resetting the box creates a new key.
- *Find my box shows nothing*: the TV and the box must be on the same network
  and subnet; guest networks and AP isolation block discovery. You can type the
  box's IP address and device ID instead.
