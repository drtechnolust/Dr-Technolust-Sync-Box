# Fancy Sync Box (product `h2nqrmaskyhckcmf`) data-point map

Source: the product's cloud schema (`/specifications`, fetched 2026-10-14) plus a
live LAN DPS query of the owned box (protocol 3.4). Observed live values are
from the box's state at query time.

## Sent by the TV app today (schema-confirmed)

| DP | Code | Type / range | TV control | Live value |
|---:|---|---|---|---|
| 20 | `switch_led` | boolean | Power | `true` |
| 22 | `bright_value` | int 10..1000 | Brightness (%×10) | 1000 |
| 105 | `hdmi_switch` | int 0..2 | HDMI 1/2/3 (index) | 0 |
| 108 | `light_db` | int 0..1000, step 10 | Diffusion degree (%×10) | 1000 |
| 120 | `wireless_chn` | enum `CH1`,`CH2`,`CH3` | Wireless channel | not reported |
| 121 | `backlight_opt` | enum `Mixed`,`On`,`Off` | Ambient backlight (On = Always) | not reported |
| 109 | `tv_sync_onoff` | boolean | Scene Mode CEC (also shown on Sync tab) | both boolean values verified by LAN write/readback |
| 112 | `hdr_auto` | boolean | HDR auto toggle | both values verified by LAN command/readback |
| 101 | `r_l` | int 0..1 | LED strip orientation (0 = Left, 1 = Right per community profile) | numeric switching/readback verified; physical direction depends on installation |
| 113 | `color_enhance` | int 0..1000 | Color enhancement slider (%×10) | 500 |
| 25 | `scene_data` | 32-character hex string | Active sync, scene or sound mode; first byte selects the preset | reported |
| 24 | `colour_data` | 12-character hex HSV | Color picker: hue 0..360, saturation/value 0..1000; live readback updates the picker | reported |
| 115 | `music_sensitivity` | enum `low`,`middle`,`high` | Sound sensitivity | not normally reported |
| 117 | `blackchk_rate` | enum `AUTO`,`FULL`,`WIDE` | Black-bar detection | not normally reported |
| 123 | `color_correction` | raw four-byte R/G/B/W trim | Calibration; last acknowledged local value remembered | not normally reported |

## Present in the schema, not yet wired (encoding or UI mapping unconfirmed)

| DP | Code | Type | Notes |
|---:|---|---|---|
| 21 | `work_mode` | enum white/colour/scene/music | Not in live DPS |
| 23 | `temp_value` | int 0..1000 | |
| 26 | `countdown` | int | |
| 27 | `music_data` | string | sound modes; encoding unknown |
| 28 | `control_data` | string | |
| 102 | `strip_bar` | int 0..1 | |
| 103 | `control_r_l` | int 0..2 | |
| 104 | `scen` | enum (screen, video, game, rainbow, fire, lighting, firework, star, water, particle, fluid, gravity, newton, breath, static_color, music_*) | command-only; scene-name mapping to the UI's Read/Drip/Flow/Ball/Swing is a guess |
| 106 | `status_report` | raw | |
| 107 | `hdmi_enable` | boolean | live `true` |
| 110 | `tv` | enum remote keys | |
| 111 | `hotkey` | enum | |
| 114 | `panel_config` | raw | |
| 116 | `enable_sync_out` | boolean | |
| 118 | `hdmi_mode_switch` | enum "0","1","2" | |
| 122 | `backlight_val` | raw | ambient brightness encoding unknown |

## Modes, scenes and sound (DP 25 `scene_data`), verified

`scene_data` is a 32-hex string; the first byte selects the mode, followed by
30 zeros. On October 15, 2026, every mode below was written and read back on
the owned F62 using the signed 1.0.3 release-check build, then the original
complete scene payload was restored. This verifies acceptance/readback, not
a visual judgement of each effect. Names and
codes from the community `tuya-local` profile for this product family:

| Byte | Mode | Byte | Mode |
|---|---|---|---|
| `80` | Movie | `91` | Drip |
| `81` | Leisure | `92` | Particle ("Practice" in the profile) |
| `82` | Gaming | `93` | Flow |
| `86` | Rainbow | `94` | Ball |
| `87` | Fire | `95` | Swing |
| `88` | Read | `96` | Breath |
| `89` | Firework | `97` | Color (pure) |
| `90` | Star | `98`–`9D` | Sound: Classic, Pop, Rock, Absolute, Electronic, Ambient |

The schema's separate `scen` enum (DP 104) exists but is command-only and its
effect was not verified, so the TV app uses DP 25.

## Color calibration (DP 123 `color_correction`), implemented

From the phone panel "Adjust Color Parameters": four sliders R, G, B, W, each
−10 % … +10 %, default 0 %. The raw value is four bytes
`[100+R, 100+G, 100+B, 100+W]` (factory default `64 64 64 64`, base64
`ZGRkZA==`). Over the LAN it is sent as the base64 string. The box accepts it
(echoed back) but never reports it, so the TV app remembers the last value it
set. Verified: writing `WmRkZA==` (R −10 %) was accepted and `ZGRkZA==`
restored the default.

### Calibration byte order, confirmed by eye (2026-10-14)

With the Color scene showing a pure primary (`colour_data` `000003E803E8` red,
`007803E803E8` green, `00F003E803E8` blue), cutting one gain byte to 0 turned
the lights fully off for that color and restored them when set back:
byte 0 = red, byte 1 = green, byte 2 = blue; byte 3 = white channel (cutting it
darkens a white scene). A plain white scene is rendered largely by the white
LED, which is why trimming only G or B on white is hard to see.

## Findings from the cloud status (2026-10-14)

The LAN status reply omits many data points (it returns only 20, 22, 24, 25, 101,
105, 107, 108, 109, 112, 113), while the cloud reports all of them. Current
cloud values and what they decode to:

| Code | Cloud value | Decoded |
|---|---|---|
| `backlight_opt` | `Off` | enum (not visible over LAN, so the TV app cannot show the live value) |
| `backlight_val` | `Dw8PDw==` | raw bytes `0F 0F 0F 0F` (4 bytes) |
| `color_correction` | `ZGRkZA==` | raw bytes `64 64 64 64` (4 bytes, 100 each) |
| `panel_config` | `EA8=` | raw bytes `10 0F` |
| `status_report` | `AQEBAKQ=` | raw bytes `01 01 01 00 A4` |
| `wireless_chn` | `CH1` | enum |
| `work_mode` | `white` | enum |
| `music_sensitivity` | `low` | enum |
| `blackchk_rate` | `AUTO` | enum |
| `hdmi_mode_switch` | `0` | enum "0"/"1"/"2" |
| `enable_sync_out` | `false` | boolean |

Raw values are base64 in the cloud. The cloud report log shows DP changes
(`light_db`, `bright_value`, `hdmi_switch`, `tv_sync_onoff`, `hdr_auto`) made
from the TV app and remote, which confirms the local writes are accepted.

To confirm an unwired entry safely: change the setting in the official phone
app while `tools\lan_watch.py` runs (LAN-visible DPs) and compare
`tools\fetch_cloud_status.py` before and after (all DPs).

## State confidence in version 1.0.1

A successful control acknowledgement is not a state report. Unreported
channel, backlight, sensitivity and black-bar values remain unknown; the UI
labels the last acknowledged request as **Sent · Unconfirmed**. A rejected
command does not update that label. Incoming DP 24 is parsed and validated so
the picker follows changes made by other controllers. Ambient level remains
disabled because its local command mapping has not been established.

## Scene Mode CEC — resolved in version 1.0.2

The earlier claim that CEC had no local datapoint was incorrect. The dedicated
[FancyLEDs Tuya Local profile](https://github.com/make-all/tuya-local/blob/c3388b38c639faca6902f8c7876e585fe87e3ae4/custom_components/tuya_local/devices/fancyleds_hdmi_2_1_syncbox.yaml#L51-L57)
explicitly names Boolean DP 109 as Scene Mode CEC. The
[original device-owner investigation](https://github.com/make-all/tuya-local/issues/3664)
also equates TV synchronization with Scene Mode CEC. Those sources describe a
different product ID in the same family, so they establish the label mapping,
not observed behavior on this particular F62.

The saved schema for this F62 independently declares `tv_sync_onoff` as readable/
writable Boolean DP 109. On October 14, 2026, `CecControlTest` changed it in both
directions using the local provider, checked the live query after each write,
and restored the original value. Power (20), scene (25) and HDMI input (105)
were unchanged. This confirms local configuration/readback support on the F62.
TV standby/wake behavior has not been physically power-cycle tested.

The app now derives Scene Mode CEC and the old TV-sync state from one value.
Both controls send `{"109":true}` or `{"109":false}`. No vendor credentials
or vendor SDK are needed. Saved preset field names remain compatible.
